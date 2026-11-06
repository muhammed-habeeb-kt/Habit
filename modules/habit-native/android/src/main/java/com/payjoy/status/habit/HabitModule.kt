package com.payjoy.status.habit

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.math.hypot
import java.util.concurrent.TimeUnit
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * The only door between JS and the native guard. JS can read a snapshot of state and
 * request actions; it can never write protection state directly.
 */
class HabitModule : Module() {
  private val appCtx: Context
    get() = requireNotNull(appContext.reactContext) { "React context is unavailable" }

  /** One reused offline detector; closed in OnDestroy. */
  @Volatile private var mlkitDetector: FaceDetector? = null

  override fun definition() = ModuleDefinition {
    Name("HabitNative")
    Events("onAttemptsChanged", "onStateChanged")

    OnCreate {
      val ctx = appContext.reactContext ?: return@OnCreate
      try {
        HabitStore.init(ctx)
        HabitStore.ensureFilter(ctx)
        HabitUnlock.tick(ctx)
        HabitStore.onAttemptsChanged = { count ->
          sendEvent("onAttemptsChanged", mapOf("count" to count))
        }
        HabitStore.onStateChanged = {
          sendEvent("onStateChanged", emptyMap<String, Any?>())
        }
        val unlock = HabitStore.unlockState(ctx)
        if (HabitStore.state(ctx).protectionEnabled || unlock.cooldown != null || unlock.offWindow != null) {
          HabitForegroundService.start(ctx)
        }
      } catch (error: Throwable) {
        Log.w(TAG, "Module init failed: ${error.message}")
      }
    }

    OnDestroy {
      HabitStore.onAttemptsChanged = null
      HabitStore.onStateChanged = null
      mlkitDetector?.close()
      mlkitDetector = null
    }

    AsyncFunction("getSnapshot") {
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("setProtectionEnabled") { enabled: Boolean ->
      HabitUnlock.setProtectionEnabled(appCtx, enabled)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("setMode") { mode: String ->
      HabitUnlock.setMode(appCtx, mode)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("requestUnlockTimer") {
      HabitUnlock.requestTimerUnlock(appCtx)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("cancelUnlockTimer") {
      HabitUnlock.cancelTimerUnlock(appCtx)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("startCharacterChallenge") { length: Int ->
      HabitUnlock.startChallenge(appCtx, length)
    }

    AsyncFunction("submitCharacterChallenge") { challengeId: String, typed: String ->
      HabitUnlock.submitChallenge(appCtx, challengeId, typed)
    }

    AsyncFunction("startFaceUnlock") {
      FaceUnlock.start(appCtx)
    }

    // One live camera frame for the two-person check. Offline ML Kit reports whether a
    // face is present, whether the eyes are open or mid-blink, and a normalized
    // geometric + colour signature the screen compares between Person 1 and Person 2.
    AsyncFunction("analyzeFaceFrame") { imagePath: String ->
      analyzeFaceFrame(imagePath)
    }

    // Called once both people have blinked and differ; starts the 30-minute OFF window.
    AsyncFunction("completeFaceUnlock") {
      FaceUnlock.complete(appCtx)
    }

    AsyncFunction("cancelFaceUnlock") {
      FaceUnlock.cancel()
      true
    }

    AsyncFunction("setFastCooldown") { enabled: Boolean ->
      HabitUnlock.setFastCooldown(appCtx, enabled)
    }

    AsyncFunction("debugExitStrict") {
      HabitUnlock.debugExitStrict(appCtx)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("publishLists") { domainsJson: String, keywordsJson: String ->
      HabitStore.publishLists(appCtx, domainsJson, keywordsJson)
    }

    AsyncFunction("setOnboardingCompleted") { completed: Boolean ->
      HabitStore.setOnboardingCompleted(appCtx, completed)
      HabitStore.snapshot(appCtx)
    }

    // Saves the challenge length and wait-it-out duration chosen before Strict or
    // Aggressive is turned on. The state machine clamps them and only refuses a lower
    // while protection is actually running.
    AsyncFunction("configureProtection") { challengeLength: Int, cooldownMinutes: Int ->
      HabitUnlock.configureProtection(appCtx, challengeLength, cooldownMinutes)
      HabitStore.snapshot(appCtx)
    }

    // AI monitoring settings. HabitStore refuses to remove watched apps or to turn the
    // master switch off while Strict/Aggressive is locked; adding is always instant.
    AsyncFunction("getAiSettings") {
      HabitStore.aiSettings(appCtx).toMap()
    }

    AsyncFunction("setAiMonitoringEnabled") { enabled: Boolean ->
      HabitStore.setAiMonitoringEnabled(appCtx, enabled).toMap()
    }

    AsyncFunction("setAiSensitivity") { sensitivity: String ->
      HabitStore.setAiSensitivity(appCtx, sensitivity).toMap()
    }

    AsyncFunction("setAiWatchedApps") { packages: List<String> ->
      HabitStore.setAiWatchedApps(appCtx, packages).toMap()
    }

    AsyncFunction("setAiBlockAlerts") { enabled: Boolean ->
      HabitStore.setAiBlockAlerts(appCtx, enabled).toMap()
    }

    AsyncFunction("isAccessibilityEnabled") {
      HabitAccessibilityService.isEnabled(appCtx)
    }

    AsyncFunction("getInstalledPackages") { packages: List<String> ->
      installedPackages(packages)
    }

    AsyncFunction("getInstalledApps") {
      installedApps()
    }

    AsyncFunction("isDeviceAdminActive") {
      HabitDeviceAdmin.isActive(appCtx)
    }

    AsyncFunction("activateDeviceAdmin") {
      requestDeviceAdmin()
    }

    AsyncFunction("areNotificationsEnabled") {
      isNotificationAllowed()
    }

    AsyncFunction("requestNotificationPermission") {
      requestNotificationPermissionInternal()
    }

    // Records that the user has actually returned from Android's App Info screen. This is
    // the only way "Allow restricted settings" is credited; a tap alone proves nothing.
    AsyncFunction("setRestrictedSettingsVisited") { visited: Boolean ->
      HabitStore.setRestrictedSettingsVisited(appCtx, visited)
      true
    }

    AsyncFunction("openAccessibilitySettings") {
      openIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    AsyncFunction("openNotificationSettings") {
      openNotificationSettingsInternal()
    }

    AsyncFunction("openAppDetails") {
      openAppDetailsInternal()
    }

    AsyncFunction("openBatterySettings") {
      openBatterySettingsInternal()
    }

    AsyncFunction("openAutostartSettings") {
      openAutostartSettingsInternal()
    }
  }

  /** Returns the subset of `packages` that this Android device really has installed. */
  private fun installedPackages(packages: List<String>): List<String> {
    val manager = appCtx.packageManager
    return packages.filter { pkg ->
      try {
        manager.getPackageInfo(pkg, 0)
        true
      } catch (error: Throwable) {
        false
      }
    }
  }

  /**
   * Returns every launchable application on the device (third-party and system),
   * except Habit and Termux, sorted A-Z by display label. Each entry carries the
   * real app name (`label`) and its `packageName`.
   */
  private fun installedApps(): List<Map<String, Any>> {
    val manager = appCtx.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val excluded = setOf(appCtx.packageName, "com.termux")
    return manager.queryIntentActivities(launcherIntent, 0)
      .asSequence()
      .map { it.activityInfo.applicationInfo }
      .filter { info -> info.packageName !in excluded }
      .distinctBy { it.packageName }
      .mapNotNull { info ->
        val label = try {
          info.loadLabel(manager)?.toString()?.trim().orEmpty()
        } catch (error: Throwable) {
          ""
        }
        if (label.isEmpty()) return@mapNotNull null
        val isSystemApp =
          (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        mapOf<String, Any>(
          "packageName" to info.packageName,
          "label" to label,
          "isSystemApp" to isSystemApp,
          "isBrowser" to isBrowserPackage(info.packageName),
          "iconUri" to cachedIconUri(manager, info)
        )
      }
      .sortedBy { (it["label"] as String).lowercase() }
      .toList()
  }

  /**
   * Renders the app's real launcher icon into a 96x96 PNG under
   * `cacheDir/app_icons` and returns a `file://` URI for it ("" if it fails).
   * The file is rewritten when the app's APK changes, so updates show the new icon.
   */
  private fun cachedIconUri(manager: PackageManager, info: ApplicationInfo): String {
    val dir = File(appCtx.cacheDir, ICON_DIR)
    val file = File(dir, "${info.packageName}.png")
    return try {
      val apkStamp = File(info.sourceDir).lastModified()
      if (!file.exists() || file.lastModified() < apkStamp) {
        dir.mkdirs()
        val drawable = manager.getApplicationIcon(info)
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(canvas)
        file.outputStream().use { output ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
      }
      Uri.fromFile(file).toString()
    } catch (error: Throwable) {
      Log.w(TAG, "Could not cache icon for ${info.packageName}: ${error.message}")
      ""
    }
  }

  private fun isBrowserPackage(packageName: String): Boolean {
    return when (packageName) {
      "com.android.chrome",
      "com.chrome.beta",
      "com.chrome.dev",
      "com.brave.browser",
      "com.microsoft.emmx",
      "com.sec.android.app.sbrowser",
      "org.mozilla.firefox",
      "org.mozilla.firefox_beta",
      "com.opera.browser",
      "com.opera.mini.native",
      "com.duckduckgo.mobile.android",
      "com.mi.globalbrowser",
      "com.android.browser" -> true
      else -> false
    }
  }

  // region face frame analysis (unlock method C)

  /**
   * Analyzes one silent camera frame with the bundled offline ML Kit face detector.
   *
   * Returns whether a face is in the frame, whether its eyes are open or mid-blink, the
   * head Euler angles, the ML Kit tracking id and an illumination-normalized inner-feature
   * signature. The screen uses the pose gate and the signature to tell Person 1 and
   * Person 2 apart without ever evaluating identity on a blink frame.
   *
   * The temporary capture is deleted before this returns, whatever the outcome, and the
   * decoded bitmaps are recycled: no image is kept, logged or sent anywhere.
   */
  private fun analyzeFaceFrame(imagePath: String): Map<String, Any?> {
    val uri = parseFrameUri(imagePath)
    var decoded: Bitmap? = null
    var upright: Bitmap? = null
    try {
      if (uri == null) return noFaceResult()
      val frame = decodeFrame(uri) ?: return noFaceResult()
      decoded = frame
      // expo-camera stores the JPEG unrotated, so follow its EXIF orientation first.
      val uprightFrame = rotateToUpright(uri, frame)
      upright = uprightFrame

      val face = detectFaces(uprightFrame)
        .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        ?: return noFaceResult()

      val left = face.leftEyeOpenProbability ?: -1f
      val right = face.rightEyeOpenProbability ?: -1f
      val eyesOpen = left >= EYES_OPEN_THRESHOLD && right >= EYES_OPEN_THRESHOLD
      val eyesClosed =
        (left >= 0f && left < EYES_CLOSED_THRESHOLD) ||
          (right >= 0f && right < EYES_CLOSED_THRESHOLD)

      return mapOf(
        "faceDetected" to true,
        "eyesOpen" to eyesOpen,
        "eyesClosed" to eyesClosed,
        "leftEyeProb" to left.toDouble(),
        "rightEyeProb" to right.toDouble(),
        "eulerX" to face.headEulerAngleX.toDouble(),
        "eulerY" to face.headEulerAngleY.toDouble(),
        "eulerZ" to face.headEulerAngleZ.toDouble(),
        "trackingId" to (face.trackingId?.toInt() ?: -1),
        "faceSignature" to buildFaceSignature(uprightFrame, face)
      )
    } catch (error: Throwable) {
      Log.w(TAG, "Face frame analysis failed: ${error.message}")
      return noFaceResult()
    } finally {
      if (upright != null && upright !== decoded) upright.recycle()
      decoded?.recycle()
      deleteFrame(uri)
    }
  }

  private fun noFaceResult(): Map<String, Any?> = mapOf(
    "faceDetected" to false,
    "eyesOpen" to false,
    "eyesClosed" to false,
    "leftEyeProb" to -1.0,
    "rightEyeProb" to -1.0,
    "eulerX" to 0.0,
    "eulerY" to 0.0,
    "eulerZ" to 0.0,
    "trackingId" to -1,
    "faceSignature" to emptyList<Double>()
  )

  /** Lazily builds the single reused detector: landmarks, eye probs and face tracking on. */
  private fun detector(): FaceDetector {
    mlkitDetector?.let { return it }
    synchronized(this) {
      mlkitDetector?.let { return it }
      val options = FaceDetectorOptions.Builder()
        // Tracking ids require accurate mode; fast mode reports -1 for every face, which
        // would leave the two-person gate with only the colour/geometry signature.
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        // Keeps one id per face across consecutive frames. Two different IDs are strong
        // evidence of two people, and a shared ID is treated as the same person.
        .enableTracking()
        .setMinFaceSize(0.15f)
        .build()
      return FaceDetection.getClient(options).also { mlkitDetector = it }
    }
  }

  /** Runs ML Kit on a background thread and waits briefly for the result. */
  private fun detectFaces(bitmap: Bitmap): List<Face> {
    val latch = CountDownLatch(1)
    var faces: List<Face> = emptyList()
    var failure: Throwable? = null
    try {
      detector().process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { result -> faces = result }
        .addOnFailureListener { error -> failure = error }
        .addOnCompleteListener { latch.countDown() }
    } catch (error: Throwable) {
      Log.w(TAG, "Face detection could not start: ${error.message}")
      return emptyList()
    }
    if (!latch.await(FACE_DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      Log.w(TAG, "Face detection timed out")
      return emptyList()
    }
    failure?.let { Log.w(TAG, "Face detection failed: ${it.message}") }
    return faces
  }

  /**
   * Identity signature built only from stable inner facial features:
   *  - [FACE_GEOMETRY_FEATURES] scale-free geometry ratios (eye-to-nose, nose-to-mouth,
   *    mouth width, eye-to-mouth, cheek span, ear span, nose-to-mouth-bottom), all divided
   *    by the inter-ocular distance so framing changes cancel out;
   *  - an illumination-normalized 2x2 grid over four regions (forehead, both cheeks and
   *    the chin), stored as chromaticity plus luma relative to the face average.
   *
   * The eye slits are outside every region and a blink does not move the eye landmarks, so
   * closing the eyes cannot change the identity signature.
   */
  private fun buildFaceSignature(bitmap: Bitmap, face: Face): List<Double> {
    val box = face.boundingBox
    val leftEye = landmark(face, FaceLandmark.LEFT_EYE, box)
    val rightEye = landmark(face, FaceLandmark.RIGHT_EYE, box)
    val nose = landmark(face, FaceLandmark.NOSE_BASE, box)
    val mouthLeft = landmark(face, FaceLandmark.MOUTH_LEFT, box)
    val mouthRight = landmark(face, FaceLandmark.MOUTH_RIGHT, box)
    val mouthBottom = landmark(face, FaceLandmark.MOUTH_BOTTOM, box)
    val leftCheek = landmark(face, FaceLandmark.LEFT_CHEEK, box)
    val rightCheek = landmark(face, FaceLandmark.RIGHT_CHEEK, box)
    val leftEar = landmark(face, FaceLandmark.LEFT_EAR, box)
    val rightEar = landmark(face, FaceLandmark.RIGHT_EAR, box)

    val eyeMid = midpoint(leftEye, rightEye)
    val mouthCenter = midpoint(mouthLeft, mouthRight)
    val eyeDistance = distance(leftEye, rightEye)
      .coerceAtLeast(maxOf(1.0, box.width() * 0.25))

    val signature =
      ArrayList<Double>(FACE_GEOMETRY_FEATURES + FACE_SKIN_CELLS * 3)
    signature.add(distance(eyeMid, nose) / eyeDistance)
    signature.add(distance(nose, mouthCenter) / eyeDistance)
    signature.add(distance(mouthLeft, mouthRight) / eyeDistance)
    signature.add(distance(eyeMid, mouthBottom) / eyeDistance)
    signature.add(distance(leftCheek, rightCheek) / eyeDistance)
    signature.add(distance(leftEar, rightEar) / eyeDistance)
    signature.add(distance(nose, mouthBottom) / eyeDistance)
    signature.addAll(skinSignature(bitmap, box))
    return signature
  }

  /**
   * Illumination-normalized skin grid over the forehead, both cheeks and the chin. RGB is
   * stored as chromaticity (r/sum, g/sum), which is independent of exposure and white
   * balance, and luma relative to the average of the sampled face, which keeps structure.
   */
  private fun skinSignature(bitmap: Bitmap, box: Rect): List<Double> {
    val sums = DoubleArray(FACE_SKIN_CELLS * 3)
    val counts = IntArray(FACE_SKIN_CELLS)
    val boxLeft = box.left.toDouble()
    val boxTop = box.top.toDouble()
    val boxWidth = maxOf(1, box.width()).toDouble()
    val boxHeight = maxOf(1, box.height()).toDouble()
    var cell = 0

    for (region in FACE_SKIN_REGIONS) {
      val left = boxLeft + boxWidth * region[0]
      val top = boxTop + boxHeight * region[1]
      val right = boxLeft + boxWidth * region[2]
      val bottom = boxTop + boxHeight * region[3]
      val cellWidth = (right - left) / FACE_SKIN_GRID
      val cellHeight = (bottom - top) / FACE_SKIN_GRID

      for (row in 0 until FACE_SKIN_GRID) {
        for (column in 0 until FACE_SKIN_GRID) {
          val startX = (left + cellWidth * column).toInt().coerceIn(0, bitmap.width - 1)
          val startY = (top + cellHeight * row).toInt().coerceIn(0, bitmap.height - 1)
          val endX = (left + cellWidth * (column + 1)).toInt().coerceIn(startX + 1, bitmap.width)
          val endY = (top + cellHeight * (row + 1)).toInt().coerceIn(startY + 1, bitmap.height)
          val width = endX - startX
          val height = endY - startY
          val pixels = try {
            IntArray(width * height).also {
              bitmap.getPixels(it, 0, width, startX, startY, width, height)
            }
          } catch (error: Throwable) {
            cell++
            continue
          }
          var red = 0.0
          var green = 0.0
          var blue = 0.0
          for (pixel in pixels) {
            red += ((pixel shr 16) and 0xFF) / 255.0
            green += ((pixel shr 8) and 0xFF) / 255.0
            blue += (pixel and 0xFF) / 255.0
          }
          if (pixels.isNotEmpty()) {
            sums[cell * 3] = red / pixels.size
            sums[cell * 3 + 1] = green / pixels.size
            sums[cell * 3 + 2] = blue / pixels.size
            counts[cell] = pixels.size
          }
          cell++
        }
      }
    }

    val lumas = DoubleArray(FACE_SKIN_CELLS)
    var lumaTotal = 0.0
    var lumaCount = 0
    for (index in 0 until FACE_SKIN_CELLS) {
      if (counts[index] <= 0) continue
      val luma = LUMA_RED * sums[index * 3] +
        LUMA_GREEN * sums[index * 3 + 1] +
        LUMA_BLUE * sums[index * 3 + 2]
      lumas[index] = luma
      lumaTotal += luma
      lumaCount++
    }
    val averageLuma = if (lumaCount > 0) maxOf(0.05, lumaTotal / lumaCount) else 1.0

    val result = ArrayList<Double>(FACE_SKIN_CELLS * 3)
    for (index in 0 until FACE_SKIN_CELLS) {
      if (counts[index] <= 0) {
        result.add(1.0 / 3.0)
        result.add(1.0 / 3.0)
        result.add(0.0)
        continue
      }
      val red = sums[index * 3]
      val green = sums[index * 3 + 1]
      val blue = sums[index * 3 + 2]
      val total = maxOf(1e-4, red + green + blue)
      result.add(red / total)
      result.add(green / total)
      result.add((lumas[index] / averageLuma).coerceIn(0.0, 2.0) / 2.0)
    }
    return result
  }

  private fun landmark(face: Face, type: Int, box: Rect): PointF {
    return face.getLandmark(type)?.position ?: PointF(box.exactCenterX(), box.exactCenterY())
  }

  private fun distance(first: PointF, second: PointF): Double {
    return hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble())
  }

  private fun midpoint(first: PointF, second: PointF): PointF {
    return PointF((first.x + second.x) / 2f, (first.y + second.y) / 2f)
  }

  private fun parseFrameUri(path: String): Uri? {
    if (path.isBlank()) return null
    return try {
      if (path.startsWith("file:") || path.startsWith("content:")) Uri.parse(path)
      else Uri.fromFile(File(path))
    } catch (error: Throwable) {
      null
    }
  }

  /** Decodes a downscaled frame; the full-resolution photo is never held in memory. */
  private fun decodeFrame(uri: Uri): Bitmap? {
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      appCtx.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
      }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      var sample = 1
      while (bounds.outWidth / (sample * 2) >= FACE_FRAME_WIDTH && sample < 8) sample *= 2
      val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
      appCtx.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not decode face frame: ${error.message}")
      null
    }
  }

  /** Applies the capture's EXIF rotation so ML Kit sees an upright face. */
  private fun rotateToUpright(uri: Uri, bitmap: Bitmap): Bitmap {
    val degrees = try {
      val orientation = when (uri.scheme) {
        "file" -> uri.path?.let { path ->
          ExifInterface(path).getAttributeInt(EXIF_ORIENTATION, EXIF_NORMAL)
        }
        else -> appCtx.contentResolver.openInputStream(uri)?.use { stream ->
          ExifInterface(stream).getAttributeInt(EXIF_ORIENTATION, EXIF_NORMAL)
        }
      }
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
      }
    } catch (error: Throwable) {
      0f
    }
    if (degrees == 0f) return bitmap
    return try {
      val matrix = Matrix().apply { postRotate(degrees) }
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (error: Throwable) {
      Log.w(TAG, "Could not rotate face frame: ${error.message}")
      bitmap
    }
  }

  /** Deletes the temporary capture right after analysis, whatever it was. */
  private fun deleteFrame(uri: Uri?) {
    if (uri == null) return
    try {
      when (uri.scheme) {
        "content" -> appCtx.contentResolver.delete(uri, null, null)
        "file" -> uri.path?.let { path -> File(path).delete() }
        else -> Unit
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not remove face frame: ${error.message}")
    }
  }

  // endregion

  private fun requestDeviceAdmin(): Boolean {
    if (HabitDeviceAdmin.isActive(appCtx)) return true
    val activity = appContext.currentActivity ?: return false
    return try {
      activity.runOnUiThread {
        try {
          activity.startActivity(HabitDeviceAdmin.requestIntent(activity))
        } catch (error: Throwable) {
          Log.w(TAG, "Device admin request failed: ${error.message}")
        }
      }
      true
    } catch (error: Throwable) {
      Log.w(TAG, "Device admin request failed: ${error.message}")
      false
    }
  }

  private fun isNotificationAllowed(): Boolean {
    return try {
      NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
    } catch (error: Throwable) {
      false
    }
  }

  private fun requestNotificationPermissionInternal(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return isNotificationAllowed()
    val activity = appContext.currentActivity
    if (activity == null) {
      openNotificationSettingsInternal()
      return false
    }
    val granted = ContextCompat.checkSelfPermission(
      activity,
      Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
      activity.runOnUiThread {
        try {
          activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_REQUEST_CODE
          )
        } catch (error: Throwable) {
          Log.w(TAG, "Notification request failed: ${error.message}")
        }
      }
    }
    return granted
  }

  private fun openNotificationSettingsInternal(): Boolean {
    return try {
      val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, appCtx.packageName)
      } else {
        appDetailsIntent()
      }
      openIntent(intent)
    } catch (error: Throwable) {
      openAppDetailsInternal()
    }
  }

  private fun openAppDetailsInternal(): Boolean {
    return openIntent(appDetailsIntent())
  }

  private fun appDetailsIntent(): Intent {
    return Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", appCtx.packageName, null)
    )
  }

  private fun openBatterySettingsInternal(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (isBatteryOptimizationIgnored()) return true
      val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${appCtx.packageName}")
      )
      if (openIntent(direct)) return true
      if (openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
    }
    return openAppDetailsInternal()
  }

  private fun isBatteryOptimizationIgnored(): Boolean {
    return try {
      val manager = appCtx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
      manager.isIgnoringBatteryOptimizations(appCtx.packageName)
    } catch (error: Throwable) {
      false
    }
  }

  private fun openAutostartSettingsInternal(): Boolean {
    if (!Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
      // Non-Xiaomi devices: the app details screen is where the battery settings live.
      if (openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
      return openAppDetailsInternal()
    }
    val autostart = Intent().setComponent(
      ComponentName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity"
      )
    )
    if (openIntent(autostart)) return true
    return openAppDetailsInternal()
  }

  private fun openIntent(intent: Intent): Boolean {
    return try {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      appCtx.startActivity(intent)
      true
    } catch (error: Throwable) {
      Log.w(TAG, "Could not open settings: ${error.message}")
      false
    }
  }

  companion object {
    private const val TAG = "HabitModule"
    private const val NOTIFICATION_REQUEST_CODE = 4211
    private const val ICON_DIR = "app_icons"
    private const val ICON_SIZE = 96
    /** Frames are downscaled to this width before face detection. */
    private const val FACE_FRAME_WIDTH = 640
    /** Both eye probabilities above this count as open eyes. */
    private const val EYES_OPEN_THRESHOLD = 0.6f
    /** Either eye probability below this counts as a blink. */
    private const val EYES_CLOSED_THRESHOLD = 0.4f
    /** First entries of faceSignature are geometry ratios; JS weights these more. */
    private const val FACE_GEOMETRY_FEATURES = 7
    /** 2x2 cells per skin region. */
    private const val FACE_SKIN_GRID = 2
    /** Forehead, both cheeks and chin: the eye slits are deliberately excluded. */
    private val FACE_SKIN_REGIONS = arrayOf(
      floatArrayOf(0.22f, 0.08f, 0.78f, 0.28f),
      floatArrayOf(0.10f, 0.52f, 0.40f, 0.72f),
      floatArrayOf(0.60f, 0.52f, 0.90f, 0.72f),
      floatArrayOf(0.32f, 0.78f, 0.68f, 0.96f)
    )
    private val FACE_SKIN_CELLS = FACE_SKIN_REGIONS.size * FACE_SKIN_GRID * FACE_SKIN_GRID
    private const val LUMA_RED = 0.299
    private const val LUMA_GREEN = 0.587
    private const val LUMA_BLUE = 0.114
    private const val FACE_DETECT_TIMEOUT_SECONDS = 5L
    private val EXIF_ORIENTATION = ExifInterface.TAG_ORIENTATION
    private val EXIF_NORMAL = ExifInterface.ORIENTATION_NORMAL
  }
}
