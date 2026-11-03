package com.adshield.pro.aether

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * User-approved, expiring allowlisting and safe policy rollback.
 *
 * A temporary override ALWAYS requires an explicit approval phrase, an approver
 * and a reason, and always expires (max 24h). Policy snapshots allow restoring
 * a previous configuration if protection breaks an app. All state is local.
 */
interface AetherPolicyStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class InMemoryAetherPolicyStore : AetherPolicyStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
}

class SharedPreferencesAetherPolicyStore(context: Context) : AetherPolicyStore {
    private val prefs = context.applicationContext.getSharedPreferences("aether_policy", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

class AetherPolicy(private val store: AetherPolicyStore) {
    companion object {
        const val APPROVAL_PHRASE = "I APPROVE THIS TEMPORARY ALLOWLIST"
        private const val KEY_ALLOW = "temporary_allowlist"
        private const val KEY_SNAPSHOT = "policy_snapshot"
        private const val KEY_AUDIT = "policy_audit"
        private const val KEY_FALSE_POSITIVES = "verified_false_positives"
    }

    private fun parseAll(): List<AetherAllowEntry> {
        val raw = store.get(KEY_ALLOW) ?: return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size < 5) return@mapNotNull null
            val expires = parts[1].toLongOrNull() ?: return@mapNotNull null
            val minutes = parts[4].toIntOrNull() ?: return@mapNotNull null
            AetherAllowEntry(parts[0], expires, parts[2], parts[3], minutes)
        }
    }

    private fun writeAll(entries: List<AetherAllowEntry>) {
        store.put(KEY_ALLOW, entries.joinToString("\n") {
            listOf(it.domain, it.expiresAtEpochMs.toString(), it.approvedBy,
                it.reason.replace("\t", " ").replace("\n", " "), it.minutes.toString()).joinToString("\t")
        })
    }

    fun activeEntries(nowEpochMs: Long = System.currentTimeMillis()): List<AetherAllowEntry> =
        parseAll().filter { it.expiresAtEpochMs > nowEpochMs }

    fun isTemporaryAllowed(domain: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val clean = AetherAnalyzer.normalize(domain)
        return activeEntries(nowEpochMs).any { it.domain == clean }
    }

    /** @throws IllegalArgumentException when approval requirements are not met. */
    fun approveTemporaryAllow(
        domain: String, minutes: Int, approver: String, reason: String,
        approvalPhrase: String, nowEpochMs: Long = System.currentTimeMillis()
    ): AetherAllowEntry {
        val clean = AetherAnalyzer.normalize(domain)
        if (clean.isEmpty()) throw IllegalArgumentException("invalid domain")
        if (approvalPhrase != APPROVAL_PHRASE) throw IllegalArgumentException("explicit approval phrase required")
        if (approver.isBlank()) throw IllegalArgumentException("approver is required")
        if (reason.isBlank()) throw IllegalArgumentException("a reason is required for the audit trail")
        if (minutes !in 1..1440) throw IllegalArgumentException("minutes must be 1..1440 (24 hours)")
        val entry = AetherAllowEntry(clean, nowEpochMs + minutes.toLong() * 60_000L,
            approver.trim().take(120), reason.trim().take(300), minutes)
        val updated = activeEntries(nowEpochMs).filter { it.domain != clean } + entry
        writeAll(updated)
        appendAudit("temp_allow.approved domain=$clean minutes=$minutes approver=${entry.approvedBy}")
        return entry
    }

    fun revoke(domain: String, nowEpochMs: Long = System.currentTimeMillis()) {
        val clean = AetherAnalyzer.normalize(domain)
        writeAll(activeEntries(nowEpochMs).filter { it.domain != clean })
        appendAudit("temp_allow.revoked domain=$clean")
    }

    /** A verified false-positive label suppresses auto-blocking but never deletes intelligence. */
    fun markFalsePositive(domain: String) {
        val clean = AetherAnalyzer.normalize(domain)
        if (clean.isEmpty()) return
        val current = verifiedFalsePositives().toMutableSet()
        current.add(clean)
        store.put(KEY_FALSE_POSITIVES, current.take(500).joinToString("\n"))
        appendAudit("feedback.false_positive domain=$clean")
    }

    fun verifiedFalsePositives(): Set<String> =
        (store.get(KEY_FALSE_POSITIVES) ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isVerifiedFalsePositive(domain: String): Boolean =
        verifiedFalsePositives().contains(AetherAnalyzer.normalize(domain))

    /** Stores a versioned snapshot of the AETHER settings for safe rollback. */
    fun saveSnapshot(snapshot: String) {
        store.put(KEY_SNAPSHOT, snapshot)
        appendAudit("policy.snapshot_saved $snapshot")
    }

    fun rollbackSnapshot(): String? {
        val snapshot = store.get(KEY_SNAPSHOT) ?: return null
        appendAudit("policy.rollback_restored $snapshot")
        return snapshot
    }

    fun auditTrail(): List<String> = (store.get(KEY_AUDIT) ?: "").lines().filter { it.isNotBlank() }

    /** Public, length-bounded audit helper used by the UI for user feedback. */
    fun appendUserAudit(line: String) = appendAudit(line.take(200))

    private fun appendAudit(line: String) {
        val previous = auditTrail()
        val updated = (listOf("${System.currentTimeMillis()} $line") + previous).take(200)
        store.put(KEY_AUDIT, updated.joinToString("\n"))
    }
}
