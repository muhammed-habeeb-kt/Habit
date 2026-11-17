package com.payjoy.status

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

/**
 * On-device NSFW screenshot cascade.
 *
 * A fresh frame every single second while a watched app is in front, and only one frame in
 * flight:
 *  - Stage 1 (TFLite MobileNetV2, 224x224, XNNPACK + fp16) scores three full-width squares
 *    that together cover the whole screen, and stage 2 (ONNX NudeNet 320n, 320x320) adds
 *    explicit-body detections on the tiles stage 1 found suspicious.
 *  - Blocking is instant: the first frame whose tiles reach the POSITIVE levels closes the
 *    app, so a fast scroll or a two-second clip is never missed while a second frame would
 *    have been confirming it.
 *  - There is no "screen did not change" gate and no adaptive slow-down: every tick takes a
 *    screenshot and classifies it, so a still explicit image is re-scored every second and
 *    the loop can never fall behind the content.
 *
 * Nothing is ever written to disk and every frame always blocks for real: the calibration
 * recorder is gone, so there is no dry-run switch left to suppress a block screen. Every
 * bitmap and hardware buffer is released in a `finally` block, and models are loaded lazily
 * on the scanner thread.
 *
 * Every failure is recorded instead of swallowed: the foreground notification and the Apps
 * tab diagnostic card read [statusMap] / [summary], so a screenshot error, a broken model
 * or a missing permission is visible on the phone instead of looking like "nothing happens".
 */
object AiVisualScanner {
  private const val TAG = "AiVisualScanner"

  private const val STAGE1_THREADS = 4
  /** NSFW MobileNetV2 output: drawings, hentai, neutral, porn, sexy. */
  private const val STAGE1_CLASSES = 5
  /** Retry a failed model load after this long instead of giving up for the session. */
  private const val MODEL_RETRY_MS = 15_000L
  /**
   * A screenshot callback that never arrives (HyperOS drops one now and then) would keep
   * [isProcessing] true and the cascade would analyse zero frames while it waits. After
   * this long the frame is abandoned: its pixels are dropped, the in-flight guard is
   * released and the next 1 s tick fires a fresh capture, so the scanner is never blind
   * for longer than one capture interval.
   */
  private const val FRAME_TIMEOUT_MS = 800L
  /**
   * A tick can land a few milliseconds early (a rescheduled watchdog tick, a late capture
   * start). Instead of burning the whole tick — two blind seconds — the exact remainder of
   * the interval is waited out with this slack on top, so the loop keeps its 1 s rhythm.
   */
  private const val SCHEDULE_SLACK_MS = 8L
  /** Re-check a busy scanner quickly once its in-flight frame has been released. */
  private const val FRAME_RETRY_MS = 120L

  /** NudeNet class indices that matter: explicit exposed body parts only. */
  private val EXPOSED_CLASS_INDICES = intArrayOf(2, 3, 4, 6, 14)
  /**
   * The 18 NudeNet labels in model order, so a result can name the winning class instead
   * of only its index. Covered classes and faces are listed but never scored.
   */
  private val NUDE_CLASS_NAMES = arrayOf(
    "FEMALE_GENITALIA_COVERED",
    "FACE_FEMALE",
    "BUTTOCKS_EXPOSED",
    "FEMALE_BREAST_EXPOSED",
    "FEMALE_GENITALIA_EXPOSED",
    "MALE_BREAST_EXPOSED",
    "ANUS_EXPOSED",
    "FEET_EXPOSED",
    "BELLY_COVERED",
    "FEET_COVERED",
    "ARMPITS_COVERED",
    "ARMPITS_EXPOSED",
    "FACE_MALE",
    "BELLY_EXPOSED",
    "MALE_GENITALIA_EXPOSED",
    "ANUS_COVERED",
    "BUTTOCKS_COVERED",
    "MALE_GENITALIA_COVERED"
  )
  /** Tile names in the order the squares are cut, used in the verdict reason. */
  private val TILE_NAMES = arrayOf("top", "center", "bottom")
  /** A crop rectangle smaller than this (either axis) is not a usable window. */
  private const val MIN_TILE_PX = 24
  /** YOLOv8 output rows: 4 box coordinates, then one row per class. */
  private const val YOLO_BOX_ROWS = 4
  private const val YOLO_CLASS_COUNT = 18
  private const val YOLO_ROWS = YOLO_BOX_ROWS + YOLO_CLASS_COUNT

  private const val STAGE1_PIXELS =
    AiCascadeConfig.STAGE1_INPUT_SIZE * AiCascadeConfig.STAGE1_INPUT_SIZE
  private const val STAGE2_PIXELS =
    AiCascadeConfig.STAGE2_INPUT_SIZE * AiCascadeConfig.STAGE2_INPUT_SIZE
  private const val STAGE1_BYTES = STAGE1_PIXELS * 3 * 4
  private const val STAGE2_BYTES = STAGE2_PIXELS * 3 * 4
  private val mainHandler = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "habit-ai-cascade").apply { isDaemon = true }
  }

  /** One capture + inference in flight; a tick that arrives meanwhile is dropped. */
  private val isProcessing = AtomicBoolean(false)
  /** When the in-flight capture started, so the 800 ms watchdog can judge it. */
  @Volatile private var processingSince = 0L
  /**
   * Bumped for every capture and for every frame that is abandoned (the 800 ms watchdog
   * fired, or the loop stopped). A callback with a stale generation releases its buffer and
   * is otherwise ignored, so it can never analyse an outdated screen or clear the next
   * capture's in-flight guard.
   */
  private val captureGeneration = AtomicInteger(0)
  @Volatile private var running = false
  @Volatile private var activePackage: String? = null
  /** Monitored website that armed this loop inside a browser, or null for a watched app. */
  @Volatile private var activeDomain: String? = null
  @Volatile private var activeService: AccessibilityService? = null
  /**
   * On-screen rectangle of the watched floating / split window, in display pixels. Null
   * means the full screen, which keeps the classic three-tile crop.
   */
  @Volatile private var activeBounds: Rect? = null

  @Volatile private var lastCaptureAt = 0L

  // region diagnostics
  @Volatile private var appLabel: String? = null
  @Volatile private var frameCount = 0
  @Volatile private var blockCount = 0
  @Volatile private var lastStage1Score = 0f
  @Volatile private var lastStage2Score = 0f
  @Volatile private var lastVerdict = "idle"
  @Volatile private var lastFrameAt = 0L
  @Volatile private var lastError: String? = null
  @Volatile private var lastErrorAt = 0L
  @Volatile private var modelError: String? = null
  @Volatile private var stage1Info: String? = null
  @Volatile private var stage2Info: String? = null
  // endregion

  /** Reused on the single scanner thread so a frame allocates no pixel arrays. */
  private val stage1Pixels = IntArray(STAGE1_PIXELS)
  private val stage2Pixels = IntArray(STAGE2_PIXELS)

  private var stage1: Interpreter? = null
  private var stage1Input: ByteBuffer? = null
  private var ortEnv: OrtEnvironment? = null
  private var stage2: OrtSession? = null
  private var stage2Input: ByteBuffer? = null

  @Volatile private var modelsReady = false
  @Volatile private var modelsFailedAt = 0L

  /**
   * The loop is a strict 1 s rhythm, and no tick is ever wasted: a tick that arrives before
   * the interval has really elapsed (a rescheduled watchdog tick, or a capture that started
   * late) waits out exactly the remaining milliseconds instead of dropping the frame, and a
   * tick that finds a frame still in flight comes back as soon as that frame is released.
   */
  private val tick = object : Runnable {
    override fun run() {
      if (!running) return
      val now = SystemClock.elapsedRealtime()
      val since = now - lastCaptureAt
      if (since < AiCascadeConfig.CAPTURE_INTERVAL_MS) {
        // Android also refuses two screenshots inside one second, so this wait is the only
        // correct move; dropping the tick would blind the scanner for two seconds.
        mainHandler.postDelayed(
          this,
          AiCascadeConfig.CAPTURE_INTERVAL_MS - since + SCHEDULE_SLACK_MS
        )
        return
      }
      val started = captureFrame()
      if (!running) return
      val delay = when {
        // Next capture exactly one interval after this one started.
        started -> (AiCascadeConfig.CAPTURE_INTERVAL_MS -
          (SystemClock.elapsedRealtime() - lastCaptureAt)).coerceAtLeast(0L)
        // A frame is still in flight: come back as soon as it is released, and the interval
        // guard above then waits out whatever is left of the second.
        isProcessing.get() -> FRAME_RETRY_MS
        else -> AiCascadeConfig.CAPTURE_INTERVAL_MS
      }
      mainHandler.postDelayed(this, delay)
    }
  }

  /**
   * HyperOS drops a `takeScreenshot` callback now and then. The watchdog runs on the main
   * thread 800 ms after every capture starts, so a dropped frame is abandoned on time
   * instead of waiting for the next tick.
   */
  private val frameWatchdog = object : Runnable {
    override fun run() {
      if (!isProcessing.get()) return
      val startedAt = processingSince
      if (startedAt == 0L) return
      if (SystemClock.elapsedRealtime() - startedAt < FRAME_TIMEOUT_MS) return
      abandonFrame("screenshot callback timed out after ${FRAME_TIMEOUT_MS} ms; retrying")
    }
  }

  /**
   * Gives up on the frame that is in flight right now: its late callback must not reach the
   * models or clear the next capture's lock, so its generation is invalidated first, then
   * the lock is released and the loop is put back on the fast 1 s rhythm so the next fresh
   * screenshot is taken as soon as the interval allows.
   */
  private fun abandonFrame(reason: String) {
    captureGeneration.incrementAndGet()
    processingSince = 0L
    isProcessing.set(false)
    if (running) {
      // Put the loop back on the 1 s rhythm now instead of waiting for a stale callback.
      mainHandler.removeCallbacks(tick)
      val wait = (AiCascadeConfig.CAPTURE_INTERVAL_MS -
        (SystemClock.elapsedRealtime() - lastCaptureAt)).coerceAtLeast(0L)
      mainHandler.postDelayed(tick, wait)
    }
    recordError(reason)
  }

  val isRunning: Boolean
    get() = running

  fun isRunningFor(pkg: String, domain: String? = null): Boolean =
    running && activePackage == pkg && activeDomain == domain

  /** True while the loop belongs to [pkg], whatever app or website armed it. */
  fun isRunningForPackage(pkg: String): Boolean = running && activePackage == pkg

  /** The website a running browser loop belongs to, or null for an app loop. */
  fun runningDomain(): String? = if (running) activeDomain else null

  /**
   * Starts (or keeps) the loop for [packageName]. The caller has already checked that
   * protection is on, AI monitoring is enabled and either the app is selected or
   * [domain] is one of the watched websites open in that browser.
   *
   * [windowBounds] is the watched window's on-screen rectangle when it is not the focused
   * full-screen app (a freeform floating window or a split-screen half). The scanner crops
   * to it, so the rest of the display is never classified.
   */
  fun start(
    service: AccessibilityService,
    packageName: String,
    domain: String? = null,
    windowBounds: Rect? = null
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      recordError("Screenshots need Android 11 or newer")
      return
    }
    if (running && activePackage == packageName && activeService === service &&
      activeDomain == domain
    ) {
      // Same window target: only its rectangle can have changed (a dragged floating
      // window). No restart, so the cascade never loses its frame history.
      activeBounds = windowBounds?.let { Rect(it) }
      return
    }

    activeService = service
    activePackage = packageName
    activeDomain = domain
    activeBounds = windowBounds?.let { Rect(it) }
    // A website block names the site (`• AI • TELEGRAM.ORG`); an app block names the app.
    appLabel = domain?.uppercase() ?: appLabelFor(service.applicationContext, packageName)
    // A frame from an earlier loop (or one the watchdog abandoned) must never block the
    // first capture of this one: its callback is stale, so it is invalidated here.
    mainHandler.removeCallbacks(frameWatchdog)
    captureGeneration.incrementAndGet()
    processingSince = 0L
    isProcessing.set(false)
    running = true
    frameCount = 0
    lastVerdict = "starting"
    mainHandler.removeCallbacks(tick)
    mainHandler.post(tick)
  }

  /** Stops the loop and drops the borderline-frame memory. Safe from any thread. */
  fun stop() {
    running = false
    mainHandler.removeCallbacks(tick)
    mainHandler.removeCallbacks(frameWatchdog)
    // Any callback still in flight belongs to a frame nobody wants any more, and its
    // generation guard means it will never release this lock itself.
    captureGeneration.incrementAndGet()
    processingSince = 0L
    isProcessing.set(false)
    activeService = null
    activePackage = null
    activeDomain = null
    activeBounds = null
    if (!lastVerdict.startsWith("blocked") && lastVerdict != "idle") lastVerdict = "stopped"
  }

  /** Called the moment the block screen appears: no point classifying a covered screen. */
  fun onOverlayShown() = stop()

  /**
   * Records a failure that happened outside the cascade (for example the block overlay was
   * refused by the launcher), so the phone shows the real reason instead of silence.
   */
  fun noteOverlayError(message: String) = recordError(message)

  /** Closes both interpreters once the accessibility connection goes away. */
  fun release() {
    stop()
    // Flip the flag before the close task runs so no later frame reuses a closed model.
    modelsReady = false
    modelsFailedAt = 0L
    worker.execute {
      closeModels()
      modelsReady = false
      modelsFailedAt = 0L
    }
  }

  // region status

  /**
   * Live diagnostics for the Apps tab card. Every value is a real observation of the
   * running scanner, not a stored setting.
   */
  fun statusMap(context: Context): Map<String, Any?> {
    val now = SystemClock.elapsedRealtime()
    val ai = try {
      HabitStore.aiSettings(context)
    } catch (error: Throwable) {
      null
    }
    val protectionEnabled = try {
      HabitStore.state(context).protectionEnabled
    } catch (error: Throwable) {
      true
    }
    return mapOf(
      "running" to running,
      "packageName" to activePackage,
      "appLabel" to appLabel,
      "frames" to frameCount,
      "captureIntervalMs" to AiCascadeConfig.CAPTURE_INTERVAL_MS,
      "blocks" to blockCount,
      "stage1Score" to lastStage1Score.toDouble(),
      "stage2Score" to lastStage2Score.toDouble(),
      "verdict" to lastVerdict,
      "modelsReady" to modelsReady,
      "modelError" to modelError,
      "stage1Model" to stage1Info,
      "stage2Model" to stage2Info,
      "lastError" to lastError,
      "lastErrorAgeMs" to ageOf(lastErrorAt, now),
      "lastFrameAgeMs" to ageOf(lastFrameAt, now),
      "screenshotSupported" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R),
      "accessibilityEnabled" to HabitAccessibilityService.isEnabled(context),
      "overlayAllowed" to canDrawOverlays(context),
      "monitoredApps" to (ai?.monitoredAppPackages?.size ?: 0),
      "aiEnabled" to (ai?.enabled ?: false),
      "protectionEnabled" to protectionEnabled
    )
  }

  /**
   * Live line for the persistent notification: the watched app, the frame counter and both
   * scores, so the user can see the scanner working inside the app. A model that failed to
   * load is reported by name instead of leaving a silent notification.
   */
  fun summary(): String? {
    val label = appLabel ?: activePackage ?: "app"
    val failure = modelError
    if (!modelsReady && failure != null) {
      return "AI Model Error: $failure"
    }
    val error = lastError
    if (error != null && ageOf(lastErrorAt, SystemClock.elapsedRealtime()) < 15_000) {
      return "AI error: $error"
    }
    if (!running) {
      return error?.let { "AI idle — last error: $it" }
    }
    return "Watching $label • Frame #$frameCount • mob: ${format(lastStage1Score)}" +
      " • nude: ${format(lastStage2Score)}"
  }

  // endregion

  // region self-test

  /**
   * Runs both bundled models once on a generated bitmap (never on the screen) so the user
   * can prove the APK really contains two working models. Nothing is classified or stored.
   */
  fun selfTest(context: Context): Map<String, Any?> {
    val started = SystemClock.elapsedRealtime()
    // Wait for any in-flight capture so the reused buffers and interpreter are free.
    val waitUntil = started + 5_000
    while (isProcessing.get() && SystemClock.elapsedRealtime() < waitUntil) {
      Thread.sleep(20)
    }
    val latch = CountDownLatch(1)
    var result: Map<String, Any?>? = null
    worker.execute {
      result = runSelfTest(context)
      latch.countDown()
    }
    if (!latch.await(60, TimeUnit.SECONDS)) {
      return mapOf(
        "stage1Ok" to false,
        "stage1Score" to 0.0,
        "stage1Error" to "stage 1 self-test timed out",
        "stage2Ok" to false,
        "stage2Score" to 0.0,
        "stage2Error" to "stage 2 self-test timed out",
        "elapsedMs" to (SystemClock.elapsedRealtime() - started)
      )
    }
    return result ?: mapOf(
      "stage1Ok" to false,
      "stage1Score" to 0.0,
      "stage1Error" to "self-test produced no result",
      "stage2Ok" to false,
      "stage2Score" to 0.0,
      "stage2Error" to "self-test produced no result",
      "elapsedMs" to (SystemClock.elapsedRealtime() - started)
    )
  }

  private fun runSelfTest(context: Context): Map<String, Any?> {
    val started = SystemClock.elapsedRealtime()
    var stage1Ok = false
    var stage1Score = 0f
    var stage1Error: String? = null
    var stage2Ok = false
    var stage2Score = 0f
    var stage2Error: String? = null

    val ready = ensureModels(context)
    if (!ready) {
      stage1Error = modelError ?: "models could not be loaded"
      stage2Error = stage1Error

      return mapOf(
        "stage1Ok" to false,
        "stage1Score" to 0.0,
        "stage1Error" to stage1Error,
        "stage2Ok" to false,
        "stage2Score" to 0.0,
        "stage2Error" to stage2Error,
        "elapsedMs" to (SystemClock.elapsedRealtime() - started)
      )
    }

    val probe1 = probeBitmap(AiCascadeConfig.STAGE1_INPUT_SIZE)
    try {
      stage1Score = stage1Score(probe1, AiCascadeConfig.thresholds(context).sexyWeight)
      stage1Ok = true
    } catch (error: Throwable) {
      stage1Error = describe(error)
    } finally {
      recycle(probe1)
    }

    val probe2 = probeBitmap(AiCascadeConfig.STAGE2_INPUT_SIZE)
    try {
      stage2Score = stage2Score(probe2)
      stage2Ok = true
    } catch (error: Throwable) {
      stage2Error = describe(error)
    } finally {
      recycle(probe2)
    }

    return mapOf(
      "stage1Ok" to stage1Ok,
      "stage1Score" to stage1Score.toDouble(),
      "stage1Error" to stage1Error,
      "stage2Ok" to stage2Ok,
      "stage2Score" to stage2Score.toDouble(),
      "stage2Error" to stage2Error,
      "elapsedMs" to (SystemClock.elapsedRealtime() - started)
    )
  }

  /** Deterministic grey ramp; a neutral image the models can classify without a screen. */
  private fun probeBitmap(size: Int): Bitmap {
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
      for (x in 0 until size) {
        val value = 40 + ((x * 3 + y * 2) % 160)
        pixels[y * size + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
      }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
  }

  // endregion

  // region capture loop

  /** True when a capture was actually requested for this tick. */
  private fun captureFrame(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val service = activeService ?: run {
      stop()
      return false
    }
    val pkg = activePackage ?: run {
      stop()
      return false
    }

    if (!shouldScan(service, pkg, activeDomain)) {
      stop()
      return false
    }
    if (!isScreenInteractive(service)) return false

    val now = SystemClock.elapsedRealtime()
    if (now - lastCaptureAt < AiCascadeConfig.CAPTURE_INTERVAL_MS) return false
    if (!isProcessing.compareAndSet(false, true)) return false
    processingSince = now
    lastCaptureAt = now
    // The watchdog and every callback share this generation: whoever abandons the frame
    // first invalidates the other's callback, so a dropped frame can never wedge the loop
    // or be analysed out of order.
    val generation = captureGeneration.incrementAndGet()
    mainHandler.removeCallbacks(frameWatchdog)
    mainHandler.postDelayed(frameWatchdog, FRAME_TIMEOUT_MS)

    try {
      service.takeScreenshot(
        Display.DEFAULT_DISPLAY,
        worker,
        object : AccessibilityService.TakeScreenshotCallback {
          override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            if (generation != captureGeneration.get()) {
              // The watchdog already gave up on this frame: release its pixels, keep nothing.
              closeScreenshot(screenshot)
              return
            }
            mainHandler.removeCallbacks(frameWatchdog)
            handleScreenshot(pkg, screenshot)
          }

          override fun onFailure(errorCode: Int) {
            if (generation != captureGeneration.get()) return
            mainHandler.removeCallbacks(frameWatchdog)
            processingSince = 0L
            isProcessing.set(false)
            // A refused frame is evidence too: inside a browser, the private-tab guard reads
            // the FLAG_SECURE refusal from this very capture, so a secure tab stays caught
            // even while the cascade owns Android's one-second screenshot quota.
            (activeService as? HabitAccessibilityService)
              ?.onScreenshotRefused(pkg, errorCode)
            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
              // Asked too soon after the last capture; wait for the next tick silently.
              lastCaptureAt = SystemClock.elapsedRealtime()
              return
            }
            recordError("screenshot failed: ${screenshotErrorName(errorCode)}")
          }
        }
      )
    } catch (error: Throwable) {
      mainHandler.removeCallbacks(frameWatchdog)
      if (generation == captureGeneration.get()) {
        processingSince = 0L
        isProcessing.set(false)
      }
      recordError("takeScreenshot threw: ${describe(error)}")
    }
    return true
  }

  /** Releases a screenshot the watchdog already abandoned, so no hardware buffer leaks. */
  private fun closeScreenshot(screenshot: AccessibilityService.ScreenshotResult) {
    try {
      screenshot.hardwareBuffer.close()
    } catch (error: Throwable) {
      Log.w(TAG, "buffer close failed: ${error.message}")
    }
  }

  private fun handleScreenshot(
    pkg: String,
    screenshot: AccessibilityService.ScreenshotResult
  ) {
    var buffer: HardwareBuffer? = null
    var hardware: Bitmap? = null
    var screen: Bitmap? = null
    try {
      val hardwareBuffer = screenshot.hardwareBuffer
      buffer = hardwareBuffer
      if (!isActive(pkg)) return
      val service = activeService ?: return
      val context = service.applicationContext

      // HARDWARE bitmaps cannot be read pixel by pixel, so copy once to ARGB_8888.
      val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        ?: run {
          recordError("could not wrap the screenshot buffer")
          return
        }
      hardware = hardwareBitmap
      val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        ?: run {
          recordError("could not copy the screenshot into ARGB_8888")
          return
        }
      screen = softwareBitmap

      // A frame that really arrived proves the screenshot capability, which is what lets the
      // private-tab guard read HyperOS's ambiguous error 2 as a FLAG_SECURE private tab.
      (service as? HabitAccessibilityService)?.onScreenshotCaptured()

      analyze(pkg, softwareBitmap, AiCascadeConfig.thresholds(context))
    } catch (error: Throwable) {
      recordError("frame failed: ${describe(error)}")
    } finally {
      recycle(screen)
      recycle(hardware)
      try {
        buffer?.close()
      } catch (error: Throwable) {
        Log.w(TAG, "buffer close failed: ${error.message}")
      }
      // Unconditional: whatever happened above (a throw, a block that stopped the loop, a
      // model error), this frame's pixels are gone and the in-flight guard must be free,
      // otherwise the scanner would stay wedged on `isProcessing = true` and never capture
      // again. A late callback for an abandoned frame never reaches here: onSuccess drops
      // it by generation first.
      processingSince = 0L
      isProcessing.set(false)
    }
  }

  private fun analyze(
    pkg: String,
    screen: Bitmap,
    thresholds: AiCascadeConfig.Thresholds
  ) {
    val service = activeService ?: return
    val context = service.applicationContext

    // Every single frame is classified: there is no pixel-diff gate, so a still explicit
    // image is re-scored each second instead of waiting for the screen to change.
    if (!ensureModels(context)) {
      // Keep the loop alive: the model load is retried, and the notification shows why.
      lastVerdict = "model error"
      return
    }

    // Only the watched area is classified: the whole screen as three full-width squares
    // for a full-screen window, or the floating / split window's own rectangle.
    val plan = planTiles(screen)
    val tiles = ArrayList<Bitmap>(plan.rects.size)
    val tileNames = ArrayList<String>(plan.rects.size)

    try {
      // Every crop goes through safeCrop, which clamps the rectangle into the real bitmap
      // before Bitmap.createBitmap runs, so a stale or unscaled window rectangle can never
      // throw IllegalArgumentException.
      for (index in plan.rects.indices) {
        val tile = safeCrop(screen, plan.rects[index]) ?: continue
        tiles.add(tile)
        tileNames.add(plan.names.getOrElse(index) { "tile" })
      }

      val probabilities = arrayOfNulls<FloatArray>(tiles.size)
      val skinRatios = FloatArray(tiles.size)
      val scores = FloatArray(tiles.size)
      for (index in tiles.indices) {
        val result = stage1Result(tiles[index])
        probabilities[index] = result.probabilities
        skinRatios[index] = result.skinRatio
        scores[index] = combinedStage1Score(result.probabilities, thresholds.sexyWeight)
      }

      // Skin gate: a text / code / dashboard tile has (almost) no human skin, so its stage 1
      // output is not evidence on its own. GitHub in dark mode scored `Hentai` 0.25+ from
      // coloured icons and white text and used to be blocked after two frames. Such a tile
      // can only be positive when NudeNet actually sees an exposed body part.
      fun stage1Allowed(index: Int): Boolean =
        skinRatios[index] >= AiCascadeConfig.SKIN_MIN_RATIO

      // Stage 1 `Hentai` alone is the second false-positive source (dark high-contrast UI).
      // It only counts at full confidence or when NudeNet backs it up.
      fun hentaiTrusted(index: Int, nude: Float): Boolean {
        val output = probabilities[index] ?: return false
        return probability(output, 1) >= AiCascadeConfig.HENTAI_SOLO_TRUST ||
          nude >= AiCascadeConfig.HENTAI_NUDE_TRUST
      }

      /** One reason line for the verdict, so the early and late blocks read the same. */
      fun describeIndex(index: Int, nudeScore: Float): String {
        val output = probabilities[index] ?: FloatArray(0)
        val hentai = if (hentaiTrusted(index, nudeScore)) probability(output, 1) else 0f
        val porn = probability(output, 3)
        val effective = porn + hentai + thresholds.sexyWeight * probability(output, 4)
        return "${tileNames.getOrElse(index) { "tile" }} stage1 ${format(effective)}," +
          " explicit ${format(porn + hentai)}, sexy ${format(probability(output, 4))}," +
          " nude ${format(nudeScore)}, skin ${format(skinRatios[index])}"
      }

      /** Stage 1 alone is enough: this is the instant verdict, NudeNet is only a backstop. */
      fun positiveFromStage1(index: Int): Boolean {
        val output = probabilities[index] ?: return false
        if (!stage1Allowed(index)) return false
        val hentai = if (hentaiTrusted(index, 0f)) probability(output, 1) else 0f
        val porn = probability(output, 3)
        val combined = porn + hentai + thresholds.sexyWeight * probability(output, 4)
        return combined >= AiCascadeConfig.POSITIVE_STAGE1_COMBINED ||
          porn + hentai >= AiCascadeConfig.POSITIVE_STAGE1_EXPLICIT ||
          probability(output, 4) >= AiCascadeConfig.POSITIVE_STAGE1_SEXY
      }

      val strongest = scores.indices
        .filter { index -> stage1Allowed(index) }
        .maxOfOrNull { index -> scores[index] } ?: 0f
      lastStage1Score = strongest
      lastStage2Score = 0f
      lastFrameAt = SystemClock.elapsedRealtime()
      frameCount += 1

      // INSTANT block: the moment stage 1 comes back positive the app is closed, without
      // waiting for NudeNet or for any second frame. This is the millisecond a fast scroll
      // past a photo is caught on; the extra detector only ever runs when stage 1 is unsure.
      val instantIndex = scores.indices.firstOrNull { index -> positiveFromStage1(index) }
      if (instantIndex != null) {
        blockCount += 1
        lastVerdict = "blocked (instant stage1: ${describeIndex(instantIndex, 0f)})"
        triggerBlock(pkg, plan.floating)
        return
      }

      // Stage 2 is additive: it runs on tiles with any stage 1 signal, so `s_nude_max`
      // can still flag an explicit frame stage 1 was unsure about.
      val gated = scores.indices.filter { index ->
        val output = probabilities[index] ?: return@filter false
        scores[index] >= AiCascadeConfig.STAGE2_GATE_SCORE ||
          probability(output, 3) >= AiCascadeConfig.STAGE2_GATE_PORN ||
          probability(output, 4) >= AiCascadeConfig.STAGE2_GATE_SEXY
      }

      val nudity = arrayOfNulls<NudeResult>(tiles.size)
      for (index in gated) nudity[index] = stage2Detail(tiles[index])
      val nudeMax = nudity.maxOfOrNull { it?.score ?: 0f } ?: 0f
      lastStage2Score = nudeMax

      // POSITIVE definition: ANY tile that reaches one of these levels makes this an 18+
      // frame, and one positive frame blocks on the spot. A fast scroll past a photo or a
      // two-second clip is the normal case, so waiting for a second confirming screen would
      // miss exactly the content the scanner exists for.
      //
      // A tile without human skin in it is never positive from stage 1, and `Hentai` alone
      // needs full confidence or NudeNet confirmation. That keeps dark-mode websites and
      // code dashboards out of the cascade while a real photo still scores normally.
      val positiveIndex = scores.indices.firstOrNull { index ->
        val output = probabilities[index] ?: return@firstOrNull false
        val nudeScore = nudity[index]?.score ?: 0f
        if (!stage1Allowed(index)) {
          return@firstOrNull nudeScore >= AiCascadeConfig.SKINLESS_NUDE_CONFIRM
        }
        val hentai = if (hentaiTrusted(index, nudeScore)) probability(output, 1) else 0f
        val porn = probability(output, 3)
        val explicit = porn + hentai
        val combined = porn + hentai + thresholds.sexyWeight * probability(output, 4)
        combined >= AiCascadeConfig.POSITIVE_STAGE1_COMBINED ||
          explicit >= AiCascadeConfig.POSITIVE_STAGE1_EXPLICIT ||
          probability(output, 4) >= AiCascadeConfig.POSITIVE_STAGE1_SEXY ||
          nudeScore >= AiCascadeConfig.POSITIVE_NUDE
      }

      val reason = positiveIndex?.let { index ->
        describeIndex(index, nudity[index]?.score ?: 0f)
      }

      if (positiveIndex != null) {
        // Stage 1 was unsure and NudeNet confirmed: still one frame, no confirmation screen.
        blockCount += 1
        lastVerdict = "blocked (nudenet confirmed: $reason)"
        triggerBlock(pkg, plan.floating)
      } else {
        lastVerdict = "safe (stage1 ${format(strongest)}, nudenet ${format(nudeMax)})"
      }
    } finally {
      for (tile in tiles) recycle(tile)
    }
  }

  /** Where the models look this frame: rectangles plus a short name for the reason line. */
  private data class TilePlan(
    val rects: List<Rect>,
    val names: List<String>,
    /** True when the watched window covers only part of the display (floating / split). */
    val floating: Boolean
  )

  /**
   * Smart, crash-proof crop plan.
   *
   * Every rectangle is built from normalized percentages of the REAL bitmap and clamped
   * with `coerceIn` (and [safeCrop]) before `Bitmap.createBitmap` sees it, so a stale or
   * unscaled window rectangle can never throw IllegalArgumentException.
   *
   * Case A — a full-screen window (or no bounds at all): three full-width overlapping
   * squares (top, centre, bottom) over the WHOLE screen, from the first pixel row to the
   * last. Nothing is trimmed and no gutter is left out, so a photo or video anywhere on
   * screen is always inside a tile.
   *
   * Case B — a floating or split window: the screenshot is cropped to the window first (its
   * own title bar dropped), so no CPU is spent on the rest of the display. A window that is
   * already roughly square becomes ONE tile — a third of the stage 1 / stage 2 work at 100%
   * of the window's pixels. A taller or wider window is covered by two overlapping squares
   * along its long axis.
   */
  private fun planTiles(screen: Bitmap): TilePlan {
    val screenWidth = screen.width.coerceAtLeast(1)
    val screenHeight = screen.height.coerceAtLeast(1)
    // activeBounds arrives in display coordinates, which do not always match the captured
    // bitmap, so it is mapped to fractions of the real bitmap instead of being used raw.
    val bounds = normalizedWindow(screen)
    val left = bounds?.left ?: 0
    val top = bounds?.top ?: 0
    val right = bounds?.right ?: screenWidth
    val bottom = bounds?.bottom ?: screenHeight
    val windowWidth = (right - left).coerceAtLeast(1)
    val windowHeight = (bottom - top).coerceAtLeast(1)
    val coversScreen =
      windowWidth >= screenWidth * AiCascadeConfig.WINDOW_FULL_SCREEN_RATIO &&
        windowHeight >= screenHeight * AiCascadeConfig.WINDOW_FULL_SCREEN_RATIO

    if (!coversScreen) {
      // The window's own title / drag bar is a control strip, never content.
      val titleBar = (windowHeight * AiCascadeConfig.WINDOW_TITLE_RATIO).toInt()
      val contentTop = (top + titleBar).coerceAtMost(bottom - 1)
      val contentHeight = (bottom - contentTop).coerceAtLeast(1)
      val ratio =
        maxOf(windowWidth, contentHeight).toFloat() / minOf(windowWidth, contentHeight).toFloat()
      val side = minOf(windowWidth, contentHeight).coerceAtLeast(1)
      if (ratio <= AiCascadeConfig.WINDOW_SQUARE_RATIO) {
        val x = left + (windowWidth - side) / 2
        val y = contentTop + (contentHeight - side) / 2
        return TilePlan(listOf(Rect(x, y, x + side, y + side)), listOf("window"), true)
      }
      if (windowWidth >= contentHeight) {
        val y = contentTop + (contentHeight - side) / 2
        val xs = intArrayOf(left, right - side).distinct()
        return TilePlan(
          xs.map { x -> Rect(x, y, x + side, y + side) },
          windowTileNames(xs.size),
          true
        )
      }
      val x = left + (windowWidth - side) / 2
      val ys = intArrayOf(contentTop, bottom - side).distinct()
      return TilePlan(
        ys.map { y -> Rect(x, y, x + side, y + side) },
        windowTileNames(ys.size),
        true
      )
    }

    // Three squares as wide as the display, covering it from the top row to the bottom row.
    // No status-bar strip is dropped and no avatar gutter is masked out, so the tiles
    // together contain every pixel of the screen.
    val tileSide = min(screenWidth, screenHeight).coerceAtLeast(1)
    val x = (screenWidth - tileSide) / 2
    val yEnd = (screenHeight - tileSide).coerceAtLeast(0)
    val offsets = intArrayOf(0, yEnd / 2, yEnd)
    return TilePlan(
      offsets.map { y -> Rect(x, y, x + tileSide, y + tileSide) },
      TILE_NAMES.toList(),
      false
    )
  }

  private fun windowTileNames(count: Int): List<String> =
    if (count <= 1) listOf("window") else List(count) { index -> "window ${index + 1}" }

  /**
   * The watched floating / split window in the screenshot's own pixel space.
   *
   * [activeBounds] is in display coordinates, which do not always match the captured bitmap
   * (scaled screenshots, cutouts, rotation). Converting to fractions of the display and back
   * to fractions of the bitmap keeps the crop inside the real pixels; a degenerate rectangle
   * falls back to the whole screen.
   */
  private fun normalizedWindow(screen: Bitmap): Rect? {
    val bounds = activeBounds ?: return null
    val metrics = activeService?.resources?.displayMetrics ?: return null
    val displayWidth = metrics.widthPixels.coerceAtLeast(1)
    val displayHeight = metrics.heightPixels.coerceAtLeast(1)
    val left = (bounds.left.toFloat() / displayWidth * screen.width).toInt()
      .coerceIn(0, screen.width - 1)
    val top = (bounds.top.toFloat() / displayHeight * screen.height).toInt()
      .coerceIn(0, screen.height - 1)
    val right = (bounds.right.toFloat() / displayWidth * screen.width).toInt()
      .coerceIn(left + 1, screen.width)
    val bottom = (bounds.bottom.toFloat() / displayHeight * screen.height).toInt()
      .coerceIn(top + 1, screen.height)
    if (right - left < MIN_TILE_PX || bottom - top < MIN_TILE_PX) return null
    return Rect(left, top, right, bottom)
  }

  /** The one place a tile is cut: clamped into the real bitmap, so it can never throw. */
  private fun safeCrop(screen: Bitmap, rect: Rect): Bitmap? {
    if (screen.width <= 0 || screen.height <= 0) return null
    val left = rect.left.coerceIn(0, screen.width - 1)
    val top = rect.top.coerceIn(0, screen.height - 1)
    val right = rect.right.coerceIn(left + 1, screen.width)
    val bottom = rect.bottom.coerceIn(top + 1, screen.height)
    if (right - left < MIN_TILE_PX || bottom - top < MIN_TILE_PX) return null
    return try {
      Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
    } catch (error: Throwable) {
      recordError("tile crop failed: ${describe(error)}")
      null
    }
  }

  // endregion

  // region models

  private fun ensureModels(context: Context): Boolean {
    if (modelsReady) return true
    // A failed load is retried later instead of disabling the scanner for the whole session.
    if (modelsFailedAt != 0L && SystemClock.elapsedRealtime() - modelsFailedAt < MODEL_RETRY_MS) {
      return false
    }

    return try {
      closeModels()

      // XNNPACK is the only delegate that keeps a 224px MobileNet inside a few ms, and fp16
      // accumulation (ARMv8.2+) halves its memory traffic; unsupported CPUs fall back to
      // fp32 kernels of the same graph without failing the load.
      val options = Interpreter.Options()
        .setNumThreads(STAGE1_THREADS)
        .setUseXNNPACK(true)
        .setAllowFp16PrecisionForFp32(true)
      val interpreter = Interpreter(loadTflite(context), options)
      stage1Info = describeTflite(interpreter)

      val inputShape = interpreter.getInputTensor(0).shape()
      if (!inputShape.contentEquals(
          intArrayOf(
            AiCascadeConfig.STAGE1_INPUT_SIZE,
            AiCascadeConfig.STAGE1_INPUT_SIZE,
            3
          )
        ) &&
        !inputShape.contentEquals(
          intArrayOf(
            1,
            AiCascadeConfig.STAGE1_INPUT_SIZE,
            AiCascadeConfig.STAGE1_INPUT_SIZE,
            3
          )
        )
      ) {
        interpreter.close()
        throw IllegalStateException("stage 1 input shape is ${inputShape.joinToString("x")}")
      }

      val env = OrtEnvironment.getEnvironment()
      val session = try {
        val bytes = context.assets.open(AiCascadeConfig.STAGE2_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions()
        try {
          env.createSession(bytes, options)
        } finally {
          options.close()
        }
      } catch (error: Throwable) {
        interpreter.close()
        throw error
      }

      val inputName = try {
        session.inputNames.first()
      } catch (error: Throwable) {
        ""
      }
      if (inputName.isNotEmpty()) {
        val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
        stage2Info = "${AiCascadeConfig.STAGE2_ASSET} " +
          (shape?.joinToString("x") ?: "input") +
          " -> " + session.outputNames.joinToString(",")
      } else {
        stage2Info = AiCascadeConfig.STAGE2_ASSET
      }

      stage1 = interpreter
      ortEnv = env
      stage2 = session
      stage1Input = ByteBuffer.allocateDirect(STAGE1_BYTES).order(ByteOrder.nativeOrder())
      stage2Input = ByteBuffer.allocateDirect(STAGE2_BYTES).order(ByteOrder.nativeOrder())
      modelsReady = true
      modelsFailedAt = 0L
      modelError = null
      true
    } catch (error: Throwable) {
      modelsFailedAt = SystemClock.elapsedRealtime()
      modelError = describe(error)
      stage1Info = null
      stage2Info = null
      recordError("models unavailable: $modelError")
      closeModels()
      false
    }
  }

  private fun closeModels() {
    try {
      stage1?.close()
    } catch (error: Throwable) {
      Log.w(TAG, "stage 1 close failed: ${error.message}")
    }
    stage1 = null
    stage1Input = null
    try {
      stage2?.close()
    } catch (error: Throwable) {
      Log.w(TAG, "stage 2 close failed: ${error.message}")
    }
    stage2 = null
    stage2Input = null
    modelsReady = false
  }

  /**
   * Loads stage 1 either memory-mapped (fastest) or, when the asset ended up compressed in
   * the APK and `openFd` refuses it, copied into a direct buffer. The copy path is what
   * keeps the scanner working on a build where `noCompress` did not take effect.
   */
  private fun loadTflite(context: Context): ByteBuffer {
    try {
      val descriptor = context.assets.openFd(AiCascadeConfig.STAGE1_ASSET)
      val stream = FileInputStream(descriptor.fileDescriptor)
      // MappedByteBuffer is a ByteBuffer, so Interpreter's ByteBuffer constructor is used.
      val mapped: MappedByteBuffer = stream.channel.map(
        FileChannel.MapMode.READ_ONLY,
        descriptor.startOffset,
        descriptor.declaredLength
      )
      mapped.rewind()
      return mapped
    } catch (error: Throwable) {
      Log.w(TAG, "asset is not mappable (${error.message}); copying it instead")
      val bytes = context.assets.open(AiCascadeConfig.STAGE1_ASSET).use { it.readBytes() }
      if (bytes.isEmpty()) throw IllegalStateException("stage 1 asset is empty")
      val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
      buffer.put(bytes)
      buffer.rewind()
      return buffer
    }
  }

  private fun describeTflite(interpreter: Interpreter): String {
    return try {
      val input = interpreter.getInputTensor(0)
      val output = interpreter.getOutputTensor(0)
      "${AiCascadeConfig.STAGE1_ASSET} ${input.dataType()} " +
        input.shape().joinToString("x", "[", "]") +
        " -> " + output.shape().joinToString("x", "[", "]") +
        " (XNNPACK, fp16)"
    } catch (error: Throwable) {
      AiCascadeConfig.STAGE1_ASSET
    }
  }

  /** Stage 1 combined score for the self-test. */
  private fun stage1Score(crop: Bitmap, sexyWeight: Float): Float =
    combinedStage1Score(stage1Result(crop).probabilities, sexyWeight)

  /** One stage 1 run: every class probability plus the tile's skin-tone fraction. */
  private data class Stage1Result(val probabilities: FloatArray, val skinRatio: Float)

  /**
   * Stage 1 raw output: RGB / 255, NHWC [1, 224, 224, 3] -> one probability per class, in
   * the model order drawing, hentai, neutral, porn, sexy. The combined score is computed
   * separately by [combinedStage1Score], which needs the per-class probabilities.
   *
   * The same 224x224 pixels give the tile's skin-tone ratio for free: text, code and a
   * dashboard UI have none, so the caller can refuse a stage 1-only verdict on them.
   */
  private fun stage1Result(crop: Bitmap): Stage1Result {
    val interpreter = stage1 ?: error("stage 1 model is not loaded")
    val buffer = stage1Input ?: error("stage 1 buffer is missing")
    val size = AiCascadeConfig.STAGE1_INPUT_SIZE
    val resized = Bitmap.createScaledBitmap(crop, size, size, true)

    try {
      resized.getPixels(stage1Pixels, 0, size, 0, 0, size, size)

      var skinPixels = 0
      buffer.rewind()
      val floats = buffer.asFloatBuffer()
      floats.clear()
      for (pixel in stage1Pixels) {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        if (isSkinTone(red, green, blue)) skinPixels += 1
        floats.put(red / 255f)
        floats.put(green / 255f)
        floats.put(blue / 255f)
      }
      floats.rewind()
      buffer.rewind()

      val outputShape = interpreter.getOutputTensor(0).shape()
      val classes = outputShape.lastOrNull()?.coerceAtLeast(1) ?: STAGE1_CLASSES
      val output = Array(1) { FloatArray(classes) }
      interpreter.run(buffer, output)
      val skinRatio = if (stage1Pixels.isEmpty()) {
        0f
      } else {
        skinPixels.toFloat() / stage1Pixels.size
      }
      return Stage1Result(output[0], skinRatio)
    } finally {
      if (resized !== crop) recycle(resized)
    }
  }

  /** Classic RGB skin-tone test: warm, bright enough and clearly red-dominant. */
  private fun isSkinTone(red: Int, green: Int, blue: Int): Boolean =
    red > 95 && green > 40 && blue > 20 &&
      red > green && red > blue && red - green > 15

  /**
   * Stage 1 combined score: Porn + Hentai + sexyWeight * Sexy. The sexy weight is the
   * sensitivity preset's (`0.85` in high, `0.60` in balanced, `0.30` in low), so bikini,
   * cleavage and lingerie frames score high enough to block without NudeNet.
   */
  private fun combinedStage1Score(probabilities: FloatArray, sexyWeight: Float): Float = when {
    probabilities.size >= STAGE1_CLASSES ->
      probabilities[3] + probabilities[1] + sexyWeight * probabilities[4]
    probabilities.size == 1 -> probabilities[0]
    probabilities.isEmpty() -> 0f
    // Unknown class order: treat every non-zero class as explicit rather than ignoring it.
    else -> probabilities.maxOrNull() ?: 0f
  }

  /** One class probability, 0 for a model whose output is shorter than the NSFW classes. */
  private fun probability(probabilities: FloatArray, index: Int): Float =
    if (index in probabilities.indices) probabilities[index] else 0f

  /** One NudeNet verdict: the strongest explicit class found in a tile. */
  private data class NudeResult(val score: Float, val className: String)

  /** Stage 2: NCHW [1, 3, 320, 320] -> highest confidence of the explicit exposed classes. */
  private fun stage2Score(crop: Bitmap): Float = stage2Detail(crop).score

  /** Stage 2 including the class that produced the score. */
  private fun stage2Detail(crop: Bitmap): NudeResult {
    val env = ortEnv ?: error("ONNX environment is not loaded")
    val session = stage2 ?: error("stage 2 model is not loaded")
    val buffer = stage2Input ?: error("stage 2 buffer is missing")
    val size = AiCascadeConfig.STAGE2_INPUT_SIZE
    val resized = Bitmap.createScaledBitmap(crop, size, size, true)

    try {
      resized.getPixels(stage2Pixels, 0, size, 0, 0, size, size)

      buffer.rewind()
      val floats = buffer.asFloatBuffer()
      floats.clear()
      for (channel in 0..2) {
        val shift = when (channel) {
          0 -> 16
          1 -> 8
          else -> 0
        }
        for (pixel in stage2Pixels) {
          floats.put(((pixel shr shift) and 0xFF) / 255f)
        }
      }
      floats.rewind()
      buffer.rewind()

      val tensor = OnnxTensor.createTensor(
        env,
        buffer.asFloatBuffer(),
        longArrayOf(1L, 3L, size.toLong(), size.toLong())
      )
      tensor.use { input ->
        val inputName = session.inputNames.first()
        session.run(mapOf(inputName to input)).use { result ->
          val value = result.get(0)
          if (value is OnnxTensor) return readNudeDetail(value)
        }
      }
    } finally {
      if (resized !== crop) recycle(resized)
    }
    return NudeResult(0f, "none")
  }

  /**
   * Reads [1, 22, 2100] (or its transposed form) and keeps only the explicit exposed
   * classes: 2 BUTTOCKS_EXPOSED, 3 FEMALE_BREAST_EXPOSED, 4 FEMALE_GENITALIA_EXPOSED,
   * 6 ANUS_EXPOSED, 14 MALE_GENITALIA_EXPOSED. Covered classes and faces are ignored.
   */
  private fun readNudeDetail(tensor: OnnxTensor): NudeResult {
    val info = tensor.info
    if (info !is TensorInfo) return NudeResult(0f, "none")
    val shape = info.shape
    if (shape.size != 3) return NudeResult(0f, "none")

    val raw = tensor.floatBuffer
    raw.rewind()
    val values = FloatArray(raw.remaining())
    raw.get(values)
    if (values.isEmpty()) return NudeResult(0f, "none")

    val dim1 = shape[1].toInt()
    val dim2 = shape[2].toInt()
    val channelMajor = dim1 == YOLO_ROWS
    val anchorMajor = dim2 == YOLO_ROWS
    if (!channelMajor && !anchorMajor) return NudeResult(0f, "none")

    val anchors = if (channelMajor) dim2 else dim1
    fun valueAt(row: Int, anchor: Int): Float {
      val index = if (channelMajor) row * anchors + anchor else anchor * YOLO_ROWS + row
      return if (index in values.indices) values[index] else 0f
    }

    // Only icons, emoji and stickers are ignored (< 6% of the crop side). Real adult
    // photos and thumbnails are kept no matter how small they are, because the old 18% /
    // 4%-area filter was blinding NudeNet on Telegram media. Boxes are in input pixels for
    // the bundled export; a normalized export (all sides <= 1.5) is scaled up first.
    val size = AiCascadeConfig.STAGE2_INPUT_SIZE.toFloat()
    val minSide = size * AiCascadeConfig.MIN_NUDE_BOX_RATIO
    val bigEnough = BooleanArray(anchors) { anchor ->
      val width = abs(valueAt(2, anchor))
      val height = abs(valueAt(3, anchor))
      val scale = if (width <= 1.5f && height <= 1.5f) size else 1f
      val widthPx = width * scale
      val heightPx = height * scale
      widthPx >= minSide && heightPx >= minSide
    }

    var best = 0f
    var bestClass = -1
    for (classIndex in EXPOSED_CLASS_INDICES) {
      if (classIndex < 0 || classIndex >= YOLO_CLASS_COUNT) continue
      val classRow = YOLO_BOX_ROWS + classIndex
      for (anchor in 0 until anchors) {
        if (!bigEnough[anchor]) continue
        val confidence = valueAt(classRow, anchor)
        if (confidence > best) {
          best = confidence
          bestClass = classIndex
        }
      }
    }
    return NudeResult(best, NUDE_CLASS_NAMES.getOrElse(bestClass) { "none" })
  }

  // endregion

  // region block + helpers

  private fun triggerBlock(pkg: String, multiWindow: Boolean = false) {
    val service = activeService ?: return
    val context = service.applicationContext
    val domain = activeDomain
    // A browser block names the website (`• AI • TELEGRAM.ORG`), an app block the app.
    val label = appLabel ?: domain?.uppercase() ?: appLabelFor(context, pkg)
    // Stop before the overlay exists so the covered screen is never classified again.
    stop()
    HabitGuard.onAiMatch(
      context,
      "$pkg:ai-screenshot${domain?.let { ":$it" } ?: ""}",
      label,
      multiWindow
    )
  }

  private fun shouldScan(
    service: AccessibilityService,
    pkg: String,
    domain: String?
  ): Boolean {
    val context = service.applicationContext
    val state = HabitStore.state(context)
    if (!state.protectionEnabled) return false
    val ai = HabitStore.aiSettings(context)
    if (!ai.enabled) return false
    // A browser loop lives only as long as its website is still one of the watched ones.
    if (domain != null) {
      val wanted = AdultFilter.normalizeDomain(domain)
      return ai.monitoredDomains.any { AdultFilter.normalizeDomain(it) == wanted }
    }
    return pkg in ai.monitoredAppPackages
  }

  private fun isActive(pkg: String): Boolean = running && activePackage == pkg

  private fun isScreenInteractive(service: AccessibilityService): Boolean {
    val power = service.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return power.isInteractive
  }

  private fun canDrawOverlays(context: Context): Boolean = try {
    Settings.canDrawOverlays(context)
  } catch (error: Throwable) {
    false
  }

  @Suppress("DEPRECATION")
  private fun appLabelFor(context: Context, pkg: String): String = try {
    val info = context.packageManager.getApplicationInfo(pkg, 0)
    context.packageManager.getApplicationLabel(info).toString().ifBlank { pkg }
  } catch (error: Throwable) {
    pkg
  }

  private fun recordError(message: String) {
    lastError = message
    lastErrorAt = SystemClock.elapsedRealtime()
    Log.w(TAG, message)
  }

  private fun screenshotErrorName(code: Int): String = when (code) {
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error ($code)"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
      "Android denied screenshot access — turn Habit's accessibility service off and on ($code)"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "captured too often ($code)"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display ($code)"
    else -> "error code $code"
  }

  private fun describe(error: Throwable): String {
    val message = error.message?.trim()
    return if (message.isNullOrEmpty()) error.javaClass.simpleName else message
  }

  private fun format(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

  private fun ageOf(at: Long, now: Long): Long = if (at == 0L) -1L else now - at

  private fun recycle(bitmap: Bitmap?) {
    if (bitmap == null || bitmap.isRecycled) return
    try {
      bitmap.recycle()
    } catch (error: Throwable) {
      Log.w(TAG, "bitmap recycle failed: ${error.message}")
    }
  }

  // endregion
}
