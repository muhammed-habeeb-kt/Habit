package com.payjoy.status.habit

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A timed unlock window (cooldown or auto re-enable window). */
data class UnlockWindow(
  val durationMs: Long,
  val savedRemainingMs: Long,
  val savedAtElapsed: Long,
  /** Wall-clock deadline, only used to stop an OFF window from growing after a reboot. */
  val endWall: Long = 0L
)

data class FailureState(
  val total: Int = 0,
  val consecutive: Int = 0,
  val day: String = "",
  val dayCount: Int = 0,
  val lockedUntilWall: Long = 0L
)

data class UnlockState(
  val cooldown: UnlockWindow? = null,
  val offWindow: UnlockWindow? = null,
  val restoreMode: String = ProtectionState.MODE_STRICT,
  val failures: FailureState = FailureState(),
  val fastCooldown: Boolean = false
)

/**
 * Settings for the future on-device AI visual scanner.
 *
 * No analyzer runs yet. These values are persisted here so the native scanner
 * can read them without any UI or storage change when it is implemented.
 */
data class AiSettings(
  val enabled: Boolean = true,
  /** Package names of the apps the user selected. Empty until they choose some. */
  monitoredAppPackages: List<String> = emptyList(),
  val aiSensitivity: String = SENSITIVITY_BALANCED,
  val blockAlertsEnabled: Boolean = true
) {
  companion object {
    const val SENSITIVITY_LOW = "low"
    const val SENSITIVITY_BALANCED = "balanced"
    const val SENSITIVITY_HIGH = "high"

    fun normalizeSensitivity(value: String?): String = when (value) {
      SENSITIVITY_LOW, SENSITIVITY_HIGH -> value
      else -> SENSITIVITY_BALANCED
    }
  }

  fun toMap(): Map<String, Any?> = mapOf(
    "enabled" to enabled,
    "monitoredAppPackages" to monitoredAppPackages,
    "aiSensitivity" to aiSensitivity,
    "blockAlertsEnabled" to blockAlertsEnabled
  )
}

data class ProtectionState(
  val protectionEnabled: Boolean = true,
  val mode: String = MODE_NORMAL,
  val onboardingCompleted: Boolean = false
) {
  companion object {
    const val MODE_NORMAL = "normal"
    const val MODE_STRICT = "strict"
    const val MODE_AGGRESSIVE = "aggressive"

    fun normalizeMode(value: String?): String = when (value) {
      MODE_STRICT, MODE_AGGRESSIVE -> value
      else -> MODE_NORMAL
    }
  }
}

/**
 * Durable, encrypted storage for protection state, unlock state, per-day attempt counts
 * and the blocklist/keyword lists.
 *
 * Fail closed: a missing or unreadable state is treated as protection ON.
 * Privacy: only daily counts are stored, never URLs or browsing history.
 */
object HabitStore {
  private const val TAG = "HabitStore"
  private const val PREFS_NAME = "habit_secure"
  private const val KEY_STATE = "state"
  private const val KEY_ATTEMPTS = "attempts"
  private const val KEY_DOMAINS = "domains"
  private const val KEY_KEYWORDS = "keywords"
  private const val KEY_UNLOCK = "unlock"
  private const val KEY_AI = "ai"
  private const val KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "habit_state_key_v1"
  private const val PREFIX_ENC = "enc1:"
  private const val PREFIX_RAW = "raw1:"
  private const val DEDUPE_WINDOW_MS = 5_000L
  private const val MAX_DAYS = 30
  private const val GCM_TAG_BITS = 128

  private val lock = Any()
  private val dedupeAt = HashMap<String, Long>()

  @Volatile private var prefs: SharedPreferences? = null
  @Volatile private var cachedState: ProtectionState? = null
  @Volatile private var cachedUnlock: UnlockState? = null
  @Volatile private var cachedDomains: List<String>? = null
  @Volatile private var cachedKeywords: List<String>? = null
  @Volatile private var cachedAi: AiSettings? = null

  /** Called on a background thread after an attempt is counted. */
  @Volatile var onAttemptsChanged: ((Int) -> Unit)? = null

  /** Called after protection state changes. */
  @Volatile var onStateChanged: (() -> Unit)? = null

  fun init(context: Context) {
    if (prefs != null) return
    synchronized(lock) {
      if (prefs == null) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      }
    }
  }

  private fun store(context: Context): SharedPreferences {
    init(context)
    return requireNotNull(prefs) { "HabitStore not initialized" }
  }

  // region crypto

  private fun secretKey(): SecretKey? {
    return try {
      val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
      val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
      existing?.secretKey ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
          KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
          )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        )
        generator.generateKey()
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Keystore key unavailable: ${error.message}")
      null
    }
  }

  private fun encrypt(plain: String): String? {
    return try {
      val key = secretKey() ?: return null
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val iv = cipher.iv
      val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
      val packed = ByteArray(iv.size + body.size)
      System.arraycopy(iv, 0, packed, 0, iv.size)
      System.arraycopy(body, 0, packed, iv.size, body.size)
      PREFIX_ENC + Base64.encodeToString(packed, Base64.NO_WRAP)
    } catch (error: Throwable) {
      Log.w(TAG, "Encryption failed: ${error.message}")
      null
    }
  }

  private fun decrypt(stored: String): String? {
    return try {
      when {
        stored.startsWith(PREFIX_ENC) -> {
          val packed = Base64.decode(stored.substring(PREFIX_ENC.length), Base64.NO_WRAP)
          if (packed.size <= 12) return null
          val key = secretKey() ?: return null
          val cipher = Cipher.getInstance("AES/GCM/NoPadding")
          cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, packed, 0, 12)
          )
          String(cipher.doFinal(packed, 12, packed.size - 12), Charsets.UTF_8)
        }
        stored.startsWith(PREFIX_RAW) ->
          String(Base64.decode(stored.substring(PREFIX_RAW.length), Base64.NO_WRAP), Charsets.UTF_8)
        else -> null
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Decryption failed: ${error.message}")
      null
    }
  }

  private fun write(context: Context, key: String, value: String) {
    val encoded = encrypt(value)
      ?: (PREFIX_RAW + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
    store(context).edit().putString(key, encoded).apply()
  }

  private fun read(context: Context, key: String): String? {
    val stored = store(context).getString(key, null) ?: return null
    return decrypt(stored)
  }

  // endregion

  // region protection state

  fun state(context: Context): ProtectionState {
    cachedState?.let { return it }
    val raw = read(context, KEY_STATE)
    if (raw == null) {
      val fallback = ProtectionState(protectionEnabled = true)
      cachedState = fallback
      return fallback
    }
    val parsed = try {
      val json = JSONObject(raw)
      ProtectionState(
        protectionEnabled = json.optBoolean("protectionEnabled", true),
        mode = ProtectionState.normalizeMode(json.optString("mode", ProtectionState.MODE_NORMAL)),
        onboardingCompleted = json.optBoolean("onboardingCompleted", false)
      )
    } catch (error: Throwable) {
      Log.w(TAG, "State unreadable, failing closed")
      ProtectionState(protectionEnabled = true)
    }
    cachedState = parsed
    return parsed
  }

  private fun writeState(context: Context, state: ProtectionState) {
    val json = JSONObject()
      .put("protectionEnabled", state.protectionEnabled)
      .put("mode", state.mode)
      .put("onboardingCompleted", state.onboardingCompleted)
    write(context, KEY_STATE, json.toString())
    cachedState = state
    onStateChanged?.invoke()
  }

  /** Applies a transformation atomically; used by the native state machine only. */
  fun updateState(
    context: Context,
    transform: (ProtectionState) -> ProtectionState
  ): ProtectionState {
    synchronized(lock) {
      val next = transform(state(context))
      writeState(context, next)
      return next
    }
  }

  fun setOnboardingCompleted(context: Context, completed: Boolean) {
    synchronized(lock) {
      writeState(context, state(context).copy(onboardingCompleted = completed))
    }
  }

  // endregion

  // region AI monitoring settings

  fun aiSettings(context: Context): AiSettings {
    cachedAi?.let { return it }
    val raw = read(context, KEY_AI)
    val parsed = if (raw.isNullOrBlank()) AiSettings() else parseAiSettings(raw)
    cachedAi = parsed
    return parsed
  }

  private fun parseAiSettings(raw: String): AiSettings {
    return try {
      val json = JSONObject(raw)
      val monitored = json.optJSONArray("monitoredAppPackages")
      val watched = ArrayList<String>()
      if (monitored != null) {
        for (index in 0 until monitored.length()) {
          val pkg = monitored.optString(index, "").trim()
          if (pkg.isNotEmpty() && !watched.contains(pkg)) watched.add(pkg)
        }
      }
      AiSettings(
        enabled = json.optBoolean("enabled", true),
        aiSensitivity = AiSettings.normalizeSensitivity(
          json.optString("aiSensitivity", AiSettings.SENSITIVITY_BALANCED)
        ),
        monitoredAppPackages = watched,
        blockAlertsEnabled = json.optBoolean("blockAlertsEnabled", true)
      )
    } catch (error: Throwable) {
      Log.w(TAG, "AI settings unreadable, using defaults")
      AiSettings()
    }
  }

  private fun writeAiSettings(context: Context, settings: AiSettings) {
    val json = JSONObject()
      .put("enabled", settings.enabled)
      .put("monitoredAppPackages", JSONArray(settings.monitoredAppPackages))
      .put("aiSensitivity", settings.aiSensitivity)
      .put("blockAlertsEnabled", settings.blockAlertsEnabled)
    write(context, KEY_AI, json.toString())
    cachedAi = settings
    onStateChanged?.invoke()
  }

  fun setAiMonitoringEnabled(context: Context, enabled: Boolean): AiSettings {
    synchronized(lock) {
      val next = aiSettings(context).copy(enabled = enabled)
      writeAiSettings(context, next)
      return next
    }
  }

  fun setAiSensitivity(context: Context, sensitivity: String): AiSettings {
    synchronized(lock) {
      val next = aiSettings(context).copy(
        aiSensitivity = AiSettings.normalizeSensitivity(sensitivity)
      )
      writeAiSettings(context, next)
      return next
    }
  }

  fun setAiWatchedApps(context: Context, packages: List<String>): AiSettings {
    synchronized(lock) {
      val cleaned = packages
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(200)
        .toList()
      val next = aiSettings(context).copy(monitoredAppPackages = cleaned)
      writeAiSettings(context, next)
      return next
    }
  }

  fun setAiBlockAlerts(context: Context, enabled: Boolean): AiSettings {
    synchronized(lock) {
      val next = aiSettings(context).copy(blockAlertsEnabled = enabled)
      writeAiSettings(context, next)
      return next
    }
  }

  // endregion

  // region unlock state

  fun unlockState(context: Context): UnlockState {
    cachedUnlock?.let { return it }
    val raw = read(context, KEY_UNLOCK)
    val parsed = if (raw.isNullOrBlank()) UnlockState() else parseUnlock(raw)
    cachedUnlock = parsed
    return parsed
  }

  fun writeUnlockState(context: Context, state: UnlockState) {
    synchronized(lock) {
      write(context, KEY_UNLOCK, serializeUnlock(state))
      cachedUnlock = state
    }
  }

  private fun serializeUnlock(state: UnlockState): String {
    val json = JSONObject()
    state.cooldown?.let { json.put("cooldown", windowJson(it)) }
    state.offWindow?.let { json.put("offWindow", windowJson(it)) }
    json.put("restoreMode", state.restoreMode)
    json.put("fastCooldown", state.fastCooldown)
    json.put(
      "failures",
      JSONObject()
        .put("total", state.failures.total)
        .put("consecutive", state.failures.consecutive)
        .put("day", state.failures.day)
        .put("dayCount", state.failures.dayCount)
        .put("lockedUntilWall", state.failures.lockedUntilWall)
    )
    return json.toString()
  }

  private fun windowJson(window: UnlockWindow): JSONObject {
    return JSONObject()
      .put("durationMs", window.durationMs)
      .put("savedRemainingMs", window.savedRemainingMs)
      .put("savedAtElapsed", window.savedAtElapsed)
      .put("endWall", window.endWall)
  }

  private fun parseUnlock(raw: String): UnlockState {
    return try {
      val json = JSONObject(raw)
      val failures = json.optJSONObject("failures")
      UnlockState(
        cooldown = parseWindow(json.optJSONObject("cooldown")),
        offWindow = parseWindow(json.optJSONObject("offWindow")),
        restoreMode = ProtectionState.normalizeMode(json.optString("restoreMode", ProtectionState.MODE_STRICT)),
        fastCooldown = json.optBoolean("fastCooldown", false),
        failures = FailureState(
          total = failures?.optInt("total", 0) ?: 0,
          consecutive = failures?.optInt("consecutive", 0) ?: 0,
          day = failures?.optString("day", "") ?: "",
          dayCount = failures?.optInt("dayCount", 0) ?: 0,
          lockedUntilWall = failures?.optLong("lockedUntilWall", 0L) ?: 0L
        )
      )
    } catch (error: Throwable) {
      Log.w(TAG, "Unlock state unreadable, starting clean")
      UnlockState()
    }
  }

  private fun parseWindow(json: JSONObject?): UnlockWindow? {
    if (json == null) return null
    val duration = json.optLong("durationMs", 0L)
    if (duration <= 0L) return null
    val remaining = json.optLong("savedRemainingMs", 0L).coerceIn(0L, duration)
    return UnlockWindow(
      durationMs = duration,
      savedRemainingMs = remaining,
      savedAtElapsed = json.optLong("savedAtElapsed", 0L),
      endWall = json.optLong("endWall", 0L)
    )
  }

  // endregion

  // region attempts

  fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

  private fun attemptsJson(context: Context): JSONObject {
    val raw = read(context, KEY_ATTEMPTS) ?: return JSONObject()
    return try {
      JSONObject(raw)
    } catch (error: Throwable) {
      JSONObject()
    }
  }

  fun attemptsToday(context: Context): Int = attemptsJson(context).optInt(today(), 0)

  /**
   * Counts one block event. Returns the new daily total, or null when the same
   * domain/keyword was already counted within the last 5 seconds.
   */
  fun registerAttempt(context: Context, matchKey: String): Int? {
    synchronized(lock) {
      val now = System.currentTimeMillis()
      val last = dedupeAt[matchKey]
      if (last != null && now - last < DEDUPE_WINDOW_MS) return null
      dedupeAt[matchKey] = now
      if (dedupeAt.size > 64) {
        dedupeAt.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS * 4 }
      }

      val json = attemptsJson(context)
      val day = today()
      val count = json.optInt(day, 0) + 1
      json.put(day, count)
      trimAttempts(json)
      write(context, KEY_ATTEMPTS, json.toString())
      onAttemptsChanged?.invoke(count)
      return count
    }
  }

  private fun trimAttempts(json: JSONObject) {
    val days = json.keys().asSequence().toList().sorted()
    if (days.size <= MAX_DAYS) return
    days.dropLast(MAX_DAYS).forEach { json.remove(it) }
  }

  // endregion

  // region lists

  fun publishLists(context: Context, domainsJson: String, keywordsJson: String): Boolean {
    return try {
      JSONArray(domainsJson)
      JSONArray(keywordsJson)
      synchronized(lock) {
        write(context, KEY_DOMAINS, domainsJson)
        write(context, KEY_KEYWORDS, keywordsJson)
        cachedDomains = null
        cachedKeywords = null
      }
      AdultFilter.update(domains(context), keywords(context))
      true
    } catch (error: Throwable) {
      Log.w(TAG, "publishLists failed: ${error.message}")
      false
    }
  }

  fun domains(context: Context): List<String> {
    cachedDomains?.let { return it }
    val parsed = parseStringArray(read(context, KEY_DOMAINS))
    cachedDomains = parsed
    return parsed
  }

  fun keywords(context: Context): List<String> {
    cachedKeywords?.let { return it }
    val parsed = parseStringArray(read(context, KEY_KEYWORDS))
    cachedKeywords = parsed
    return parsed
  }

  private fun parseStringArray(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { index ->
        val value = array.optString(index, "").trim()
        value.ifEmpty { null }
      }
    } catch (error: Throwable) {
      emptyList()
    }
  }

  fun ensureFilter(context: Context) {
    AdultFilter.update(domains(context), keywords(context))
  }

  // endregion

  fun snapshot(context: Context): Map<String, Any?> {
    val state = state(context)
    return mapOf(
      "protectionEnabled" to state.protectionEnabled,
      "mode" to state.mode,
      "onboardingCompleted" to state.onboardingCompleted,
      "attemptsToday" to attemptsToday(context),
      "accessibilityEnabled" to HabitAccessibilityService.isEnabled(context),
      "foregroundServiceRunning" to HabitForegroundService.isRunning,
      "debugBuild" to HabitUnlock.isDebugBuild(context),
      "deviceAdminActive" to HabitDeviceAdmin.isActive(context),
      "unlock" to HabitUnlock.snapshotPart(context),
      "ai" to aiSettings(context).toMap()
    )
  }
}
