package com.adshield.pro.engine.filter

/**
 * Video Safety — the policy that keeps playback working while third-party video
 * adverts stay blocked.
 *
 * The problem this solves is specific. A DNS filter cannot tell an in-stream
 * advert from the media itself, so the rules that "block harder" are the same
 * rules that produce "the player opens but the video never starts":
 *
 *  - entity-level rules (`||googlevideo.com^`, `||google.com^`) remove the media
 *    CDN together with every advert on it;
 *  - substring wildcards (`*adserver*`, `*telemetry.*`) match a substring of any
 *    hostname, including legitimate per-node CDN names such as
 *    `srv-adserver-3.googlevideo.com`;
 *  - blanket rules for shared edge zones (`akamaized.net`, `cloudfront.net`, ...)
 *    take playback (and downloads) down for unrelated services while catching no
 *    third-party ad server at all.
 *
 * The policy therefore has two halves, and both matter:
 *
 *  1. [isVideoUnsafeRule] rejects those three rule shapes wherever a list is
 *     parsed, so an aggressive feed can never take playback down.
 *  2. [isProtectedPlaybackHost] exempts media delivery hosts from being blocked
 *     by *heuristic* reasons, while [isAdvertDeliveryHost] keeps the actual
 *     third-party ad infrastructure blocked (pubads.g.doubleclick.net,
 *     static.doubleclick.net, s0.2mdn.net, imasdk.googleapis.com, ads.twitch.tv).
 *
 * Guessing is not acceptable in either half: a host that is not on the media list
 * is never "protected", and an advert slot on a media domain is never exempt.
 */
object DnsMediaPlaybackGuard {

    /** Registrable domains that carry playback. Lowest-precedence safety net only. */
    val PROTECTED_REGISTRABLE_DOMAINS: Set<String> = setOf(
        // Google / YouTube media delivery and player assets
        "googlevideo.com", "youtube.com", "youtu.be", "ytimg.com", "ggpht.com",
        "youtube-nocookie.com", "youtubei.googleapis.com",
        // Netflix
        "netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net",
        // Amazon Prime Video
        "primevideo.com", "amazonvideo.com", "aiv-cdn.net", "aiv-delivery.net", "media-amazon.com",
        // Disney+ / Hotstar / Hulu / HBO Max / Paramount+
        "disneyplus.com", "dssott.com", "bamgrid.com", "hotstar.com", "hulu.com",
        "hbomax.com", "max.com", "paramountplus.com",
        // Twitch
        "twitch.tv", "ttvnw.net", "jtvnw.net",
        // Meta / Instagram video
        "fbcdn.net", "cdninstagram.com", "instagram.com",
        // TikTok
        "tiktok.com", "tiktokcdn.com", "tiktokv.com", "tiktokcdn-us.com",
        "ibyteimg.com", "muscdn.com", "byteoversea.com",
        // Vimeo / Dailymotion / Rumble
        "vimeo.com", "vimeocdn.com", "dailymotion.com", "dmcdn.net", "rumble.com",
        // Music playback (a broken stream is the same user-facing failure)
        "spotify.com", "scdn.co", "soundcloud.com", "sndcdn.com", "deezer.com", "dzcdn.net",
        // Live / conferencing media
        "zoom.us", "meet.google.com", "teams.microsoft.com", "teams.live.com", "webex.com"
    )

    /** Advert slots on third-party delivery hosts. Always blockable. */
    val ADVERT_DELIVERY_HOSTS: Set<String> = setOf(
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "googleads.g.doubleclick.net",
        "static.doubleclick.net",
        "stats.g.doubleclick.net",
        "ad.doubleclick.net",
        "s0.2mdn.net",
        "s1.2mdn.net",
        "imasdk.googleapis.com",
        "tpc.googlesyndication.com",
        "pagead2.googlesyndication.com",
        "static.ads-twitter.com",
        "cdn.adsafeprotected.com"
    )

    /** Shared edge/CDN zones: a blanket rule for one of these is always wrong. */
    val SHARED_CDN_ZONES: Set<String> = setOf(
        "akamaized.net", "akamaihd.net", "akamai.net", "cloudfront.net",
        "fastly.net", "fastlylb.net", "edgekey.net", "edgesuite.net", "llnwd.net",
        "azureedge.net", "b-cdn.net", "kwcdn.com", "cdngslb.com", "w.cdngslb.com",
        "cloudflare.net"
    )

    /** Entity-level domains: never worth blocking as a whole. */
    val ENTITY_LEVEL_DOMAINS: Set<String> = setOf(
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
        "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com",
        "facebook.com", "fbcdn.net", "instagram.com", "whatsapp.net", "messenger.com",
        "microsoft.com", "live.com", "office.com", "office.net", "sharepoint.com",
        "microsoftonline.com", "windows.com",
        "apple.com", "icloud.com",
        "amazon.com", "amazonaws.com", "cloudfront.net", "akamaized.net", "akamaihd.net",
        "fastly.net", "netflix.com", "tiktok.com", "twitch.tv", "vimeo.com",
        "dailymotion.com", "spotify.com", "soundcloud.com",
        "twitter.com", "x.com", "linkedin.com", "reddit.com", "snapchat.com",
        "telegram.org", "paypal.com", "stripe.com", "shopify.com",
        "github.com", "githubusercontent.com"
    )

    /** Labels that describe the player/stream rather than an advert slot. */
    private val PLAYER_LABEL_TOKENS = listOf(
        "video", "vod", "media", "stream", "player", "playback",
        "manifest", "m3u8", "mpd", "segment", "hls", "dash", "live",
        "drm", "widevine", "playready", "fairplay", "license",
        "redirector", "content", "cdn"
    )

    /** Advert intent tokens. A whole-label match marks advert delivery. */
    private val ADVERT_LABEL_TOKENS = setOf(
        "ad", "ads", "adserver", "adservice", "advert", "advertising", "adv",
        "doubleclick", "imasdk", "prebid", "vast", "vmap", "adunit",
        "beacon", "analytics", "telemetry", "tracking", "tracker", "metrics",
        "pixel", "sponsor", "sponsored", "promo", "banner", "preroll",
        "midroll", "postroll", "interstitial",
        // Telemetry/log collectors published as subdomains of a media domain: the
        // names upstream lists deliberately block (views.hulu.com,
        // log.byteoversea.com, mcs.tiktokv.com, smetrics.disneyplus.com, ...).
        "log", "logs", "log2", "logger", "logging", "gplog", "maplog", "eventlog",
        "eventlogger", "eventgateway", "events", "mon", "monitoring", "mcs", "tsa",
        "smetrics", "perfmetrics", "customerevents", "telemetry2", "pulse", "tags",
        "promoted", "adeventtracker", "aet", "views", "dradis", "stats"
    )

    data class Verdict(
        val domain: String,
        /** True when blocking this host would break playback. */
        val isPlaybackInfrastructure: Boolean,
        /** True when this host is third-party advert delivery. */
        val isAdvertDelivery: Boolean,
        val reason: String
    )

    data class UnsafeRule(val code: String, val pattern: String, val detail: String)

    /** Bare lowercase hostname, or "" when the input cannot be a hostname. */
    fun normalizeDomain(input: String): String {
        var s = input.trim().lowercase()
        s = s.removePrefix("http://").removePrefix("https://")
        s = s.removePrefix("//")
        if (s.contains('@')) s = s.substringAfterLast('@')
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.trimStart('.').trimEnd('.')
        if (s.isEmpty() || s.length > 253) return ""
        if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return ""
        val labels = s.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return ""
        return s
    }

    private val TWO_LEVEL_SUFFIXES = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob",
        "nic", "res", "sch", "mil", "info", "biz"
    )

    fun registrableDomain(input: String): String {
        val name = normalizeDomain(input)
        if (name.isEmpty()) return ""
        val labels = name.split('.')
        if (labels.size <= 2) return name
        val last = labels.last()
        val penultimate = labels[labels.size - 2]
        return if (last.length == 2 && TWO_LEVEL_SUFFIXES.contains(penultimate)) {
            labels.takeLast(3).joinToString(".")
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    private fun matchesHostOrSubdomain(domain: String, entry: String): Boolean =
        domain == entry || domain.endsWith(".$entry")

    /**
     * The protected media zone a host belongs to (`googlevideo.com`), or null.
     *
     * Needed by the evaluator: the Video Safety exemption may only apply when the
     * *blocking rule is the media domain itself*. A rule for a specific host under
     * the zone (`log.byteoversea.com`, `views.hulu.com`) is a deliberate block of an
     * ad or telemetry endpoint and must survive.
     */
    fun protectedZoneFor(input: String): String? {
        val domain = normalizeDomain(input)
        if (domain.isEmpty()) return null
        return PROTECTED_REGISTRABLE_DOMAINS.firstOrNull { matchesHostOrSubdomain(domain, it) }
    }

    /** True when the host carries an advert-intent *label* (`ads`, `preroll`, ...). */
    fun hasAdvertIntent(domain: String): Boolean {
        val name = normalizeDomain(domain)
        if (name.isEmpty()) return false
        return name.split('.').any { label ->
            ADVERT_LABEL_TOKENS.contains(label) ||
                label.split('-', '_').any { ADVERT_LABEL_TOKENS.contains(it) } ||
                // Separators are not significant in a hostname: pre-roll == preroll.
                ADVERT_LABEL_TOKENS.contains(label.replace("-", "").replace("_", ""))
        }
    }

    /** True when the host is third-party advert delivery. Always blockable. */
    fun isAdvertDeliveryHost(input: String): Boolean {
        val domain = normalizeDomain(input)
        if (domain.isEmpty()) return false
        if (ADVERT_DELIVERY_HOSTS.any { matchesHostOrSubdomain(domain, it) }) return true
        val registrable = registrableDomain(domain)
        if (registrable.isEmpty()) return false
        if (!PROTECTED_REGISTRABLE_DOMAINS.contains(registrable)) return false
        // An advert slot published as a subdomain of a media domain (ads.twitch.tv).
        // Compound node names (srv-adserver-3.googlevideo.com) are NOT advert slots.
        val suffix = ".$registrable"
        if (!domain.endsWith(suffix)) return false
        val subLabels = domain.removeSuffix(suffix).split('.')
        return subLabels.any { label ->
            if (ADVERT_LABEL_TOKENS.contains(label)) return@any true
            if (ADVERT_LABEL_TOKENS.contains(label.replace("-", "").replace("_", ""))) return@any true
            // `ads-service`, `log-gateway`: lead token only, so a per-node name like
            // `srv-adserver-3.googlevideo.com` is not caught by its middle segment.
            label.contains('-') && ADVERT_LABEL_TOKENS.contains(label.split('-').first())
        }
    }

    /**
     * True when blocking this host would remove the video rather than an advert.
     * Advert delivery always wins over this exemption.
     */
    fun isProtectedPlaybackHost(input: String): Boolean {
        val domain = normalizeDomain(input)
        if (domain.isEmpty()) return false
        if (isAdvertDeliveryHost(domain)) return false
        val registrable = registrableDomain(domain)
        if (registrable.isEmpty()) return false
        return PROTECTED_REGISTRABLE_DOMAINS.contains(registrable)
    }

    fun classify(input: String): Verdict {
        val domain = normalizeDomain(input)
        if (domain.isEmpty()) {
            return Verdict("", false, false, "Input is not a hostname.")
        }
        if (isAdvertDeliveryHost(domain)) {
            return Verdict(
                domain, isPlaybackInfrastructure = false, isAdvertDelivery = true,
                reason = "Advert delivery host: blocking it removes a video advert, not the video."
            )
        }
        val registrable = registrableDomain(domain)
        if (registrable.isNotEmpty() && PROTECTED_REGISTRABLE_DOMAINS.contains(registrable)) {
            val labels = domain.split('.')
            val playerNamed = labels.any { label -> PLAYER_LABEL_TOKENS.any { label.contains(it) } }
            return Verdict(
                domain, isPlaybackInfrastructure = true, isAdvertDelivery = false,
                reason = if (playerNamed) {
                    "Player/stream host on $registrable; blocking it stops playback."
                } else {
                    "Media delivery domain ($registrable); blocking it removes the stream, not the advert."
                }
            )
        }
        return Verdict(domain, false, hasAdvertIntent(domain), "Not a protected media host.")
    }

    /** Strips adblock syntax down to the bare pattern the engines compile. */
    fun patternOf(rule: String): String {
        var body = rule.trim()
        if (body.isEmpty() || body.startsWith("!") || body.startsWith("#")) return ""
        if (body.startsWith("@@")) body = body.removePrefix("@@").trim()
        body = body.removePrefix("||").removePrefix("|")
        body = body.substringBefore('^').substringBefore('$')
        return body.trim().lowercase()
    }

    /**
     * Explains why a rule would break playback, or returns null when the rule is
     * safe. Feed and user rules both pass through this check.
     */
    fun explainUnsafe(rule: String): UnsafeRule? {
        val pattern = patternOf(rule)
        if (pattern.isEmpty()) return null

        if (pattern.contains('*')) {
            val literal = pattern.replace("*", "")
            if (literal.length < 4) {
                return UnsafeRule(
                    "WILDCARD_TOO_BROAD", pattern,
                    "Wildcard \"$pattern\" matches almost every host and would break playback."
                )
            }
            if (!pattern.startsWith("*.")) {
                val suffix = pattern.substringAfterLast('*')
                val suffixIsDomain = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
                    .matches(suffix.removePrefix("."))
                if (!suffixIsDomain) {
                    return UnsafeRule(
                        "SUBSTRING_WILDCARD", pattern,
                        "Wildcard \"$pattern\" matches a substring of a hostname, not a DNS label, " +
                            "so it also matches legitimate player and CDN hosts."
                    )
                }
            }
            val literalDomain = registrableDomain(literal)
            if (literalDomain.isNotEmpty() &&
                PROTECTED_REGISTRABLE_DOMAINS.any { matchesHostOrSubdomain(literalDomain, it) }
            ) {
                return UnsafeRule(
                    "WILDCARD_ON_MEDIA_DOMAIN", pattern,
                    "Wildcard \"$pattern\" applies to a media delivery domain ($literalDomain)."
                )
            }
            return null
        }

        val domain = normalizeDomain(pattern)
        if (domain.isEmpty()) return null

        if (SHARED_CDN_ZONES.contains(domain)) {
            return UnsafeRule(
                "SHARED_CDN_BLANKET", domain,
                "\"$domain\" is a shared CDN zone. Blocking the whole zone breaks playback for " +
                    "unrelated services; block the specific host inside the zone instead."
            )
        }
        if (ENTITY_LEVEL_DOMAINS.contains(domain)) {
            return UnsafeRule(
                "ENTITY_LEVEL_RULE", domain,
                "\"$domain\" is an entity-level domain: blocking it removes logins, updates and " +
                    "media delivery along with any advert. Block the specific advert host instead."
            )
        }
        return null
    }

    fun isVideoUnsafeRule(rule: String): Boolean = explainUnsafe(rule) != null
}
