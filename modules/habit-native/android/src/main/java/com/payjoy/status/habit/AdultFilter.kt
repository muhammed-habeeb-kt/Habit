package com.payjoy.status.habit

import java.net.URLDecoder

/**
 * Offline matcher for adult domains and keywords.
 *
 * Domains match the host itself or any subdomain (m.example.com matches example.com).
 * Keywords match whole words only, so "class" never trips "ass".
 */
object AdultFilter {
  @Volatile private var domains: Set<String> = emptySet()
  @Volatile private var keywordRegex: Regex? = null

  /** Root-domain aliases of the same service, so a watched site covers its short links. */
  private val DOMAIN_ALIASES = mapOf(
    "t.me" to "telegram.org",
    "telegram.me" to "telegram.org",
    "telegram.dog" to "telegram.org"
  )

  private val hostRegex = Regex(
    "(?i)(?<![\\p{L}\\p{N}@._-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(?![\\p{L}\\p{N}-])"
  )

  @Synchronized
  fun update(domainList: List<String>, keywordList: List<String>) {
    val normalized = domainList.asSequence()
      .map { normalizeDomain(it) }
      .filter { it.isNotEmpty() }
      .toSet()
    domains = normalized
    keywordRegex = buildKeywordRegex(keywordList)
  }

  fun isReady(): Boolean = domains.isNotEmpty() || keywordRegex != null

  /** Returns the matched host or "kw:<keyword>", or null when the text is clean. */
  fun match(text: String): String? {
    if (text.isBlank()) return null
    val haystack = if (text.contains('%')) decodeSafe(text) else text

    val domainSet = domains
    if (domainSet.isNotEmpty()) {
      for (result in hostRegex.findAll(haystack)) {
        val host = result.groupValues[1].lowercase().trimEnd('.')
        if (isBlockedHost(host, domainSet)) return host
      }
    }

    val regex = keywordRegex ?: return null
    val found = regex.find(haystack) ?: return null
    return "kw:" + found.value.lowercase()
  }

  /**
   * The monitored AI website [host] belongs to, or null.
   *
   * Matching is flexible in both directions of what the user configured: a host of
   * `web.telegram.org` or `k.telegram.org` matches a monitored `telegram.org` (and vice
   * versa), and the known aliases of a service match each other, so adding `telegram.org`
   * also covers `t.me`. The stored entry itself is returned, so a block can name exactly
   * what the user added.
   */
  fun monitoredDomainFor(host: String, domains: List<String>): String? {
    if (domains.isEmpty()) return null
    val wanted = domainVariants(host)
    if (wanted.isEmpty()) return null
    for (entry in domains) {
      if (domainVariants(entry).any { it in wanted }) return entry
    }
    return null
  }

  /**
   * [value]'s host plus every parent domain it belongs to, with each known service alias
   * added as it is reached: `web.telegram.org` -> `web.telegram.org`, `telegram.org`;
   * `t.me` -> `t.me`, `telegram.org`. A bare TLD is never a variant, so watching
   * `telegram.org` cannot match an unrelated `something.org`.
   */
  private fun domainVariants(value: String): Set<String> {
    val variants = LinkedHashSet<String>()
    var candidate = normalizeDomain(value)
    while (candidate.isNotEmpty()) {
      variants.add(candidate)
      DOMAIN_ALIASES[candidate]?.let { variants.add(it) }
      val dot = candidate.indexOf('.')
      if (dot < 0) break
      // Keep stripping labels only while the parent still has a dot (a real domain).
      val parent = candidate.substring(dot + 1)
      if (!parent.contains('.')) break
      candidate = parent
    }
    return variants
  }

  /**
   * Host of a URL, a domain or a browser URL bar text, or null when the text has none.
   * `https://web.telegram.org/a/#123` becomes `web.telegram.org`.
   */
  fun hostOf(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val lowered = if (text.contains('%')) decodeSafe(text).lowercase() else text.lowercase()
    val match = hostRegex.find(lowered) ?: return null
    return match.groupValues[1].trimEnd('.')
  }

  private fun isBlockedHost(host: String, set: Set<String>): Boolean {
    if (set.contains(host)) return true
    var candidate = host
    var dot = candidate.indexOf('.')
    while (dot >= 0) {
      candidate = candidate.substring(dot + 1)
      if (set.contains(candidate)) return true
      dot = candidate.indexOf('.')
    }
    return false
  }

  /** Cleans user input into a bare lowercase hostname: no scheme, `www.`, path or port. */
  fun normalizeDomain(value: String): String {
    var domain = value.trim().lowercase()
    for (prefix in listOf("https://", "http://", "www.")) {
      if (domain.startsWith(prefix)) domain = domain.substring(prefix.length)
    }
    val cut = domain.indexOfAny(charArrayOf('/', '?', '#', ':', ' '))
    if (cut >= 0) domain = domain.substring(0, cut)
    return domain.trim('.')
  }

  private fun decodeSafe(text: String): String {
    return try {
      URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    } catch (error: Throwable) {
      text
    }
  }

  private fun buildKeywordRegex(list: List<String>): Regex? {
    val cleaned = list.asSequence()
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() }
      .distinct()
      .sortedByDescending { it.length }
      .toList()
    if (cleaned.isEmpty()) return null
    val body = cleaned.joinToString("|") { Regex.escape(it) }
    return try {
      Regex("(?<![\\p{L}\\p{N}])($body)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    } catch (error: Throwable) {
      null
    }
  }
}
