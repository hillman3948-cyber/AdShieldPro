package com.adshield.pro.aether

/**
 * On-device security education: lessons at three levels, real quiz grading, and
 * defensive labs locked behind an explicit ownership/permission confirmation.
 */
data class AetherLesson(
    val id: String,
    val title: String,
    val minutes: Int,
    val levels: Map<String, String>,
    val questions: List<AetherQuestion>
)

data class AetherQuestion(
    val text: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

data class AetherQuizResult(val score: Int, val total: Int, val passed: Boolean, val feedback: List<String>)

class AetherEducation {
    companion object {
        const val LAB_PERMISSION_PHRASE = "I OWN OR HAVE EXPLICIT PERMISSION TO TEST THESE SYSTEMS"
    }

    val lessons = listOf(
        AetherLesson("basics", "Cybersecurity foundations", 8, mapOf(
            "beginner" to "Security protects your accounts, devices and data: confidentiality, integrity and availability. Most attacks target passwords and trust.",
            "intermediate" to "Start with a threat model, then layer controls: unique passwords + MFA, updates, backups, least privilege and DNS filtering.",
            "professional" to "Map controls to a framework, define trust boundaries and residual risk, and measure detection precision/recall rather than assuming a control works."
        ), listOf(
            AetherQuestion("Which control best limits damage from a leaked password?",
                listOf("Longer Wi-Fi name", "Multi-factor authentication", "Dark mode"), 1,
                "MFA requires a second factor so a password alone is not enough."),
            AetherQuestion("What does least privilege mean?",
                listOf("Give every app admin rights", "Give only the access needed", "Never update"),
                1, "It limits what a compromised account or app can reach.")
        )),
        AetherLesson("dns", "DNS security", 9, mapOf(
            "beginner" to "DNS is the internet's phone book. If someone changes it, you can be sent to a fake site. Encrypted DNS hides lookups from your network.",
            "intermediate" to "DoH (RFC 8484) and DoT (RFC 7858) protect transport; DNSSEC validates records where enabled. Encrypted DNS should fail closed, not downgrade to plaintext.",
            "professional" to "Check RCODE, TTL, answer consistency and bogon answers as hijack indicators; sign and verify intelligence feeds; fail open on engine errors."
        ), listOf(
            AetherQuestion("What should a fail-closed encrypted DNS client do when DoH fails?",
                listOf("Silently use plaintext DNS", "Return SERVFAIL", "Disable protection"), 1,
                "Silent downgrade leaks queries; SERVFAIL keeps the chosen protocol."),
            AetherQuestion("What can a DNS-only filter see?",
                listOf("HTTPS page contents", "Domain names", "Typed passwords"), 1,
                "DNS filtering observes name resolution, not encrypted content.")
        )),
        AetherLesson("phishing", "Recognising phishing", 8, mapOf(
            "beginner" to "A phishing page copies a real login screen. Always check the exact address before typing a password.",
            "intermediate" to "Look for '@' userinfo, brand names in subdomains, punycode lookalikes, suspicious TLDs and long redirect chains. Individually these are weak signals.",
            "professional" to "Distinguish confirmed intelligence from heuristics; require source, timestamp, confidence and evidence; measure false positives from verified feedback."
        ), listOf(
            AetherQuestion("In https://paypal.com@evil.example/login, which site receives your data?",
                listOf("paypal.com", "evil.example", "Neither"), 1,
                "Text before '@' is userinfo; the real host is after it."),
            AetherQuestion("Why flag rather than auto-block a lookalike domain?",
                listOf("Lookalikes are harmless", "Heuristics can produce false positives", "Blocking is impossible"), 1,
                "Only confirmed intelligence is auto-blocked by default.")
        )),
        AetherLesson("wifi", "Securing home Wi-Fi", 7, mapOf(
            "beginner" to "Change the router admin password, use WPA2/WPA3 and keep firmware updated.",
            "intermediate" to "Disable WPS and remote administration, create a guest network and use a trusted DNS resolver.",
            "professional" to "Segment VLANs, review UPnP exposure, watch for rogue DHCP/DNS servers and centralise DNS logging."
        ), listOf(AetherQuestion("Which setting should usually be disabled?",
            listOf("WPA3", "WPS", "Guest network"), 1, "The WPS PIN is brute-forceable."))),
        AetherLesson("incident", "Incident response basics", 9, mapOf(
            "beginner" to "If an account is hacked: change the password from a clean device, sign out everywhere and enable two-step verification.",
            "intermediate" to "Check recovery methods, active sessions, forwarding rules and OAuth grants; document times and messages.",
            "professional" to "Preserve evidence, rotate tokens, scope lateral impact, remediate root cause and improve detection."
        ), listOf(AetherQuestion("What should you do FIRST after noticing a compromised account?",
            listOf("Delete all apps", "Use a clean device, change password and sign out sessions", "Post publicly"), 1,
            "Containment first: revoke the attacker's access from a trusted device."))),
        AetherLesson("ethical", "Ethical hacking & defensive labs", 8, mapOf(
            "beginner" to "Only test systems you own or have written permission to test. Use a private lab.",
            "intermediate" to "Write down scope and rules of engagement, practise on deliberately vulnerable local targets and focus on detection and remediation.",
            "professional" to "Follow a scoped methodology, handle evidence carefully, report findings with reproduction steps and retest after fixes."
        ), listOf(AetherQuestion("Before testing a system, what is mandatory?",
            listOf("A fast laptop", "Ownership or explicit written permission and scope", "A public IP"), 1,
            "Authorisation and scope define legal, safe testing.")))
    )

    val labs = listOf(
        "dns_lab" to "Defensive DNS observation lab: compare real answers and TTLs on domains you own, then document what you can and cannot conclude.",
        "audit_lab" to "Audit & rollback lab: create an approved expiring allowlist entry, review the activity log, then restore a previous policy snapshot.",
        "corpus_lab" to "Detection accuracy lab: explain why held-out real indicators and false-positive labels matter more than indicators already in the feed."
    )

    fun lesson(id: String): AetherLesson? = lessons.firstOrNull { it.id == id }

    fun grade(id: String, answers: List<Int>): AetherQuizResult {
        val lesson = lesson(id) ?: throw IllegalArgumentException("unknown lesson id")
        val feedback = ArrayList<String>()
        var score = 0
        lesson.questions.forEachIndexed { index, question ->
            val given = answers.getOrNull(index)
            if (given == question.correctIndex) score++
            feedback.add("Q${index + 1}: ${if (given == question.correctIndex) "correct" else "incorrect"} — ${question.explanation}")
        }
        return AetherQuizResult(score, lesson.questions.size, score == lesson.questions.size, feedback)
    }

    fun unlockLab(id: String, phrase: String): Pair<Boolean, String> {
        if (!labs.any { it.first == id }) return false to "Unknown lab."
        if (phrase.trim().uppercase() != LAB_PERMISSION_PHRASE) {
            return false to "Explicit ownership/permission confirmation is required before lab steps are shown. Only test systems you own or have permission to test."
        }
        val description = labs.first { it.first == id }.second
        return true to "$description\n\nDefensive, local-only steps. No offensive tooling is provided."
    }
}
