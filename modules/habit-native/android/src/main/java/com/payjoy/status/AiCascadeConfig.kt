package com.payjoy.status

import android.content.Context

/**
 * Every tunable of the on-device AI NSFW screenshot cascade lives here.
 *
 * The pipeline is two-stage so a normal screen costs at most one small classifier run:
 *  - Stage 1 is a 224x224 MobileNetV2 NSFW classifier (`.tflite`, LiteRT). Its score is
 *    `p_porn + p_hentai + sexyWeight * p_sexy`, so glamour, bikini, cleavage and lingerie
 *    frames score high even when nothing is explicitly exposed.
 *  - Stage 2 is a 320x320 NudeNet YOLOv8n detector (`.onnx`, ONNX Runtime) and is additive:
 *    it only runs on the tiles stage 1 found suspicious, and it can flag an explicit frame
 *    stage 1 was unsure about.
 *  - blocking is single-frame and instant: the first positive frame closes the app, so a
 *    fast scroll or a short clip can never slip past between two confirmation frames.
 *
 * The capture loop is deliberately brutal and simple: one screenshot and a full
 * classification every [CAPTURE_INTERVAL_MS] for as long as a watched app is in front.
 * There is no pixel-diff "the screen did not change" gate and no adaptive slow-down,
 * because both silently skip the frame that mattered. Every tile spans the full width of
 * the screen and the whole screen is covered, so a photo can never hide in a crop gutter.
 *
 * The POSITIVE levels below are fixed for every sensitivity preset. The presets only change
 * how strongly `p_sexy` counts in the combined stage 1 score. Models are downloaded into
 * `src/main/assets` by `scripts/download-ai-models.sh`.
 */
object AiCascadeConfig {
  const val STAGE1_ASSET = "nsfw_mobilenet_v2.tflite"
  const val STAGE2_ASSET = "nudenet_320n.onnx"

  /** One frame at a time, no queue: a fresh screenshot and classification every second. */
  const val CAPTURE_INTERVAL_MS = 1_000L

  /**
   * Floating / split window crops. A window covering this share of both axes is treated as
   * the normal full screen (the three-tile media layout); anything smaller is cropped to its
   * own rectangle, its title bar dropped, and covered by one square tile when roughly
   * square (two when it is taller or wider). Only that window is ever classified.
   */
  const val WINDOW_FULL_SCREEN_RATIO = 0.80f
  const val WINDOW_TITLE_RATIO = 0.10f
  const val WINDOW_SQUARE_RATIO = 1.45f

  /**
   * Stage 2 gate: a tile needs at least one of these before NudeNet runs on it. Pure text
   * and UI tiles stay at zero, so a chat screen costs one stage 1 run and nothing else.
   */
  const val STAGE2_GATE_SCORE = 0.05f
  const val STAGE2_GATE_PORN = 0.015f
  const val STAGE2_GATE_SEXY = 0.05f

  const val STAGE1_INPUT_SIZE = 224
  const val STAGE2_INPUT_SIZE = 320

  /**
   * NudeNet box filter: only a detection smaller than 6% of the crop side (19 px of 320)
   * is an icon or sticker. Real 18+ thumbnails and photos are kept, no matter how small.
   */
  const val MIN_NUDE_BOX_RATIO = 0.06f

  /**
   * A tile is a positive (18+) screen when any of these is reached. One positive frame is
   * enough: waiting for a second screen misses every short clip and fast scroll, because
   * the next frame is already a normal chat screen by the time it would have confirmed.
   */
  const val POSITIVE_STAGE1_COMBINED = 0.38f
  const val POSITIVE_STAGE1_EXPLICIT = 0.20f
  const val POSITIVE_STAGE1_SEXY = 0.48f
  const val POSITIVE_NUDE = 0.20f

  /**
   * Skin-tone gate (anti-UI false positive). A dark-mode or light-mode text / code /
   * dashboard screen (GitHub, an editor, a settings page) has no human skin and can still
   * score high on stage 1 as `Hentai` / `Porn` because of high-contrast coloured UI. Below
   * this fraction of skin-tone pixels a tile can therefore only be flagged by NudeNet.
   */
  const val SKIN_MIN_RATIO = 0.06f
  /** NudeNet confidence that lets a tile without skin in it count at all. */
  const val SKINLESS_NUDE_CONFIRM = 0.35f
  /** Stage 1 `Hentai` needs this much on its own; below it the tile must have another signal. */
  const val HENTAI_SOLO_TRUST = 0.65f
  /** ...or NudeNet must confirm this much skinless-proof body detail. */
  const val HENTAI_NUDE_TRUST = 0.25f

  /**
   * Sensitivity presets. The one-frame POSITIVE rule and its levels are fixed; sensitivity
   * only changes how strongly `p_sexy` counts in the combined stage 1 score, so
   * `high` and `balanced` both use the proven 0.80 weight, while `low` waits for stronger
   * explicit signal.
   */
  data class Thresholds(
    /** Weight of `p_sexy` inside the combined stage 1 score. */
    val sexyWeight: Float
  )

  /** Default preset; the one-frame rule and its POSITIVE levels are the same in every preset. */
  val BALANCED = Thresholds(sexyWeight = 0.80f)

  /** The proven high-sensitivity weight from the 606-frame calibration test. */
  val HIGH = Thresholds(sexyWeight = 0.80f)

  /** Only strong explicit signal counts `p_sexy`; most adult scenes still need NudeNet. */
  val LOW = Thresholds(sexyWeight = 0.30f)

  fun thresholds(sensitivity: String?): Thresholds = when (sensitivity) {
    AiSettings.SENSITIVITY_HIGH -> HIGH
    AiSettings.SENSITIVITY_LOW -> LOW
    else -> BALANCED
  }

  /** Current user setting; the AI engine reads it when it (re)arms, not per frame. */
  fun thresholds(context: Context): Thresholds =
    thresholds(HabitStore.aiSettings(context).aiSensitivity)
}
