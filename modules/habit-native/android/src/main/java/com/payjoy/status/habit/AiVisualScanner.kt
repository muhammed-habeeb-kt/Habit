package com.payjoy.status.habit

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
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
 * One frame per second while a watched app is in front (2.2 s after a run of safe frames),
 * and only one frame in flight:
 *  - Stage 1 (TFLite MobileNetV2, 224x224, XNNPACK + fp16) scores three squares of the
 *    screen. A confident score blocks immediately; NudeNet can never veto it.
 *  - Stage 2 (ONNX NudeNet 320n, 320x320) is additive only: it runs on the tiles stage 1
 *    found suspicious and catches small thumbnails stage 1 missed.
 *  - Stage 3 is temporal: two consecutive borderline frames are needed before blocking.
 *  - Stage 0 is free: a 16x16 pixel-diff gate skips both models when the screen is static,
 *    and a run of safe frames relaxes the capture interval.
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
  private const val STATIC_GRID_PIXELS =
    AiCascadeConfig.STATIC_DIFF_GRID * AiCascadeConfig.STATIC_DIFF_GRID

  private val mainHandler = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "habit-ai-cascade").apply { isDaemon = true }
  }

  /** One capture + inference in flight; a tick that arrives meanwhile is dropped. */
  private val isProcessing = AtomicBoolean(false)
  private val unsureStrikeCount = AtomicInteger(0)
  /** Consecutive would-block frames; production needs two before the overlay appears. */
  private val blockConfirmCount = AtomicInteger(0)
  /** Frames the static-screen gate skipped; diagnostics only, they cost no inference. */
  @Volatile private var skippedFrames = 0

  @Volatile private var running = false
  @Volatile private var activePackage: String? = null
  /** Monitored website that armed this loop inside a browser, or null for a watched app. */
  @Volatile private var activeDomain: String? = null
  @Volatile private var activeService: AccessibilityService? = null

  @Volatile private var lastCaptureAt = 0L
  /** Current tick delay: 1 s on interaction, 2.2 s while a safe scene stays unchanged. */
  @Volatile private var captureIntervalMs = AiCascadeConfig.CAPTURE_INTERVAL_MS
  /** Consecutive safe frames; at [AiCascadeConfig.RELAXED_AFTER_SAFE_FRAMES] the loop relaxes. */
  @Volatile private var safeStreak = 0

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

  /** 16x16 RGB grid of the current frame and of the last completely safe frame. */
  private val gridPixels = IntArray(STATIC_GRID_PIXELS)
  private val lastSafeGrid = IntArray(STATIC_GRID_PIXELS)
  private var hasSafeGrid = false

  private var stage1: Interpreter? = null
  private var stage1Input: ByteBuffer? = null
  private var ortEnv: OrtEnvironment? = null
  private var stage2: OrtSession? = null
  private var stage2Input: ByteBuffer? = null

  @Volatile private var modelsReady = false
  @Volatile private var modelsFailedAt = 0L

  private val tick = object : Runnable {
    override fun run() {
      if (!running) return
      captureFrame()
      if (running) mainHandler.postDelayed(this, captureIntervalMs)
    }
  }

  val isRunning: Boolean
    get() = running

  fun isRunningFor(pkg: String, domain: String? = null): Boolean =
    running && activePackage == pkg && activeDomain == domain

  /** True while the loop belongs to [pkg], whatever app or website armed it. */
  fun isRunningForPackage(pkg: String): Boolean = running && activePackage == pkg

  /**
   * Starts (or keeps) the loop for [packageName]. The caller has already checked that
   * protection is on, AI monitoring is enabled and either the app is selected or
   * [domain] is one of the watched websites open in that browser.
   */
  fun start(service: AccessibilityService, packageName: String, domain: String? = null) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      recordError("Screenshots need Android 11 or newer")
      return
    }
    if (running && activePackage == packageName && activeService === service &&
      activeDomain == domain
    ) {
      return
    }

    activeService = service
    activePackage = packageName
    activeDomain = domain
    // A website block names the site (`• AI • TELEGRAM.ORG`); an app block names the app.
    appLabel = domain?.uppercase() ?: appLabelFor(service.applicationContext, packageName)
    running = true
    frameCount = 0
    skippedFrames = 0
    unsureStrikeCount.set(0)
    blockConfirmCount.set(0)
    safeStreak = 0
    captureIntervalMs = AiCascadeConfig.CAPTURE_INTERVAL_MS
    hasSafeGrid = false
    lastVerdict = "starting"
    mainHandler.removeCallbacks(tick)
    mainHandler.post(tick)
  }

  /** Stops the loop and drops the borderline-frame memory. Safe from any thread. */
  fun stop() {
    running = false
    mainHandler.removeCallbacks(tick)
    activeService = null
    activePackage = null
    activeDomain = null
    unsureStrikeCount.set(0)
    blockConfirmCount.set(0)
    safeStreak = 0
    captureIntervalMs = AiCascadeConfig.CAPTURE_INTERVAL_MS
    // The next app starts on a new screen, so its first frame is always analyzed.
    hasSafeGrid = false
    if (!lastVerdict.startsWith("blocked") && lastVerdict != "idle") lastVerdict = "stopped"
  }

  /**
   * Called for a real interaction (scroll, typed text, window change) from the
   * accessibility service. Never let the battery saver outlive the moment the user moved
   * on: a relaxed loop is snapped back to the fast interval immediately instead of after
   * the current 2.2 s sleep.
   */
  fun noteUserActivity() {
    val wasRelaxed = captureIntervalMs != AiCascadeConfig.CAPTURE_INTERVAL_MS
    safeStreak = 0
    captureIntervalMs = AiCascadeConfig.CAPTURE_INTERVAL_MS
    if (!running || !wasRelaxed) return
    mainHandler.removeCallbacks(tick)
    mainHandler.post(tick)
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
      "skippedFrames" to skippedFrames,
      "captureIntervalMs" to captureIntervalMs,
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
   * Clean status line for the persistent notification: the watched app or site, or the exact
   * error. Production never shows per-frame model scores in the notification.
   */
  fun summary(): String? {
    val label = appLabel ?: activePackage ?: "app"
    val error = lastError
    if (error != null && ageOf(lastErrorAt, SystemClock.elapsedRealtime()) < 15_000) {
      return "AI error: $error"
    }
    if (!running) {
      return error?.let { "AI idle — last error: $it" }
    }
    return "AI scanning $label"
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

  private fun captureFrame() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val service = activeService ?: run {
      stop()
      return
    }
    val pkg = activePackage ?: run {
      stop()
      return
    }

    if (!shouldScan(service, pkg, activeDomain)) {
      stop()
      return
    }
    if (!isScreenInteractive(service)) return

    val now = SystemClock.elapsedRealtime()
    if (now - lastCaptureAt < captureIntervalMs) return
    if (!isProcessing.compareAndSet(false, true)) return
    lastCaptureAt = now

    try {
      service.takeScreenshot(
        Display.DEFAULT_DISPLAY,
        worker,
        object : AccessibilityService.TakeScreenshotCallback {
          override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            handleScreenshot(pkg, screenshot)
          }

          override fun onFailure(errorCode: Int) {
            isProcessing.set(false)
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
      isProcessing.set(false)
      recordError("takeScreenshot threw: ${describe(error)}")
    }
  }

  private fun handleScreenshot(pkg: String, screenshot: AccessibilityService.ScreenshotResult) {
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

    // Stage 0, free: a 16x16 grid tells whether the screen still shows the same safe
    // scene, and then neither model runs at all.
    val grid = sampleGrid(screen)
    val staticSkip = hasSafeGrid &&
      unsureStrikeCount.get() == 0 &&
      // A frame waiting for its confirmation must be looked at again, never skipped.
      blockConfirmCount.get() == 0 &&
      lastStage1Score < AiCascadeConfig.STATIC_SKIP_MAX_SCORE &&
      meanGridDiff(grid, lastSafeGrid) < AiCascadeConfig.STATIC_DIFF_THRESHOLD
    if (staticSkip) {
      skippedFrames += 1
      lastFrameAt = SystemClock.elapsedRealtime()
      lastVerdict = "skipped (unchanged screen)"
      return
    }

    // Models load only after the free gate, so a static screen never pays for them.
    if (!ensureModels(context)) {
      // Keep the loop alive: the model load is retried, and the notification shows why.
      lastVerdict = "model error"
      return
    }

    // Only the central media viewport is classified. The top band (status bar + app header
    // with its circular profile avatar) and the left gutter (Telegram/Discord chat-list and
    // message avatars) are deliberately excluded, so scrolling a chat list can never feed
    // an avatar to a model. Inside that viewport three overlapping squares cover the top,
    // middle and bottom of where a video or photo actually plays.
    val mediaLeft = (screen.width * AiCascadeConfig.MEDIA_LEFT_RATIO).toInt()
    val mediaTop = (screen.height * AiCascadeConfig.MEDIA_TOP_RATIO).toInt()
    val mediaRight = (screen.width * AiCascadeConfig.MEDIA_RIGHT_RATIO).toInt()
    val mediaBottom = (screen.height * AiCascadeConfig.MEDIA_BOTTOM_RATIO).toInt()
    val mediaWidth = (mediaRight - mediaLeft).coerceAtLeast(1)
    val mediaHeight = (mediaBottom - mediaTop).coerceAtLeast(1)
    val side = min(mediaWidth, mediaHeight).coerceAtLeast(1)
    val x = mediaLeft + (mediaWidth - side) / 2
    val yStart = mediaTop
    val yEnd = mediaBottom - side
    val offsets = intArrayOf(yStart, (yStart + yEnd) / 2, yEnd)
    val tiles = ArrayList<Bitmap>(offsets.size)

    try {
      for (offset in offsets) {
        tiles.add(Bitmap.createBitmap(screen, x, offset, side, side))
      }

      val probabilities = arrayOfNulls<FloatArray>(tiles.size)
      val scores = FloatArray(tiles.size)
      for (index in tiles.indices) {
        val output = stage1Probabilities(tiles[index])
        probabilities[index] = output
        scores[index] = combinedStage1Score(output, thresholds.sexyWeight)
      }
      val strongest = scores.maxOrNull() ?: 0f

      lastStage1Score = strongest
      lastStage2Score = 0f
      lastFrameAt = SystemClock.elapsedRealtime()
      frameCount += 1

      // Stage 1 blocks on its own: glamour, bikini, cleavage and adult frames never wait
      // for NudeNet, so nothing can turn a real hit into a "false alarm" any more.
      val stage1BlockIndex = scores.indices.firstOrNull { index ->
        val output = probabilities[index] ?: return@firstOrNull false
        val explicit = probability(output, 3) + probability(output, 1)
        scores[index] >= thresholds.stage1Block ||
          explicit >= thresholds.stage1BlockExplicit ||
          probability(output, 4) >= thresholds.stage1BlockSexy
      }
      val stage1BlockReason = stage1BlockIndex?.let { index ->
        "stage1 ${format(scores[index])} on ${TILE_NAMES.getOrElse(index) { "center" }}" +
          " instant block"
      }

      // Stage 2 is additive only: it runs on the tiles stage 1 called suspicious, and it
      // never runs at all once stage 1 already blocked the frame.
      val gated = if (stage1BlockIndex != null) {
        emptyList()
      } else {
        scores.indices.filter { index ->
          val output = probabilities[index] ?: return@filter false
          scores[index] >= AiCascadeConfig.STAGE2_GATE_SCORE ||
            probability(output, 3) >= AiCascadeConfig.STAGE2_GATE_PORN ||
            probability(output, 4) >= AiCascadeConfig.STAGE2_GATE_SEXY
        }
      }

      val nudity = arrayOfNulls<NudeResult>(tiles.size)
      for (index in gated) nudity[index] = stage2Detail(tiles[index])

      val scored = nudity.indices.filter { nudity[it] != null }
      val nudeTopIndex = scored.maxByOrNull { nudity[it]?.score ?: 0f }
      val nudeTop = nudeTopIndex?.let { nudity[it] }
      // Only a gated tile may block, so a tile stage 1 read as pure text can never start a
      // block through NudeNet.
      val verdictNude = if (gated.isEmpty()) 0f else gated.maxOf { nudity[it]?.score ?: 0f }
      lastStage2Score = nudeTop?.score ?: verdictNude

      val verdict =
        evaluate(strongest, stage1BlockReason, gated.isNotEmpty(), verdictNude, thresholds)

      // Only a genuinely safe frame may become the reference for the free pixel gate.
      if (verdict.strikes == 0 && strongest < AiCascadeConfig.STATIC_SKIP_MAX_SCORE) {
        System.arraycopy(grid, 0, lastSafeGrid, 0, grid.size)
        hasSafeGrid = true
      }
      adaptCaptureInterval(strongest, lastStage2Score)

      lastVerdict = verdict.text
      if (verdict.wouldBlock) {
        // Production blocking: the same verdict must survive two consecutive frames, so a
        // brief scroll past a small preview never closes the app. A playing video or an
        // opened media post keeps the hit on screen and blocks on the second frame.
        val confirmations = blockConfirmCount.incrementAndGet()
        if (confirmations >= AiCascadeConfig.BLOCK_CONFIRM_FRAMES) {
          blockConfirmCount.set(0)
          blockCount += 1
          triggerBlock(pkg)
        } else {
          // Keep the next frame fast instead of relaxing into the 2.2 s movie interval.
          captureIntervalMs = AiCascadeConfig.CAPTURE_INTERVAL_MS
          safeStreak = 0
          lastVerdict = "confirming $confirmations/${AiCascadeConfig.BLOCK_CONFIRM_FRAMES} " +
            "(${verdict.text})"
        }
      } else {
        blockConfirmCount.set(0)
      }
    } finally {
      for (tile in tiles) recycle(tile)
    }
  }

  /** Outcome of the cascade for one frame, with the human-readable reason kept. */
  private data class Verdict(
    val wouldBlock: Boolean,
    /** Unsure-frame strike count after this frame, 0 unless the gray zone was reached. */
    val strikes: Int,
    val reason: String,
    /** Short state shown in the notification and the status card. */
    val text: String
  )

  /**
   * The cascade rules:
   *  - a stage 1 direct block wins and clears the strikes,
   *  - a frame whose tiles never crossed the stage 2 gate is safe,
   *  - NudeNet at [AiCascadeConfig.Thresholds.nudeBlock] blocks immediately,
   *  - a borderline frame (NudeNet or stage 1) counts as a strike; two in a row block,
   *  - anything else clears the strikes. NudeNet can never clear a stage 1 block.
   */
  private fun evaluate(
    strongest: Float,
    stage1BlockReason: String?,
    gated: Boolean,
    nude: Float,
    thresholds: AiCascadeConfig.Thresholds
  ): Verdict {
    return when {
      stage1BlockReason != null -> {
        unsureStrikeCount.set(0)
        Verdict(true, 0, stage1BlockReason, "blocked (stage 1 ${format(strongest)})")
      }
      !gated -> {
        unsureStrikeCount.set(0)
        Verdict(false, 0, "stage1 ${format(strongest)} below stage 2 gate", "safe")
      }
      nude >= thresholds.nudeBlock -> {
        unsureStrikeCount.set(0)
        Verdict(
          true,
          0,
          "nudenet ${format(nude)} instant block",
          "blocked (nudenet ${format(nude)})"
        )
      }
      nude >= thresholds.nudeTemporal || strongest >= thresholds.stage1Temporal -> {
        val strikes = unsureStrikeCount.incrementAndGet()
        val detail = "nudenet ${format(nude)}, stage1 ${format(strongest)}"
        if (strikes >= thresholds.strikesRequired) {
          unsureStrikeCount.set(0)
          Verdict(
            true,
            strikes,
            "temporal $strikes unsure frames ($detail)",
            "blocked ($strikes unsure frames)"
          )
        } else {
          Verdict(
            false,
            strikes,
            "unsure $strikes/${thresholds.strikesRequired} ($detail)",
            "unsure ($strikes/${thresholds.strikesRequired})"
          )
        }
      }
      else -> {
        unsureStrikeCount.set(0)
        Verdict(
          false,
          0,
          "nudenet ${format(nude)} under ${format(thresholds.nudeTemporal)}",
          "safe"
        )
      }
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
    combinedStage1Score(stage1Probabilities(crop), sexyWeight)

  /**
   * Stage 1 raw output: RGB / 255, NHWC [1, 224, 224, 3] -> one probability per class, in
   * the model order drawing, hentai, neutral, porn, sexy. The combined score is computed
   * separately by [combinedStage1Score], which needs the per-class probabilities.
   */
  private fun stage1Probabilities(crop: Bitmap): FloatArray {
    val interpreter = stage1 ?: error("stage 1 model is not loaded")
    val buffer = stage1Input ?: error("stage 1 buffer is missing")
    val size = AiCascadeConfig.STAGE1_INPUT_SIZE
    val resized = Bitmap.createScaledBitmap(crop, size, size, true)

    try {
      resized.getPixels(stage1Pixels, 0, size, 0, 0, size, size)

      buffer.rewind()
      val floats = buffer.asFloatBuffer()
      floats.clear()
      for (pixel in stage1Pixels) {
        floats.put(((pixel shr 16) and 0xFF) / 255f)
        floats.put(((pixel shr 8) and 0xFF) / 255f)
        floats.put((pixel and 0xFF) / 255f)
      }
      floats.rewind()
      buffer.rewind()

      val outputShape = interpreter.getOutputTensor(0).shape()
      val classes = outputShape.lastOrNull()?.coerceAtLeast(1) ?: STAGE1_CLASSES
      val output = Array(1) { FloatArray(classes) }
      interpreter.run(buffer, output)
      return output[0]
    } finally {
      if (resized !== crop) recycle(resized)
    }
  }

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

    // Ignore tiny detections entirely: a circular avatar, a sticker, an emoji or a small
    // thumbnail is never "substantial media". Boxes are in input pixels for the bundled
    // export; a normalized export (all sides <= 1.5) is scaled up first.
    val size = AiCascadeConfig.STAGE2_INPUT_SIZE.toFloat()
    val minSide = size * AiCascadeConfig.MIN_NUDE_BOX_RATIO
    val minArea = size * size * AiCascadeConfig.MIN_NUDE_BOX_AREA_RATIO
    val bigEnough = BooleanArray(anchors) { anchor ->
      val width = abs(valueAt(2, anchor))
      val height = abs(valueAt(3, anchor))
      val scale = if (width <= 1.5f && height <= 1.5f) size else 1f
      val widthPx = width * scale
      val heightPx = height * scale
      widthPx >= minSide && heightPx >= minSide && widthPx * heightPx >= minArea
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

  private fun triggerBlock(pkg: String) {
    val service = activeService ?: return
    val context = service.applicationContext
    val domain = activeDomain
    // A browser block names the website (`• AI • TELEGRAM.ORG`), an app block the app.
    val label = appLabel ?: domain?.uppercase() ?: appLabelFor(context, pkg)
    // Stop before the overlay exists so the covered screen is never classified again.
    stop()
    HabitGuard.onAiMatch(context, "$pkg:ai-screenshot${domain?.let { ":$it" } ?: ""}", label)
  }

  /**
   * 16x16 RGB sample of the whole screen: 256 `getPixel` reads cost well under a millisecond,
   * which is nothing compared to a stage 1 inference, and they are enough to tell "same
   * screen" from "something moved".
   */
  private fun sampleGrid(screen: Bitmap): IntArray {
    val step = AiCascadeConfig.STATIC_DIFF_GRID
    val maxX = (screen.width - 1).coerceAtLeast(0)
    val maxY = (screen.height - 1).coerceAtLeast(0)
    for (gy in 0 until step) {
      val y = ((gy + 0.5f) * screen.height / step).toInt().coerceIn(0, maxY)
      for (gx in 0 until step) {
        val x = ((gx + 0.5f) * screen.width / step).toInt().coerceIn(0, maxX)
        gridPixels[gy * step + gx] = screen.getPixel(x, y)
      }
    }
    return gridPixels
  }

  /** Mean absolute per-channel difference of two grids, on the 0..255 scale. */
  private fun meanGridDiff(current: IntArray, previous: IntArray): Float {
    if (current.size != previous.size || current.isEmpty()) return Float.MAX_VALUE
    var sum = 0L
    for (index in current.indices) {
      val a = current[index]
      val b = previous[index]
      sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
      sum += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
      sum += abs((a and 0xFF) - (b and 0xFF)).toLong()
    }
    return sum.toFloat() / (current.size * 3)
  }

  /**
   * Battery saver for long stretches of normal video: after
   * [AiCascadeConfig.RELAXED_AFTER_SAFE_FRAMES] safe frames the tick relaxes to
   * [AiCascadeConfig.RELAXED_CAPTURE_INTERVAL_MS], and any score at or above
   * [AiCascadeConfig.ADAPTIVE_FAST_SCORE] snaps it straight back to one second.
   */
  private fun adaptCaptureInterval(strongest: Float, nude: Float) {
    val signal = strongest >= AiCascadeConfig.ADAPTIVE_FAST_SCORE
    val safe = strongest < AiCascadeConfig.ADAPTIVE_SAFE_SCORE &&
      nude < AiCascadeConfig.ADAPTIVE_SAFE_NUDE
    if (signal || !safe) {
      safeStreak = 0
      captureIntervalMs = AiCascadeConfig.CAPTURE_INTERVAL_MS
      return
    }
    safeStreak += 1
    if (safeStreak >= AiCascadeConfig.RELAXED_AFTER_SAFE_FRAMES) {
      captureIntervalMs = AiCascadeConfig.RELAXED_CAPTURE_INTERVAL_MS
    }
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
