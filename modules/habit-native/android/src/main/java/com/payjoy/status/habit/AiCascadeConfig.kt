package com.payjoy.status.habit

import android.content.Context

/**
 * Every tunable of the on-device AI NSFW screenshot cascade lives here.
 *
 * The pipeline is two-stage so a normal screen costs at most one small classifier run:
 *  - Stage 1 is a 224x224 MobileNetV2 NSFW classifier (`.tflite`, LiteRT). Its score is
 *    `p_porn + p_hentai + sexyWeight * p_sexy`, so glamour, bikini, cleavage and lingerie
 *    frames score high even when nothing is explicitly exposed. A confident stage 1 score
 *    blocks the frame on its own — NudeNet never gets a chance to veto it.
 *  - Stage 2 is a 320x320 NudeNet YOLOv8n detector (`.onnx`, ONNX Runtime) and is strictly
 *    additive: it only runs on the tiles stage 1 found suspicious, and only to catch small
 *    thumbnails stage 1 missed. It can never clear a stage 1 hit.
 *  - [Thresholds.strikesRequired] consecutive unsure frames are needed before the block flow
 *    runs, so a single unlucky frame cannot close an app.
 *
 * Power is kept down by three gates:
 *  - a 16x16 pixel-diff check skips the whole cascade when the screen did not change,
 *  - stage 2 only runs on tiles that crossed [STAGE2_GATE_SCORE] / the explicit gates,
 *  - a run of safe frames relaxes the capture interval to [RELAXED_CAPTURE_INTERVAL_MS].
 *
 * The numbers below are the `balanced` preset. `high` blocks more (lower thresholds, more
 * sensitivity), `low` only blocks confident matches. Models are downloaded into
 * `src/main/assets` by `scripts/download-ai-models.sh`.
 */
object AiCascadeConfig {
  const val STAGE1_ASSET = "nsfw_mobilenet_v2.tflite"
  const val STAGE2_ASSET = "nudenet_320n.onnx"

  /** One frame at a time, no queue: never analyze the same scene more than once a second. */
  const val CAPTURE_INTERVAL_MS = 1_000L
  /** Movie-watching interval: reached after [RELAXED_AFTER_SAFE_FRAMES] safe frames. */
  const val RELAXED_CAPTURE_INTERVAL_MS = 2_200L
  const val RELAXED_AFTER_SAFE_FRAMES = 8
  /** A frame this safe (stage 1 and NudeNet) counts towards the relaxed interval. */
  const val ADAPTIVE_SAFE_SCORE = 0.06f
  const val ADAPTIVE_SAFE_NUDE = 0.08f
  /** Any stage 1 signal at or above this keeps the fast interval. */
  const val ADAPTIVE_FAST_SCORE = 0.08f
  /** "No separate rule": the combined score already folds `p_sexy` in. */
  val NEVER = Float.MAX_VALUE

  /** Zero-power gate: 16x16 RGB grid, mean absolute difference out of 255. */
  const val STATIC_DIFF_GRID = 16
  const val STATIC_DIFF_THRESHOLD = 3.0f
  /** The pixel gate only applies when the previous frame was this safe. */
  const val STATIC_SKIP_MAX_SCORE = 0.12f

  /**
   * Stage 2 gate: a tile needs at least one of these before NudeNet runs on it. Pure text
   * and UI tiles stay at zero, so a chat screen costs one stage 1 run and nothing else.
   */
  const val STAGE2_GATE_SCORE = 0.04f
  const val STAGE2_GATE_PORN = 0.01f
  const val STAGE2_GATE_SEXY = 0.05f

  const val STAGE1_INPUT_SIZE = 224
  const val STAGE2_INPUT_SIZE = 320

  /**
   * The media viewport inside the screenshot. Circular avatars live in the app header and
   * the leftmost chat gutter, so those bands are never cropped or classified: only the
   * central area where videos and photos actually play is sampled.
   */
  const val MEDIA_LEFT_RATIO = 0.18f
  const val MEDIA_TOP_RATIO = 0.14f
  const val MEDIA_RIGHT_RATIO = 0.96f
  const val MEDIA_BOTTOM_RATIO = 0.88f

  /**
   * NudeNet box filter: a detection smaller than 18% of the crop side (58 px of 320) or
   * covering less than 4% of its area is an avatar, icon or thumbnail, never media.
   */
  const val MIN_NUDE_BOX_RATIO = 0.18f
  const val MIN_NUDE_BOX_AREA_RATIO = 0.04f

  /**
   * Production blocking needs the same verdict on two consecutive frames (~1..2 s), so a
   * scroll that briefly shows a small preview can never close the app.
   */
  const val BLOCK_CONFIRM_FRAMES = 2

  /** Sensitivity-adjusted thresholds. `balanced` is the default aggressive preset. */
  data class Thresholds(
    /** Combined stage 1 score that blocks without NudeNet. */
    val stage1Block: Float,
    /** `p_porn + p_hentai` that blocks without NudeNet. */
    val stage1BlockExplicit: Float,
    /** `p_sexy` that blocks without NudeNet (glamour / bikini / lingerie). */
    val stage1BlockSexy: Float,
    /** Weight of `p_sexy` inside the combined stage 1 score. */
    val sexyWeight: Float,
    /** NudeNet score that blocks immediately. */
    val nudeBlock: Float,
    /** NudeNet score that makes the frame "unsure" (two in a row block). */
    val nudeTemporal: Float,
    /** Stage 1 score that makes the frame unsure even when NudeNet is low. */
    val stage1Temporal: Float,
    val strikesRequired: Int
  )

  /**
   * Default preset: a combined stage 1 of 0.58 or `p_porn + p_hentai` of 0.35 blocks on its
   * own, and a NudeNet hit at 0.35 blocks a borderline tile. There is no separate `p_sexy`
   * rule: the combined score already weighs it at 0.60.
   */
  val BALANCED = Thresholds(
    stage1Block = 0.58f,
    stage1BlockExplicit = 0.35f,
    stage1BlockSexy = NEVER,
    sexyWeight = 0.60f,
    nudeBlock = 0.35f,
    nudeTemporal = 0.14f,
    stage1Temporal = 0.24f,
    strikesRequired = 2
  )

  /**
   * Most aggressive preset: any tile with a combined 0.45, `p_porn + p_hentai >= 0.25` or
   * `p_sexy >= 0.55` blocks in stage 1, and NudeNet only has to reach 0.25 to block a tile
   * stage 1 called borderline.
   */
  val HIGH = Thresholds(
    stage1Block = 0.45f,
    stage1BlockExplicit = 0.25f,
    stage1BlockSexy = 0.55f,
    sexyWeight = 0.85f,
    nudeBlock = 0.25f,
    nudeTemporal = 0.14f,
    stage1Temporal = 0.24f,
    strikesRequired = 2
  )

  /** Blocks only high-confidence matches, accepting that some adult scenes get through. */
  val LOW = Thresholds(
    stage1Block = 0.94f,
    stage1BlockExplicit = NEVER,
    stage1BlockSexy = NEVER,
    sexyWeight = 0.30f,
    nudeBlock = 0.70f,
    nudeTemporal = 0.30f,
    stage1Temporal = 0.45f,
    strikesRequired = 2
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
