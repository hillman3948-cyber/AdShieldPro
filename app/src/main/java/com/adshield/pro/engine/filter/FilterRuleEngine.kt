package com.adshield.pro.engine.filter

import com.adshield.pro.domain.model.CustomRule
import com.adshield.pro.domain.model.FilterEvaluationResult
import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.RuleConflictReport
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.RuleType
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-performance, thread-safe multi-layer domain filtering engine.
 *
 * Uses an immutable [FilterSnapshot] held inside an [AtomicReference] so that:
 * - DNS packet threads perform lock-free lookups in microseconds.
 * - Protection mode changes, custom rule edits, and filter list updates swap atomically
 *   without restarting the Android VpnService tunnel.
 */
@Singleton
class FilterRuleEngine @Inject constructor() {

    data class FilterListEntry(
        val category: RuleCategory,
        val sourceName: String,
        val matchType: RuleMatchType
    )

    data class WildcardEntry(
        val rawPattern: String,
        val regex: Regex,
        val ruleType: RuleType,
        val category: RuleCategory,
        val sourceName: String,
        val priority: Int
    )

    data class FilterSnapshot(
        val protectionMode: ProtectionMode = ProtectionMode.STANDARD,
        val customAllowExact: Map<String, CustomRule> = emptyMap(),
        val customAllowSubdomain: Map<String, CustomRule> = emptyMap(),
        val customBlockExact: Map<String, CustomRule> = emptyMap(),
        val customBlockSubdomain: Map<String, CustomRule> = emptyMap(),
        val customWildcards: List<WildcardEntry> = emptyList(),
        val builtInAllowSubdomains: Set<String> = DEFAULT_SAFE_ALLOWLIST,
        val filterListSubdomains: Map<String, FilterListEntry> = emptyMap(),
        val filterListExact: Map<String, FilterListEntry> = emptyMap(),
        val filterListWildcards: List<WildcardEntry> = emptyList()
    ) {
        val totalLoadedRules: Int
            get() = customAllowExact.size +
                customAllowSubdomain.size +
                customBlockExact.size +
                customBlockSubdomain.size +
                customWildcards.size +
                builtInAllowSubdomains.size +
                filterListSubdomains.size +
                filterListExact.size +
                filterListWildcards.size
    }

    private val snapshotRef = AtomicReference(FilterSnapshot())

    fun getCurrentSnapshot(): FilterSnapshot = snapshotRef.get()

    /**
     * Atomically updates the active protection mode without rebuilding the filter lists or restarting the VPN.
     */
    fun updateProtectionMode(mode: ProtectionMode) {
        snapshotRef.updateAndGet { current ->
            current.copy(protectionMode = mode)
        }
    }

    /**
     * Atomically updates the user's custom rules in memory.
     */
    fun updateCustomRules(rules: List<CustomRule>) {
        val enabled = rules.filter { it.isEnabled }
        val allowExact = HashMap<String, CustomRule>()
        val allowSubdomain = HashMap<String, CustomRule>()
        val blockExact = HashMap<String, CustomRule>()
        val blockSubdomain = HashMap<String, CustomRule>()
        val wildcards = ArrayList<WildcardEntry>()

        for (rule in enabled) {
            val norm = normalizeDomain(rule.normalizedPattern)
            when (rule.matchType) {
                RuleMatchType.EXACT -> {
                    if (rule.ruleType == RuleType.ALLOW) allowExact[norm] = rule
                    else blockExact[norm] = rule
                }
                RuleMatchType.SUBDOMAIN -> {
                    if (rule.ruleType == RuleType.ALLOW) allowSubdomain[norm] = rule
                    else blockSubdomain[norm] = rule
                }
                RuleMatchType.WILDCARD -> {
                    compileWildcardRegex(rule.normalizedPattern)?.let { regex ->
                        wildcards.add(
                            WildcardEntry(
                                rawPattern = rule.normalizedPattern,
                                regex = regex,
                                ruleType = rule.ruleType,
                                category = rule.category,
                                sourceName = "User Custom Rule",
                                priority = rule.priority
                            )
                        )
                    }
                }
            }
        }

        wildcards.sortByDescending { it.priority }

        snapshotRef.updateAndGet { current ->
            current.copy(
                customAllowExact = allowExact,
                customAllowSubdomain = allowSubdomain,
                customBlockExact = blockExact,
                customBlockSubdomain = blockSubdomain,
                customWildcards = wildcards
            )
        }
    }

    /**
     * Atomically replaces the compiled filter list rules. Supports instant rollback if needed.
     */
    fun updateFilterListRules(
        subdomainRules: Map<String, FilterListEntry>,
        exactRules: Map<String, FilterListEntry>,
        wildcardRules: List<WildcardEntry>,
        allowSubdomains: Set<String> = DEFAULT_SAFE_ALLOWLIST
    ): FilterSnapshot {
        val previous = snapshotRef.get()
        snapshotRef.set(
            previous.copy(
                builtInAllowSubdomains = DEFAULT_SAFE_ALLOWLIST + allowSubdomains,
                filterListSubdomains = subdomainRules,
                filterListExact = exactRules,
                filterListWildcards = wildcardRules
            )
        )
        return previous
    }

    fun restoreSnapshot(snapshot: FilterSnapshot) {
        snapshotRef.set(snapshot)
    }

    /**
     * Evaluates a domain against all active layers in strict priority order:
     * 1. User Custom Allow (Exact & Subdomain & Wildcard, Priority 100)
     * 2. User Custom Block (Exact & Subdomain & Wildcard, Priority 90)
     * 3. Built-in Safety Allowlist (Priority 80)
     * 4. Active Filter Lists filtered by current ProtectionMode categories (Priority 50)
     */
    fun evaluate(rawDomain: String): FilterEvaluationResult {
        val startNs = System.nanoTime()
        val domain = normalizeDomain(rawDomain)
        if (domain.isEmpty()) {
            return FilterEvaluationResult(
                domain = rawDomain,
                isBlocked = false,
                matchedRule = null,
                matchedCategory = null,
                matchType = null,
                sourceName = "Invalid/Empty",
                priority = 0,
                evaluationTimeMicros = 0L
            )
        }

        val snap = snapshotRef.get()
        val suffixes = extractDomainSuffixes(domain)

        // 1. User Custom Allow (Exact)
        snap.customAllowExact[domain]?.let { rule ->
            return buildResult(domain, false, rule.rawPattern, rule.category, RuleMatchType.EXACT, "User Allowlist", rule.priority, startNs)
        }

        // 2. User Custom Allow (Subdomain walk)
        for (suffix in suffixes) {
            snap.customAllowSubdomain[suffix]?.let { rule ->
                return buildResult(domain, false, rule.rawPattern, rule.category, RuleMatchType.SUBDOMAIN, "User Allowlist", rule.priority, startNs)
            }
        }

        // 3. User Custom Wildcards (Allow first, then Block by priority)
        for (wc in snap.customWildcards) {
            if (wc.regex.matches(domain)) {
                val blocked = wc.ruleType == RuleType.BLOCK
                return buildResult(domain, blocked, wc.rawPattern, wc.category, RuleMatchType.WILDCARD, wc.sourceName, wc.priority, startNs)
            }
        }

        // 4. User Custom Block (Exact)
        snap.customBlockExact[domain]?.let { rule ->
            return buildResult(domain, true, rule.rawPattern, rule.category, RuleMatchType.EXACT, "User Blocklist", rule.priority, startNs)
        }

        // 5. User Custom Block (Subdomain walk)
        for (suffix in suffixes) {
            snap.customBlockSubdomain[suffix]?.let { rule ->
                return buildResult(domain, true, rule.rawPattern, rule.category, RuleMatchType.SUBDOMAIN, "User Blocklist", rule.priority, startNs)
            }
        }

        // 6. Built-in Safety Allowlist (Subdomain walk)
        for (suffix in suffixes) {
            if (snap.builtInAllowSubdomains.contains(suffix)) {
                return buildResult(domain, false, "@@||$suffix^", null, RuleMatchType.SUBDOMAIN, "System Safety Allowlist", 80, startNs)
            }
        }

        val activeCategories = snap.protectionMode.activeCategories

        // 7. Filter List Exact Match
        snap.filterListExact[domain]?.let { entry ->
            if (activeCategories.contains(entry.category)) {
                return buildResult(domain, true, domain, entry.category, RuleMatchType.EXACT, entry.sourceName, 50, startNs)
            }
        }

        // 8. Filter List Subdomain Suffix Walk (O(label_count) hash lookups)
        for (suffix in suffixes) {
            val entry = snap.filterListSubdomains[suffix]
            if (entry != null && activeCategories.contains(entry.category)) {
                return buildResult(domain, true, "||$suffix^", entry.category, RuleMatchType.SUBDOMAIN, entry.sourceName, 50, startNs)
            }
        }

        // 9. Filter List Wildcard Rules
        for (wc in snap.filterListWildcards) {
            if (activeCategories.contains(wc.category) && wc.regex.matches(domain)) {
                return buildResult(domain, true, wc.rawPattern, wc.category, RuleMatchType.WILDCARD, wc.sourceName, wc.priority, startNs)
            }
        }

        return buildResult(domain, false, null, null, null, "Allowed", 0, startNs)
    }

    private fun buildResult(
        domain: String,
        isBlocked: Boolean,
        matchedRule: String?,
        category: RuleCategory?,
        matchType: RuleMatchType?,
        sourceName: String,
        priority: Int,
        startNs: Long
    ): FilterEvaluationResult {
        val elapsedMicros = (System.nanoTime() - startNs) / 1_000L
        return FilterEvaluationResult(
            domain = domain,
            isBlocked = isBlocked,
            matchedRule = matchedRule,
            matchedCategory = category,
            matchType = matchType,
            sourceName = sourceName,
            priority = priority,
            evaluationTimeMicros = elapsedMicros
        )
    }

    /**
     * Validates and parses a user-supplied rule or domain string.
     * Rejects malformed domains, IP literals, executable/HTML snippets, or overly broad wildcards (`*`).
     */
    fun validateAndParseRuleInput(
        rawInput: String,
        explicitType: RuleType? = null,
        explicitMatchType: RuleMatchType? = null,
        comment: String = ""
    ): Result<CustomRule> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Rule pattern cannot be empty."))
        }
        if (trimmed.length > 253) {
            return Result.failure(IllegalArgumentException("Domain pattern exceeds maximum length of 253 characters."))
        }
        if (trimmed.any { it.isWhitespace() || it in setOf('<', '>', '"', '\'', ';', '(', ')', '{', '}') }) {
            return Result.failure(IllegalArgumentException("Rule contains illegal or unsafe characters."))
        }

        var working = trimmed.lowercase()
        var detectedRuleType = explicitType ?: RuleType.BLOCK
        var detectedMatchType = explicitMatchType

        // Detect Adblock exception syntax @@||domain^
        if (working.startsWith("@@")) {
            detectedRuleType = explicitType ?: RuleType.ALLOW
            working = working.removePrefix("@@")
        }

        // Detect Adblock ||domain^ syntax
        if (working.startsWith("||")) {
            working = working.removePrefix("||")
            if (detectedMatchType == null) {
                detectedMatchType = RuleMatchType.SUBDOMAIN
            }
        }

        // Strip trailing ^ or $options
        if (working.contains('^')) {
            working = working.substringBefore('^')
        }
        if (working.contains('$')) {
            working = working.substringBefore('$')
        }

        working = normalizeDomain(working)

        if (working.isEmpty() || working == "*" || working.all { it == '*' || it == '.' }) {
            return Result.failure(IllegalArgumentException("Wildcard cannot match all domains on the internet."))
        }

        // Reject raw IPv4 literals as domain rules
        if (IPV4_LITERAL_REGEX.matches(working)) {
            return Result.failure(IllegalArgumentException("Please enter a domain name or wildcard pattern rather than a raw IPv4 address."))
        }

        val hasWildcard = working.contains('*')
        val finalMatchType = when {
            hasWildcard -> RuleMatchType.WILDCARD
            detectedMatchType != null -> detectedMatchType
            else -> RuleMatchType.SUBDOMAIN
        }

        if (hasWildcard) {
            val cleaned = working.replace("*", "a")
            if (!isValidDomainStructure(cleaned, allowSingleLabel = true)) {
                return Result.failure(IllegalArgumentException("Malformed wildcard domain pattern: '$trimmed'"))
            }
            if (compileWildcardRegex(working) == null) {
                return Result.failure(IllegalArgumentException("Unable to compile wildcard pattern: '$trimmed'"))
            }
        } else {
            if (!isValidDomainStructure(working, allowSingleLabel = false)) {
                return Result.failure(IllegalArgumentException("Invalid domain name '$working'. Example valid format: ads.example.com"))
            }
        }

        val priority = if (detectedRuleType == RuleType.ALLOW) 100 else 90
        return Result.success(
            CustomRule(
                rawPattern = trimmed,
                normalizedPattern = working,
                ruleType = detectedRuleType,
                matchType = finalMatchType,
                category = RuleCategory.CUSTOM,
                priority = priority,
                isEnabled = true,
                comment = comment.trim()
            )
        )
    }

    /**
     * Parses a complete filter list text stream (hosts format, Adblock DNS format, or plain domain lines).
     * Never executes code; rejects binary/HTML content.
     */
    data class ParsedFilterBatch(
        val subdomainRules: Map<String, FilterListEntry>,
        val exactRules: Map<String, FilterListEntry>,
        val wildcardRules: List<WildcardEntry>,
        val allowSubdomains: Set<String>,
        val totalValidRules: Int,
        val duplicateCount: Int
    )

    fun parseFilterListContent(
        content: String,
        defaultCategory: RuleCategory,
        sourceName: String
    ): Result<ParsedFilterBatch> {
        val sampleHead = content.take(512).trim().lowercase()
        if (sampleHead.startsWith("<!doctype html") || sampleHead.startsWith("<html") || sampleHead.startsWith("<script")) {
            return Result.failure(IllegalArgumentException("Filter source returned HTML/script instead of a valid plain-text domain list."))
        }
        if (content.indexOf('\u0000') >= 0) {
            return Result.failure(IllegalArgumentException("Binary payload rejected by filter validator."))
        }

        val subdomainMap = HashMap<String, FilterListEntry>()
        val exactMap = HashMap<String, FilterListEntry>()
        val wildcardList = ArrayList<WildcardEntry>()
        val allowSet = HashSet<String>()
        var duplicates = 0
        var activeCategory = defaultCategory

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            // Check category directive comment in our bundled list
            if (line.startsWith("#") || line.startsWith("!")) {
                when {
                    line.contains("[CATEGORY:TRACKERS]", ignoreCase = true) -> activeCategory = RuleCategory.TRACKERS
                    line.contains("[CATEGORY:MALWARE]", ignoreCase = true) -> activeCategory = RuleCategory.MALWARE
                    line.contains("[CATEGORY:STRICT_TELEMETRY]", ignoreCase = true) -> activeCategory = RuleCategory.STRICT_TELEMETRY
                    line.contains("[CATEGORY:ADS]", ignoreCase = true) -> activeCategory = RuleCategory.ADS
                }
                return@forEach
            }

            // Strip inline comments
            val withoutComment = line.substringBefore(" #").substringBefore("\t#").trim()
            if (withoutComment.isEmpty()) return@forEach

            // Ignore cosmetic HTML rules (e.g., example.com##.ad) in DNS filter parser
            if (withoutComment.contains("##") || withoutComment.contains("#@#") || withoutComment.contains("#?#")) {
                return@forEach
            }

            // Case 1: Hosts file format (0.0.0.0 domain.com or 127.0.0.1 domain.com)
            if (withoutComment.startsWith("0.0.0.0 ") ||
                withoutComment.startsWith("127.0.0.1 ") ||
                withoutComment.startsWith("::1 ") ||
                withoutComment.startsWith("0.0.0.0\t") ||
                withoutComment.startsWith("127.0.0.1\t")
            ) {
                val parts = withoutComment.split(WHITESPACE_REGEX)
                if (parts.size >= 2) {
                    val host = normalizeDomain(parts[1])
                    if (host !in IGNORED_LOCALHOSTS && isValidDomainStructure(host, false)) {
                        val prev = subdomainMap.putIfAbsent(
                            host,
                            FilterListEntry(activeCategory, sourceName, RuleMatchType.SUBDOMAIN)
                        )
                        if (prev != null) duplicates++
                    }
                }
                return@forEach
            }

            // Case 2: Exception rule @@||domain.com^
            if (withoutComment.startsWith("@@")) {
                val clean = normalizeDomain(
                    withoutComment.removePrefix("@@").removePrefix("||").substringBefore('^').substringBefore('$')
                )
                if (isValidDomainStructure(clean, false)) {
                    if (!allowSet.add(clean)) duplicates++
                }
                return@forEach
            }

            // Case 3: Adblock ||domain.com^
            if (withoutComment.startsWith("||")) {
                val clean = normalizeDomain(
                    withoutComment.removePrefix("||").substringBefore('^').substringBefore('$')
                )
                if (clean.contains('*')) {
                    compileWildcardRegex(clean)?.let { regex ->
                        wildcardList.add(
                            WildcardEntry(clean, regex, RuleType.BLOCK, activeCategory, sourceName, 50)
                        )
                    }
                } else if (isValidDomainStructure(clean, false)) {
                    val prev = subdomainMap.putIfAbsent(
                        clean,
                        FilterListEntry(activeCategory, sourceName, RuleMatchType.SUBDOMAIN)
                    )
                    if (prev != null) duplicates++
                }
                return@forEach
            }

            // Case 4: Wildcard or plain domain
            val clean = normalizeDomain(withoutComment.substringBefore('^').substringBefore('$'))
            if (clean.contains('*')) {
                compileWildcardRegex(clean)?.let { regex ->
                    wildcardList.add(
                        WildcardEntry(clean, regex, RuleType.BLOCK, activeCategory, sourceName, 50)
                    )
                }
            } else if (isValidDomainStructure(clean, false)) {
                val prev = subdomainMap.putIfAbsent(
                    clean,
                    FilterListEntry(activeCategory, sourceName, RuleMatchType.SUBDOMAIN)
                )
                if (prev != null) duplicates++
            }
        }

        val totalValid = subdomainMap.size + exactMap.size + wildcardList.size + allowSet.size
        return Result.success(
            ParsedFilterBatch(
                subdomainRules = subdomainMap,
                exactRules = exactMap,
                wildcardRules = wildcardList,
                allowSubdomains = allowSet,
                totalValidRules = totalValid,
                duplicateCount = duplicates
            )
        )
    }

    /**
     * Detects duplicate rules, Allow-vs-Block conflicts, and subdomain shadowing across custom rules
     * and loaded filter lists.
     */
    fun detectConflictsAndDuplicates(customRules: List<CustomRule>): List<RuleConflictReport> {
        val reports = ArrayList<RuleConflictReport>()
        val snap = snapshotRef.get()

        for (i in customRules.indices) {
            val r1 = customRules[i]
            for (j in i + 1 until customRules.size) {
                val r2 = customRules[j]
                if (r1.normalizedPattern == r2.normalizedPattern) {
                    if (r1.ruleType == r2.ruleType && r1.matchType == r2.matchType) {
                        reports.add(
                            RuleConflictReport(
                                conflictType = RuleConflictReport.ConflictType.EXACT_DUPLICATE,
                                primaryRule = r1,
                                conflictingRule = r2,
                                conflictingFilterListDomain = null,
                                explanation = "Both rules target '${r1.normalizedPattern}' with the same ${r1.ruleType} action. One duplicate can be safely deleted.",
                                winningAction = r1.ruleType
                            )
                        )
                    } else if (r1.ruleType != r2.ruleType) {
                        val allowRule = if (r1.ruleType == RuleType.ALLOW) r1 else r2
                        val blockRule = if (r1.ruleType == RuleType.BLOCK) r1 else r2
                        reports.add(
                            RuleConflictReport(
                                conflictType = RuleConflictReport.ConflictType.ALLOW_OVERRIDES_BLOCK,
                                primaryRule = allowRule,
                                conflictingRule = blockRule,
                                conflictingFilterListDomain = null,
                                explanation = "Allow rule '${allowRule.rawPattern}' (Priority ${allowRule.priority}) takes precedence over Block rule '${blockRule.rawPattern}' (Priority ${blockRule.priority}). '${r1.normalizedPattern}' will be ALLOWED.",
                                winningAction = RuleType.ALLOW
                            )
                        )
                    }
                } else {
                    // Check if one is a subdomain of another SUBDOMAIN rule
                    if (r1.matchType == RuleMatchType.SUBDOMAIN && r2.normalizedPattern.endsWith(".${r1.normalizedPattern}")) {
                        val winning = if (r1.ruleType == RuleType.ALLOW || r2.ruleType == RuleType.ALLOW) RuleType.ALLOW else RuleType.BLOCK
                        reports.add(
                            RuleConflictReport(
                                conflictType = RuleConflictReport.ConflictType.SUBDOMAIN_SHADOWED_BY_PARENT,
                                primaryRule = r2,
                                conflictingRule = r1,
                                conflictingFilterListDomain = null,
                                explanation = if (r1.ruleType == r2.ruleType) {
                                    "Subdomain rule '${r2.normalizedPattern}' is redundant because parent subdomain rule '${r1.normalizedPattern}' already covers all its subdomains."
                                } else {
                                    "Subdomain rule '${r2.normalizedPattern}' (${r2.ruleType}) conflicts with parent rule '${r1.normalizedPattern}' (${r1.ruleType}). Allow rules always win first (Priority 100)."
                                },
                                winningAction = winning
                            )
                        )
                    } else if (r2.matchType == RuleMatchType.SUBDOMAIN && r1.normalizedPattern.endsWith(".${r2.normalizedPattern}")) {
                        val winning = if (r1.ruleType == RuleType.ALLOW || r2.ruleType == RuleType.ALLOW) RuleType.ALLOW else RuleType.BLOCK
                        reports.add(
                            RuleConflictReport(
                                conflictType = RuleConflictReport.ConflictType.SUBDOMAIN_SHADOWED_BY_PARENT,
                                primaryRule = r1,
                                conflictingRule = r2,
                                conflictingFilterListDomain = null,
                                explanation = if (r1.ruleType == r2.ruleType) {
                                    "Subdomain rule '${r1.normalizedPattern}' is redundant because parent subdomain rule '${r2.normalizedPattern}' already covers all its subdomains."
                                } else {
                                    "Subdomain rule '${r1.normalizedPattern}' (${r1.ruleType}) conflicts with parent rule '${r2.normalizedPattern}' (${r2.ruleType}). Allow rules always win first (Priority 100)."
                                },
                                winningAction = winning
                            )
                        )
                    }
                }
            }

            // Also report if a user ALLOW rule overrides an active built-in filter list block
            if (r1.ruleType == RuleType.ALLOW && r1.matchType != RuleMatchType.WILDCARD) {
                val suffixes = extractDomainSuffixes(r1.normalizedPattern)
                val matchedListSuffix = suffixes.firstOrNull { snap.filterListSubdomains.containsKey(it) }
                if (matchedListSuffix != null) {
                    val listEntry = snap.filterListSubdomains[matchedListSuffix]
                    reports.add(
                        RuleConflictReport(
                            conflictType = RuleConflictReport.ConflictType.ALLOW_OVERRIDES_BLOCK,
                            primaryRule = r1,
                            conflictingRule = null,
                            conflictingFilterListDomain = matchedListSuffix,
                            explanation = "Your custom Allow rule '${r1.normalizedPattern}' (Priority 100) overrides filter list '${listEntry?.sourceName}' which blocks '||$matchedListSuffix^' (Priority 50).",
                            winningAction = RuleType.ALLOW
                        )
                    )
                }
            }
        }

        return reports
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val IPV4_LITERAL_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
        private val DOMAIN_LABEL_REGEX = Regex("^[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?$")

        private val IGNORED_LOCALHOSTS = setOf(
            "localhost",
            "localhost.localdomain",
            "local",
            "broadcasthost",
            "ip6-localhost",
            "ip6-loopback",
            "0.0.0.0",
            "127.0.0.1"
        )

        val DEFAULT_SAFE_ALLOWLIST = setOf(
            "connectivitycheck.gstatic.com",
            "clients3.google.com",
            "captive.apple.com",
            "play.googleapis.com",
            "android.clients.google.com"
        )

        fun normalizeDomain(input: String): String {
            var s = input.trim().lowercase()
            if (s.startsWith("http://")) s = s.removePrefix("http://")
            if (s.startsWith("https://")) s = s.removePrefix("https://")
            if (s.contains('/')) s = s.substringBefore('/')
            if (s.contains(':')) s = s.substringBefore(':')
            return s.trimEnd('.')
        }

        /**
         * Returns ordered domain suffixes from most specific to top-level parent:
         * e.g. "a.b.example.com" -> ["a.b.example.com", "b.example.com", "example.com"]
         */
        fun extractDomainSuffixes(domain: String): List<String> {
            val result = ArrayList<String>(6)
            var current = domain
            while (true) {
                val dotIndex = current.indexOf('.')
                if (dotIndex < 0) break
                result.add(current)
                current = current.substring(dotIndex + 1)
            }
            return result
        }

        fun isValidDomainStructure(domain: String, allowSingleLabel: Boolean): Boolean {
            if (domain.isEmpty() || domain.length > 253) return false
            val labels = domain.split('.')
            if (!allowSingleLabel && labels.size < 2) return false
            val tld = labels.last()
            if (!allowSingleLabel && (tld.length < 2 || tld.all { it.isDigit() })) return false
            return labels.all { label ->
                label.isNotEmpty() && label.length <= 63 && DOMAIN_LABEL_REGEX.matches(label)
            }
        }

        fun compileWildcardRegex(wildcardPattern: String): Regex? {
            return runCatching {
                val sb = StringBuilder("^")
                for (ch in wildcardPattern.lowercase()) {
                    when (ch) {
                        '*' -> sb.append("[a-z0-9._-]*")
                        '.' -> sb.append("\\.")
                        '-', '_' -> sb.append(ch)
                        in 'a'..'z', in '0'..'9' -> sb.append(ch)
                        else -> return null
                    }
                }
                sb.append("$")
                Regex(sb.toString())
            }.getOrNull()
        }
    }
}
