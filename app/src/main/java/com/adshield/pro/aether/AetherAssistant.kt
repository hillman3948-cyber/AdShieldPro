package com.adshield.pro.aether

/**
 * On-device AETHER assistant: deterministic retrieval + real investigation tool.
 *
 * It is NOT a generative LLM and needs no network. User text is always treated
 * as untrusted data: prompt-injection patterns are detected and ignored; the
 * assistant is read-only, refuses offensive requests, and never executes files
 * or changes protection settings.
 */
class AetherAssistant(
    private val analyzer: AetherAnalyzer,
    private val settings: () -> AetherSettings = { AetherSettings() }
) {
    companion object {
        private val DOMAIN = Regex("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b", RegexOption.IGNORE_CASE)
        private val INJECTION = listOf(
            Regex("ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions", RegexOption.IGNORE_CASE),
            Regex("disregard\\s+(your|the)\\s+(system|previous)\\s+(prompt|instructions)", RegexOption.IGNORE_CASE),
            Regex("(system|developer)\\s*:\\s*", RegexOption.IGNORE_CASE),
            Regex("reveal\\s+(your|the)\\s+(system|developer)\\s+prompt", RegexOption.IGNORE_CASE),
            Regex("disable\\s+(all\\s+)?(protection|blocking|security)", RegexOption.IGNORE_CASE)
        )
        private val OFFENSIVE = Regex(
            "hack\\s+(into|someone|my friend|neighbor|neighbour|wifi)|ddos|" +
                "bypass\\s+(antivirus|2fa|mfa|login)|steal\\s+(password|cookie|session)|" +
                "crack\\s+(wifi|password)|exploit\\s+(a\\s+)?(website|server)",
            RegexOption.IGNORE_CASE
        )
        private val PERMISSION_WORDS = Regex(
            "my own|i own|authorized|authorised|permission|lab|ctf|defensive|learn",
            RegexOption.IGNORE_CASE
        )
        private val INVESTIGATE = Regex(
            "\\b(is|are|check|investigate|analyze|analyse|scan|safe|trust|legit|" +
                "malicious|phish|risk|verify|look up|this link|this url)\\b",
            RegexOption.IGNORE_CASE
        )
    }

    private data class Topic(
        val title: String,
        val keywords: List<String>,
        val beginner: String,
        val intermediate: String,
        val professional: String,
        val steps: List<String>
    )

    private val topics = listOf(
        Topic("Phishing and suspicious links",
            listOf("phish", "scam", "suspicious", "link", "fake login"),
            "Phishing is a message pretending to be a company you trust. Do not click; open the official app yourself or type the known address.",
            "Check the registrable domain (last two labels), not the pretty text before it. Watch for lookalike characters, '@' userinfo and credential words.",
            "Confirmed indicators come from signed feeds with source/timestamp/confidence; heuristic-only hits are suspicious, never proof. Inspect redirect chains and certificates.",
            listOf("Do not enter credentials on the linked page.", "Compare the exact hostname with the known domain.",
                "Use the AETHER Analyze tab to see signals and evidence.")),
        Topic("DNS security and troubleshooting",
            listOf("dns", "resolver", "doh", "dot", "nxdomain", "no internet", "troubleshoot"),
            "DNS turns a name into a number your device connects to. If DNS fails, sites look offline even when Wi-Fi works.",
            "The local VPN answers DNS queries and forwards allowed ones to your chosen DoH/DoT/UDP resolver. Encrypted DNS fails closed instead of silently downgrading.",
            "Inspect RCODE, TTL and answers; compare resolvers; check for private/bogon answers as hijack indicators. Only one Android VPN can be always-on.",
            listOf("Check that protection is active.", "Try another resolver preset under Settings.",
                "If an app breaks, use an approved expiring allowlist entry and report a false positive.")),
        Topic("Home Wi-Fi security",
            listOf("wifi", "wi-fi", "router", "home network", "wpa"),
            "Change the router's default password, use WPA2/WPA3 and put visitors on a guest network.",
            "Disable WPS and remote administration, keep firmware updated and use a trusted DNS resolver.",
            "Segment IoT devices, review UPnP exposure and watch for rogue DHCP/DNS servers. A phone VPN only protects that phone.",
            listOf("Update router firmware from the vendor's official page.", "Use WPA3 with a long unique passphrase.",
                "Turn off WPS and enable a guest network.")),
        Topic("Protecting Android devices",
            listOf("android", "phone", "mobile", "permissions", "play protect"),
            "Install apps from the official store, keep the system updated and check permissions before granting them.",
            "Enable Play Protect, avoid sideloaded APKs, review Accessibility permissions and use DNS filtering for trackers.",
            "Verify APK signatures, use per-app VPN exclusions deliberately and remember Android sandboxing prevents injecting cosmetic filters into other browsers.",
            listOf("Update Android and apps.", "Audit Accessibility and SMS permissions.",
                "Keep Play Protect on and verify any APK's signature.")),
        Topic("Privacy and trackers",
            listOf("privacy", "tracker", "tracking", "ads", "telemetry", "data"),
            "Trackers are small requests telling a company which sites you visit. Blocking their addresses helps but cannot stop a site asking you for data.",
            "AETHER classifies known ad/analytics/telemetry destinations from the bundled signed baseline; choose minimal, balanced or strict privacy levels.",
            "DNS blocking is coarse: first-party endpoints and server-side tracking are unaffected. No HTTPS payload inspection occurs.",
            listOf("Choose your privacy level in the AETHER screen.", "Remove tracking parameters from links you share.",
                "Review app permissions and ad personalisation settings.")),
        Topic("Suspected account compromise",
            listOf("hacked", "compromised", "account", "password reset", "2fa", "mfa"),
            "Change the password from a trusted device, sign out of all sessions and turn on two-step verification.",
            "Check recovery email/phone, active sessions, mail forwarding rules and OAuth grants. Use unique passwords.",
            "Preserve timestamps as evidence, rotate tokens/keys, review audit logs and scope the incident to accounts reusing credentials.",
            listOf("Use a clean device and the official app/site.", "Sign out all sessions and revoke unknown app grants.",
                "Enable MFA and check recovery methods.")),
        Topic("Defensive security and ethical hacking",
            listOf("ethical", "hacking", "pentest", "lab", "learn", "ctf", "defensive"),
            "Ethical hacking means testing systems you own or have written permission to test, then fixing what you find.",
            "Build a local lab with your own VMs and deliberately vulnerable training targets, keep a written scope and practise defensive skills.",
            "Follow a scoped methodology, handle evidence carefully and report findings with remediation steps. AETHER provides defensive labs only and requires an explicit permission confirmation.",
            listOf("Get explicit written permission and define scope.", "Use an isolated lab you own.",
                "Practise detection, hardening and incident response.")),
        Topic("How AETHER AI works",
            listOf("aether", "how does", "capabilities", "limitations", "what can you do"),
            "AETHER checks DNS names against trusted security lists and simple explainable warning signs, then tells you what it found.",
            "It is a versioned rule engine plus this assistant — no opaque cloud model. Confirmed list matches are blocked; lookalike patterns are only flagged.",
            "Detection accuracy is measured on a labeled corpus; candidate rules must pass recall, false-positive and compatibility gates. Feeds are Ed25519-signed and verified. DNS errors fail open.",
            listOf("Use the Analyze tab with a domain or URL.", "Read each signal's source, timestamp and confidence.",
                "See docs/aether/MODULES.md for every module's limitations."))
    )

    fun respond(message: String, level: String = "beginner"): AetherReply {
        val clean = message.trim().take(2000)
        val lvl = if (level in listOf("beginner", "intermediate", "professional")) level else "beginner"
        val injected = INJECTION.any { it.containsMatchIn(clean) }
        val sections = ArrayList<Pair<String, String>>()
        if (injected) {
            sections.add("Prompt-injection notice" to
                "Your message contained text resembling instructions to change my behavior. " +
                    "I treat all user text as data, ignored those instructions, and did not change any setting.")
        }
        if (OFFENSIVE.containsMatchIn(clean) && !PERMISSION_WORDS.containsMatchIn(clean)) {
            sections.add("Boundary" to
                "I only help with defensive security and testing systems you own or have explicit written permission to test. " +
                    "I will not provide instructions to attack, bypass authentication or steal credentials.")
            sections.add("Safe alternative" to
                "Open the Learn tab for defensive labs; each lab requires an explicit ownership/permission confirmation.")
            return buildReply(clean, sections, injected, true, null)
        }
        val target = DOMAIN.find(clean)?.value
        val verdict = if (target != null && INVESTIGATE.containsMatchIn(clean)) {
            val result = runCatching { analyzer.analyze(target, settings()) }
                .getOrElse { AetherAnalyzer.failSafe(target) }
            sections.add("Investigation: $target" to explanation(result, lvl))
            sections.add("Evidence" to
                "Classification: ${result.classification}; risk ${result.risk}/100; " +
                    "confidence ${result.confidence}; ${if (result.confirmed) "confirmed intelligence match" else "no confirmed intelligence match"}. " +
                    "Decision: ${if (result.shouldBlock) "block" else "allow/flag"} (${result.reasonCode}). " +
                    "Signals: ${result.signals.take(4).joinToString(", ") { it.id }.ifEmpty { "none" }}.")
            sections.add("What to do" to result.recommendations.joinToString("\n• ", "• "))
            result
        } else null
        val topic = topics.firstOrNull { t -> t.keywords.any { clean.lowercase().contains(it) } }
            ?: topics.last()
        if (verdict == null) {
            sections.add(topic.title to when (lvl) {
                "intermediate" -> topic.intermediate
                "professional" -> topic.professional
                else -> topic.beginner
            })
            sections.add("Practical steps" to topic.steps.joinToString("\n• ", "• "))
        }
        sections.add("Limitations & safety" to
            "DNS names only; no HTTPS inspection; no universal app-level ad blocking; no offensive actions; " +
                "no guaranteed prevention of unknown threats. This assistant is read-only and cannot change protection policy.")
        return buildReply(clean, sections, injected, false, verdict)
    }

    private fun explanation(v: AetherVerdict, level: String): String =
        v.explanations[level] ?: v.explanations["beginner"] ?: "No explanation available."

    private fun buildReply(
        original: String, sections: List<Pair<String, String>>, injected: Boolean,
        refused: Boolean, verdict: AetherVerdict?
    ): AetherReply {
        val answer = sections.joinToString("\n\n") { (title, text) -> "$title: $text" }
        return AetherReply(answer, sections, injected, refused, verdict)
    }
}
