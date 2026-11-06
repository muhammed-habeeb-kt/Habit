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
import kotlin.math.min

/**
 * On-device NSFW screenshot cascade.
 *
 * One frame per second while a watched app is in front, and only one frame in flight:
 *  - Stage 1 (TFLite MobileNetV2, 224x224) scores the top and bottom square of the screen.
 *    A low score exits immediately; a very high score blocks immediately.
 *  - Stage 2 (ONNX NudeNet 320n, 320x320) confirms the gray zone using only explicit
 *    exposed classes. A false alarm clears the frame.
 *  - Stage 3 is temporal: two consecutive borderline frames are needed before blocking.
 *
 * Nothing is written to disk unless debug recording is on: then every frame becomes a
 * downscaled JPEG plus one CSV row under `filesDir/ai_debug_session` (see [AiDebugSession])
 * so scores can be calibrated. Every bitmap and hardware buffer is released in a `finally`
 * block, and models are loaded lazily on the scanner thread.
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
   * The 18 NudeNet labels in model order, so the debug CSV names the winning class
   * instead of only its index. Covered classes and faces are listed but never scored.
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
  /** Tile names in the order the squares are cut, matching the CSV columns. */
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

  private val mainHandler = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "habit-ai-cascade").apply { isDaemon = true }
  }

  /** One capture + inference in flight; a tick that arrives meanwhile is dropped. */
  private val isProcessing = AtomicBoolean(false)
  private val unsureStrikeCount = AtomicInteger(0)

  @Volatile private var running = false
  @Volatile private var activePackage: String? = null
  @Volatile private var activeService: AccessibilityService? = null

  @Volatile private var lastCaptureAt = 0L

  // region diagnostics
  @Volatile private var appLabel: String? = null
  @Volatile private var frameCount = 0
  @Volatile private var blockCount = 0
  @Volatile private var lastStage1Score = 0f
  @Volatile private var lastStage2Score = 0f
  @Volatile private var lastVerdict = "idle"
  @Volatile private var lastFrameAt = 0L
  @Volatile private var dryRunActive = false
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

  private val tick = object : Runnable {
    override fun run() {
      if (!running) return
      captureFrame()
      if (running) mainHandler.postDelayed(this, AiCascadeConfig.CAPTURE_INTERVAL_MS)
    }
  }

  val isRunning: Boolean
    get() = running

  fun isRunningFor(pkg: String): Boolean = running && activePackage == pkg

  /**
   * Starts (or keeps) the loop for [packageName]. The caller has already checked that
   * protection is on, AI monitoring is enabled and the app is selected.
   */
  fun start(service: AccessibilityService, packageName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      recordError("Screenshots need Android 11 or newer")
      return
    }
    if (running && activePackage == packageName && activeService === service) return

    activeService = service
    activePackage = packageName
    appLabel = appLabelFor(service.applicationContext, packageName)
    running = true
    frameCount = 0
    unsureStrikeCount.set(0)
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
    unsureStrikeCount.set(0)
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
      "protectionEnabled" to protectionEnabled,
      "debugRecordingEnabled" to (ai?.debugRecordingEnabled ?: false),
      "debugDryRun" to (ai?.debugDryRun ?: false),
      "recordedFrames" to AiDebugSession.recordedFrames(context)
    )
  }

  /**
   * One-line status for the persistent notification:
   * `AI scanning Telegram • Frames: 12 • mob 0.14 • nude 0.02 (safe)` or the exact error.
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
    val scores = "mob ${format(lastStage1Score)} • nude ${format(lastStage2Score)}"
    val recorded = AiDebugSession.cachedFrameCount()
    val recording = if (recorded > 0) " • REC $recorded" else ""
    val dryRun = if (dryRunActive) " • DRY RUN" else ""
    return "AI scanning $label • Frames: $frameCount • $scores ($lastVerdict)$recording$dryRun"
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
      stage1Score = stage1Score(probe1)
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

    if (!shouldScan(service, pkg)) {
      stop()
      return
    }
    if (!isScreenInteractive(service)) return

    val now = SystemClock.elapsedRealtime()
    if (now - lastCaptureAt < AiCascadeConfig.CAPTURE_INTERVAL_MS) return
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
      if (!ensureModels(context)) {
        // Keep the loop alive: the model load is retried, and the notification shows why.
        lastVerdict = "model error"
        return
      }

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
    val frameStarted = SystemClock.elapsedRealtime()
    val service = activeService ?: return
    val context = service.applicationContext
    val ai = HabitStore.aiSettings(context)
    // Debug recording logs every frame; dry-run only suppresses the block screen *while*
    // recording, so a stale dry-run flag can never silently stop blocking on its own.
    val recording = ai.debugRecordingEnabled
    val dryRun = recording && ai.debugDryRun
    dryRunActive = dryRun

    // Three overlapping square tiles (top, centre, bottom) instead of one squashed tall
    // frame. A full-screen photo or a media viewer fills the centre, while a chat column
    // fills the middle band, so the centre tile is the one that actually catches content
    // inside an app like Telegram.
    val side = min(screen.width, screen.height / 2).coerceAtLeast(1)
    val x = (screen.width - side) / 2
    val offsets = intArrayOf(0, (screen.height - side) / 2, screen.height - side)
    val tiles = ArrayList<Bitmap>(offsets.size)

    try {
      for (offset in offsets) {
        tiles.add(Bitmap.createBitmap(screen, x, offset, side, side))
      }

      val stage1Started = SystemClock.elapsedRealtime()
      val probabilities = arrayOfNulls<FloatArray>(tiles.size)
      val scores = FloatArray(tiles.size)
      for (index in tiles.indices) {
        val output = stage1Probabilities(tiles[index])
        probabilities[index] = output
        scores[index] = combinedStage1Score(output)
      }
      val stage1Ms = SystemClock.elapsedRealtime() - stage1Started
      val strongest = scores.maxOrNull() ?: 0f
      val winnerIndex = scores.indices.maxByOrNull { scores[it] } ?: -1

      lastStage1Score = strongest
      lastStage2Score = 0f
      lastFrameAt = SystemClock.elapsedRealtime()
      frameCount += 1

      val stage2Started = SystemClock.elapsedRealtime()
      val nudity = arrayOfNulls<NudeResult>(tiles.size)
      if (recording) {
        // A calibration log needs both scores for every tile on every frame, even when
        // stage 1 already left the gray zone.
        for (index in tiles.indices) nudity[index] = stage2Detail(tiles[index])
      } else if (strongest >= thresholds.stage1SafeExit &&
        strongest < thresholds.stage1InstantBlock && winnerIndex >= 0
      ) {
        // Normal mode keeps stage 2 to the strongest suspicious tile: one extra inference
        // per frame keeps the 1 s cadence on mid-range phones.
        nudity[winnerIndex] = stage2Detail(tiles[winnerIndex])
      }
      val stage2Ms = SystemClock.elapsedRealtime() - stage2Started

      val winnerNude = if (winnerIndex >= 0) nudity[winnerIndex]?.score ?: 0f else 0f
      val scored = nudity.indices.filter { nudity[it] != null }
      val nudeTopIndex = scored.maxByOrNull { nudity[it]?.score ?: 0f }
      val nudeTop = nudeTopIndex?.let { nudity[it] }
      lastStage2Score = if (recording) nudeTop?.score ?: 0f else winnerNude

      val verdict = evaluate(strongest, winnerNude, thresholds)

      if (recording) {
        val winnerTile = TILE_NAMES.getOrElse(winnerIndex) { "center" }
        val winnerProbabilities =
          probabilities[winnerIndex.coerceAtLeast(0)] ?: FloatArray(STAGE1_CLASSES)
        val frameId = AiDebugSession.record(
          context,
          screen,
          AiDebugSession.FrameRecord(
            packageName = pkg,
            sensitivity = ai.aiSensitivity,
            mobMax = strongest,
            winningTile = winnerTile,
            mobTop = scores.getOrElse(0) { 0f },
            mobCenter = scores.getOrElse(1) { 0f },
            mobBottom = scores.getOrElse(2) { 0f },
            // The stage 1 model order is drawing, hentai, neutral, porn, sexy.
            porn = probability(winnerProbabilities, 3),
            hentai = probability(winnerProbabilities, 1),
            sexy = probability(winnerProbabilities, 4),
            neutral = probability(winnerProbabilities, 2),
            drawing = probability(winnerProbabilities, 0),
            nudeMax = nudeTop?.score ?: 0f,
            nudeTopClass = nudeTop?.className ?: "none",
            nudeTopTile = TILE_NAMES.getOrElse(nudeTopIndex ?: -1) { "none" },
            unsureStrikes = verdict.strikes,
            wouldBlock = verdict.wouldBlock,
            verdictReason = verdict.reason,
            stage1Ms = stage1Ms,
            stage2Ms = stage2Ms,
            totalMs = SystemClock.elapsedRealtime() - frameStarted
          )
        )
        if (frameId < 0) {
          recordError("debug frame could not be written to ${AiDebugSession.sessionDir(context)}.")
        }
      }

      lastVerdict = if (dryRun && verdict.wouldBlock) "would block (dry run)" else verdict.text
      if (verdict.wouldBlock && !dryRun) {
        blockCount += 1
        triggerBlock(pkg)
      }
    } finally {
      for (tile in tiles) recycle(tile)
    }
  }

  /** Outcome of the cascade for one frame, with the reason kept for the debug CSV. */
  private data class Verdict(
    val wouldBlock: Boolean,
    /** Unsure-frame strike count after this frame, 0 unless the gray zone was reached. */
    val strikes: Int,
    val reason: String,
    /** Short state shown in the notification and the status card. */
    val text: String
  )

  /**
   * The cascade rules, unchanged by debug recording: stage 1 leaves early below the safe
   * exit or blocks instantly above the instant threshold, stage 2 confirms or clears the
   * gray zone and two consecutive unsure frames block.
   */
  private fun evaluate(
    strongest: Float,
    nude: Float,
    thresholds: AiCascadeConfig.Thresholds
  ): Verdict {
    return when {
      strongest < thresholds.stage1SafeExit -> {
        unsureStrikeCount.set(0)
        Verdict(false, 0, "stage1 ${format(strongest)} below safe exit", "safe")
      }
      strongest >= thresholds.stage1InstantBlock -> {
        unsureStrikeCount.set(0)
        Verdict(
          true,
          0,
          "stage1 ${format(strongest)} instant block",
          "blocked (stage 1 ${format(strongest)})"
        )
      }
      nude >= thresholds.stage2ConfirmBlock -> {
        unsureStrikeCount.set(0)
        Verdict(
          true,
          0,
          "nudenet ${format(nude)} confirm",
          "blocked (nudenet ${format(nude)})"
        )
      }
      nude < thresholds.stage2FalseAlarm -> {
        unsureStrikeCount.set(0)
        Verdict(false, 0, "nudenet ${format(nude)} false alarm", "safe")
      }
      else -> {
        val strikes = unsureStrikeCount.incrementAndGet()
        if (strikes >= thresholds.strikesRequired) {
          unsureStrikeCount.set(0)
          Verdict(
            true,
            strikes,
            "temporal $strikes unsure frames",
            "blocked ($strikes unsure frames)"
          )
        } else {
          Verdict(
            false,
            strikes,
            "unsure $strikes/${thresholds.strikesRequired}",
            "unsure ($strikes/${thresholds.strikesRequired})"
          )
        }
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

      val interpreter =
        Interpreter(loadTflite(context), Interpreter.Options().setNumThreads(STAGE1_THREADS))
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
        " -> " + output.shape().joinToString("x", "[", "]")
    } catch (error: Throwable) {
      AiCascadeConfig.STAGE1_ASSET
    }
  }

  /** Stage 1 combined score: Porn + Hentai + 0.3 * Sexy. */
  private fun stage1Score(crop: Bitmap): Float = combinedStage1Score(stage1Probabilities(crop))

  /**
   * Stage 1 raw output: RGB / 255, NHWC [1, 224, 224, 3] -> one probability per class, in
   * the model order drawing, hentai, neutral, porn, sexy. The debug CSV needs the whole
   * vector, so the combined score is computed separately by [combinedStage1Score].
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

  private fun combinedStage1Score(probabilities: FloatArray): Float = when {
    probabilities.size >= STAGE1_CLASSES ->
      probabilities[3] + probabilities[1] + 0.3f * probabilities[4]
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

  /** Stage 2 including the class that produced the score, for the debug CSV. */
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
    var best = 0f
    var bestClass = -1
    for (classIndex in EXPOSED_CLASS_INDICES) {
      if (classIndex < 0 || classIndex >= YOLO_CLASS_COUNT) continue
      val classRow = YOLO_BOX_ROWS + classIndex
      for (anchor in 0 until anchors) {
        val index = if (channelMajor) classRow * anchors + anchor else anchor * YOLO_ROWS + classRow
        if (index in values.indices) {
          val confidence = values[index]
          if (confidence > best) {
            best = confidence
            bestClass = classIndex
          }
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
    val label = appLabel ?: appLabelFor(context, pkg)
    // Stop before the overlay exists so the covered screen is never classified again.
    stop()
    HabitGuard.onAiMatch(context, "$pkg:ai-screenshot", label)
  }

  private fun shouldScan(service: AccessibilityService, pkg: String): Boolean {
    val context = service.applicationContext
    val state = HabitStore.state(context)
    if (!state.protectionEnabled) return false
    val ai = HabitStore.aiSettings(context)
    return ai.enabled && pkg in ai.monitoredAppPackages
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
