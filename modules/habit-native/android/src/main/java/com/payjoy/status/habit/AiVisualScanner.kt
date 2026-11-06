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
import android.util.Log
import android.view.Display
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
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
 * Nothing is written to disk, no frame is logged, and every bitmap and hardware buffer is
 * released in a `finally` block. Models are loaded lazily on the scanner thread.
 */
object AiVisualScanner {
  private const val TAG = "AiVisualScanner"

  private const val STAGE1_THREADS = 4
  private const val MAX_CONSECUTIVE_FAILURES = 3
  /** NSFW MobileNetV2 output: drawings, hentai, neutral, porn, sexy. */
  private const val STAGE1_CLASSES = 5

  /** NudeNet class indices that matter: explicit exposed body parts only. */
  private val EXPOSED_CLASS_INDICES = intArrayOf(2, 3, 4, 6, 14)
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
  @Volatile private var consecutiveFailures = 0

  @Volatile private var lastCaptureAt = 0L

  /** Reused on the single scanner thread so a frame allocates no pixel arrays. */
  private val stage1Pixels = IntArray(STAGE1_PIXELS)
  private val stage2Pixels = IntArray(STAGE2_PIXELS)

  private var stage1: Interpreter? = null
  private var stage1Input: ByteBuffer? = null
  private var ortEnv: OrtEnvironment? = null
  private var stage2: OrtSession? = null
  private var stage2Input: ByteBuffer? = null

  @Volatile private var modelsReady = false
  @Volatile private var modelsFailed = false

  private val tick = object : Runnable {
    override fun run() {
      if (!running) return
      captureFrame()
      if (running) mainHandler.postDelayed(this, AiCascadeConfig.CAPTURE_INTERVAL_MS)
    }
  }

  /**
   * Starts (or keeps) the loop for [packageName]. The caller has already checked that
   * protection is on, AI monitoring is enabled and the app is selected.
   */
  fun start(service: AccessibilityService, packageName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    if (running && activePackage == packageName && activeService === service) return

    activeService = service
    activePackage = packageName
    running = true
    unsureStrikeCount.set(0)
    modelsFailed = false
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
  }

  /** Called the moment the block screen appears: no point classifying a covered screen. */
  fun onOverlayShown() = stop()

  /** Closes both interpreters once the accessibility connection goes away. */
  fun release() {
    stop()
    // Flip the flag before the close task runs so no later frame reuses a closed model.
    modelsReady = false
    modelsFailed = false
    worker.execute {
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
      modelsFailed = false
    }
  }

  // region capture loop

  private fun captureFrame() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val service = activeService ?: return
    val pkg = activePackage ?: return

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
            Log.w(TAG, "screenshot failed: $errorCode")
          }
        }
      )
    } catch (error: Throwable) {
      isProcessing.set(false)
      Log.w(TAG, "takeScreenshot threw: ${error.message}")
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
        stop()
        return
      }

      // HARDWARE bitmaps cannot be read pixel by pixel, so copy once to ARGB_8888.
      val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        ?: return
      hardware = hardwareBitmap
      val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
      screen = softwareBitmap

      analyze(pkg, softwareBitmap, AiCascadeConfig.thresholds(context))
      consecutiveFailures = 0
    } catch (error: Throwable) {
      consecutiveFailures += 1
      Log.w(TAG, "frame failed: ${error.message}")
      if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) stop()
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
    // Two square tiles instead of one squashed tall frame: upper half and lower half.
    val side = min(screen.width, screen.height / 2).coerceAtLeast(1)
    val x = (screen.width - side) / 2
    val top = Bitmap.createBitmap(screen, x, 0, side, side)
    val bottom = Bitmap.createBitmap(screen, x, screen.height - side, side, side)

    try {
      val topScore = stage1Score(top)
      val bottomScore = stage1Score(bottom)
      val strongest = max(topScore, bottomScore)

      if (strongest < thresholds.stage1SafeExit) {
        unsureStrikeCount.set(0)
        return
      }
      if (strongest >= thresholds.stage1InstantBlock) {
        unsureStrikeCount.set(0)
        triggerBlock(pkg)
        return
      }

      var nude = 0f
      if (topScore >= thresholds.stage1SafeExit) {
        nude = max(nude, stage2Score(top))
      }
      if (bottomScore >= thresholds.stage1SafeExit) {
        nude = max(nude, stage2Score(bottom))
      }

      when {
        nude >= thresholds.stage2ConfirmBlock -> {
          unsureStrikeCount.set(0)
          triggerBlock(pkg)
        }
        nude < thresholds.stage2FalseAlarm -> unsureStrikeCount.set(0)
        else -> {
          if (unsureStrikeCount.incrementAndGet() >= thresholds.strikesRequired) {
            unsureStrikeCount.set(0)
            triggerBlock(pkg)
          }
        }
      }
    } finally {
      recycle(top)
      recycle(bottom)
    }
  }

  // endregion

  // region models

  private fun ensureModels(context: Context): Boolean {
    if (modelsReady) return true
    if (modelsFailed) return false

    return try {
      val interpreter = Interpreter(
        loadAsset(context, AiCascadeConfig.STAGE1_ASSET),
        Interpreter.Options().setNumThreads(STAGE1_THREADS)
      )
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

      stage1 = interpreter
      ortEnv = env
      stage2 = session
      stage1Input = ByteBuffer.allocateDirect(STAGE1_BYTES).order(ByteOrder.nativeOrder())
      stage2Input = ByteBuffer.allocateDirect(STAGE2_BYTES).order(ByteOrder.nativeOrder())
      modelsReady = true
      true
    } catch (error: Throwable) {
      modelsFailed = true
      Log.w(TAG, "models unavailable: ${error.message}")
      false
    }
  }

  private fun loadAsset(context: Context, name: String): MappedByteBuffer {
    // Assets are stored uncompressed (noCompress in build.gradle), so TFLite can map them.
    // The descriptor stays tied to the mapping for the life of the loaded model, which is
    // why this follows the official LiteRT sample and does not close it here.
    val descriptor = context.assets.openFd(name)
    val stream = FileInputStream(descriptor.fileDescriptor)
    return stream.channel.map(
      FileChannel.MapMode.READ_ONLY,
      descriptor.startOffset,
      descriptor.declaredLength
    )
  }

  /** Stage 1: RGB / 255, NHWC [1, 224, 224, 3] -> Porn + Hentai + 0.3 * Sexy. */
  private fun stage1Score(crop: Bitmap): Float {
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

      val output = Array(1) { FloatArray(STAGE1_CLASSES) }
      interpreter.run(buffer, output)
      val probabilities = output[0]
      return probabilities[3] + probabilities[1] + 0.3f * probabilities[4]
    } finally {
      if (resized !== crop) recycle(resized)
    }
  }

  /** Stage 2: NCHW [1, 3, 320, 320] -> highest confidence of the explicit exposed classes. */
  private fun stage2Score(crop: Bitmap): Float {
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
          if (value is OnnxTensor) return readNudeScore(value)
        }
      }
    } finally {
      if (resized !== crop) recycle(resized)
    }
    return 0f
  }

  /**
   * Reads [1, 22, 2100] (or its transposed form) and keeps only the explicit exposed
   * classes: 2 BUTTOCKS_EXPOSED, 3 FEMALE_BREAST_EXPOSED, 4 FEMALE_GENITALIA_EXPOSED,
   * 6 ANUS_EXPOSED, 14 MALE_GENITALIA_EXPOSED. Covered classes and faces are ignored.
   */
  private fun readNudeScore(tensor: OnnxTensor): Float {
    val info = tensor.info
    if (info !is TensorInfo) return 0f
    val shape = info.shape
    if (shape.size != 3) return 0f

    val raw = tensor.floatBuffer
    raw.rewind()
    val values = FloatArray(raw.remaining())
    raw.get(values)
    if (values.isEmpty()) return 0f

    val dim1 = shape[1].toInt()
    val dim2 = shape[2].toInt()
    val channelMajor = dim1 == YOLO_ROWS
    val anchorMajor = dim2 == YOLO_ROWS
    if (!channelMajor && !anchorMajor) return 0f

    val anchors = if (channelMajor) dim2 else dim1
    var best = 0f
    for (classIndex in EXPOSED_CLASS_INDICES) {
      if (classIndex < 0 || classIndex >= YOLO_CLASS_COUNT) continue
      val classRow = YOLO_BOX_ROWS + classIndex
      for (anchor in 0 until anchors) {
        val index = if (channelMajor) classRow * anchors + anchor else anchor * YOLO_ROWS + classRow
        if (index in values.indices) {
          val confidence = values[index]
          if (confidence > best) best = confidence
        }
      }
    }
    return best
  }

  // endregion

  // region block + helpers

  private fun triggerBlock(pkg: String) {
    val service = activeService ?: return
    val context = service.applicationContext
    val label = appLabel(context, pkg)
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

  @Suppress("DEPRECATION")
  private fun appLabel(context: Context, pkg: String): String = try {
    val info = context.packageManager.getApplicationInfo(pkg, 0)
    context.packageManager.getApplicationLabel(info).toString().ifBlank { pkg }
  } catch (error: Throwable) {
    pkg
  }

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
