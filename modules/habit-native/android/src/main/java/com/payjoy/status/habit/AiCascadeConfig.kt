package com.payjoy.status.habit

import android.content.Context

/**
 * Every tunable of the on-device AI NSFW screenshot cascade lives here.
 *
 * The pipeline is two-stage so a normal screen costs one small classifier run:
 *  - Stage 1 is a 224x224 MobileNetV2 NSFW classifier (`.tflite`, LiteRT). A score below
 *    [Thresholds.stage1SafeExit] ends the frame immediately; a score at or above
 *    [Thresholds.stage1InstantBlock] is a confident hit; anything in between is the
 *    "gray zone" that goes to stage 2.
 *  - Stage 2 is a 320x320 NudeNet YOLOv8n detector (`.onnx`, ONNX Runtime). It confirms
 *    the gray zone ([Thresholds.stage2ConfirmBlock]) or calls it a false alarm.
 *  - [TEMPORAL_STRIKES_REQUIRED] consecutive confirmed frames are needed before the block
 *    flow runs, so a single unlucky frame (a thumbnail, a poster) cannot close an app.
 *
 * The numbers below are the `balanced` preset. `high` blocks more (lower thresholds,
 * more sensitivity), `low` only blocks confident matches (higher thresholds). Models are
 * downloaded into `src/main/assets` by `scripts/download-ai-models.sh`.
 */
object AiCascadeConfig {
  const val STAGE1_ASSET = "nsfw_mobilenet_v2.tflite"
  const val STAGE2_ASSET = "nudenet_320n.onnx"

  /** One frame at a time, no queue: never analyze the same scene more than once a second. */
  const val CAPTURE_INTERVAL_MS = 1_000L

  const val STAGE1_INPUT_SIZE = 224
  const val STAGE2_INPUT_SIZE = 320

  const val STAGE1_SAFE_EXIT_THRESHOLD = 0.20f
  const val STAGE1_INSTANT_BLOCK_THRESHOLD = 0.88f
  const val STAGE2_CONFIRM_BLOCK_THRESHOLD = 0.55f
  const val STAGE2_FALSE_ALARM_THRESHOLD = 0.20f
  const val TEMPORAL_STRIKES_REQUIRED = 2

  /** Sensitivity-adjusted thresholds. `balanced` is exactly the constants above. */
  data class Thresholds(
    val stage1SafeExit: Float,
    val stage1InstantBlock: Float,
    val stage2ConfirmBlock: Float,
    val stage2FalseAlarm: Float,
    val strikesRequired: Int
  )

  val BALANCED = Thresholds(
    stage1SafeExit = STAGE1_SAFE_EXIT_THRESHOLD,
    stage1InstantBlock = STAGE1_INSTANT_BLOCK_THRESHOLD,
    stage2ConfirmBlock = STAGE2_CONFIRM_BLOCK_THRESHOLD,
    stage2FalseAlarm = STAGE2_FALSE_ALARM_THRESHOLD,
    strikesRequired = TEMPORAL_STRIKES_REQUIRED
  )

  /** Catches more: exits the safe branch sooner and confirms the gray zone more easily. */
  val HIGH = Thresholds(
    stage1SafeExit = 0.14f,
    stage1InstantBlock = 0.80f,
    stage2ConfirmBlock = 0.45f,
    stage2FalseAlarm = 0.15f,
    strikesRequired = TEMPORAL_STRIKES_REQUIRED
  )

  /** Blocks only high-confidence matches, accepting that some adult scenes get through. */
  val LOW = Thresholds(
    stage1SafeExit = 0.30f,
    stage1InstantBlock = 0.94f,
    stage2ConfirmBlock = 0.70f,
    stage2FalseAlarm = 0.30f,
    strikesRequired = TEMPORAL_STRIKES_REQUIRED
  )

  fun thresholds(sensitivity: String?): Thresholds = when (sensitivity) {
    AiSettings.SENSITIVITY_HIGH -> HIGH
    AiSettings.SENSITIVITY_LOW -> LOW
    else -> BALANCED
  }

  /** Current user setting; the AI engine reads it when it (re)arms, not per frame. */
  fun thresholds(context: Context): Thresholds =
    thresholds(HabitStore.aiSettings(context).aiSensitivity)
}
