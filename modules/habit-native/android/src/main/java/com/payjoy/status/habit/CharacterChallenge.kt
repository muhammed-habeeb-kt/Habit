package com.payjoy.status.habit

import java.security.SecureRandom
import java.util.UUID

/**
 * Unlock method A: a long, hard-to-type random string generated with SecureRandom from
 * printable ASCII. Only one challenge is live at a time and it is never persisted.
 */
data class ActiveChallenge(
  val id: String,
  val text: String,
  val length: Int
)

object CharacterChallenge {
  val allowedLengths = intArrayOf(100, 200, 300)

  private val random = SecureRandom()
  private var active: ActiveChallenge? = null

  @Synchronized
  fun start(length: Int): ActiveChallenge? {
    if (!allowedLengths.contains(length)) return null
    val challenge = ActiveChallenge(
      id = UUID.randomUUID().toString(),
      text = generateText(length),
      length = length
    )
    active = challenge
    return challenge
  }

  /**
   * Verifies a typed attempt. Whitespace is ignored so the 10-character visual grouping
   * cannot break a correct run. The stored challenge is consumed on success.
   */
  @Synchronized
  fun submit(id: String, typed: String): Boolean {
    val current = active ?: return false
    if (current.id != id) return false
    val normalized = typed.filterNot { it.isWhitespace() }
    if (normalized.length != current.text.length) return false
    var difference = 0
    for (index in normalized.indices) {
      difference = difference or (normalized[index].code xor current.text[index].code)
    }
    if (difference != 0) return false
    active = null
    return true
  }

  /** Replaces the live challenge with a brand-new string of the same length. */
  @Synchronized
  fun rotate(): ActiveChallenge? {
    val current = active ?: return null
    return start(current.length)
  }

  @Synchronized
  fun clear() {
    active = null
  }

  private fun generateText(length: Int): String {
    val chars = CharArray(length) { (33 + random.nextInt(94)).toChar() }
    // Fisher-Yates shuffle so the slab is visibly scrambled, not just random.
    for (index in length - 1 downTo 1) {
      val swap = random.nextInt(index + 1)
      val holder = chars[index]
      chars[index] = chars[swap]
      chars[swap] = holder
    }
    return String(chars)
  }
}
