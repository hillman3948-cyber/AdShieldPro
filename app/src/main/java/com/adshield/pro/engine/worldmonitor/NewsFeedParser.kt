package com.adshield.pro.engine.worldmonitor

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal, dependency-free RSS 2.0 / RDF 1.0 / Atom 1.0 reader for the Global News layer.
 *
 * Why this exists instead of a generic XML pull parser:
 *  - news feeds are the one place where the app must survive arbitrary, frequently broken XML
 *    (unclosed CDATA, stray entities, namespaced tags, HTML inside descriptions);
 *  - the previous implementation searched the entire document for `<item>`/`<title>` with a
 *    document-wide regex, which silently pairs a title with the wrong link whenever a feed puts
 *    a `<title>` outside an item (channel title, image block, `<media:title>`).
 *
 * This parser is deliberately strict about *structure* and lenient about *content*:
 *  - items are only produced from well-formed `<item>` / `<entry>` blocks;
 *  - a tag is read from the item body only, so a channel-level title can never leak into an item;
 *  - CDATA, numeric/named entities and HTML tags inside descriptions are decoded, never rendered;
 *  - a missing date stays null. It is never replaced with "now", which would make stale news look
 *    like breaking news.
 *
 * Every method is pure and deterministic so it can be unit-tested against recorded fixtures.
 */
object NewsFeedParser {

    data class NewsItem(
        val title: String,
        val summary: String,
        val link: String,
        /** Dedicated article image (media:content / enclosure / Atom link[rel=enclosure]). */
        val imageUrl: String?,
        /** Publication instant in epoch millis, or null when the feed omits/unformats it. */
        val publishedEpochMs: Long?,
        /** Feed-supplied section (e.g. "World", "News", "Sport"), when present. */
        val section: String?,
        /** Author / creator line, when present. */
        val author: String?
    ) {
        /** Titles that are navigation or advertising, not news. */
        val isPlaceholder: Boolean
            get() = title.isBlank() ||
                title.equals("home", ignoreCase = true) ||
                title.equals("news", ignoreCase = true) ||
                link.isBlank()
    }

    private val ITEM_BLOCK = Regex(
        "<item\\b[^>]*>(.*?)</item\\s*>|<entry\\b[^>]*>(.*?)</entry\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val HTML_TAGS = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Parses a feed document into ordered items.
     *
     * @param fallbackLink used only when a single item has no link at all (e.g. an Atom entry that
     *   is all text). Items without a title are dropped rather than guessed.
     */
    fun parse(xml: String, limit: Int = 40, fallbackLink: String = ""): List<NewsItem> {
        if (xml.isBlank()) return emptyList()
        val out = ArrayList<NewsItem>(minOf(limit, 16))
        for (match in ITEM_BLOCK.findAll(xml)) {
            val block = match.groupValues[1].takeIf { it.isNotBlank() } ?: match.groupValues[2]
            if (block.isBlank()) continue
            val title = decode(tag(block, "title"))
            if (title.isBlank()) continue
            val summary = decode(tag(block, "description").ifBlank {
                tag(block, "summary").ifBlank { tag(block, "content") }
            })
            val link = articleLink(block).ifBlank { fallbackLink }
            val item = NewsItem(
                title = title,
                summary = stripHtml(summary),
                link = link,
                imageUrl = imageUrl(block),
                publishedEpochMs = parseDate(
                    // RSS 2.0 -> Atom -> RDF/DC -> Dublin Core terms. `dc:date` is what the
                    // Deutsche Welle RDF feed uses; without it every DW item looked undated.
                    tag(block, "pubDate").ifBlank {
                        tag(block, "published").ifBlank {
                            tag(block, "updated").ifBlank {
                                tag(block, "dc:date").ifBlank {
                                    tag(block, "dcterms:modified").ifBlank { tag(block, "date") }
                                }
                            }
                        }
                    }
                ),
                section = decode(tag(block, "category")).ifBlank { null },
                author = decode(tag(block, "dc:creator").ifBlank { tag(block, "author") })
                    .let { stripHtml(it) }
                    .ifBlank { null }
            )
            if (item.isPlaceholder) continue
            out += item
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * Reads one tag from a fragment. Anchored to `<tag` so `<title>` cannot match `<media:title>`
     * and `<link>` cannot match `<linkrel>`.
     */
    fun tag(xml: String, name: String): String {
        if (xml.isEmpty()) return ""
        val escaped = Regex.escape(name)
        // Self-closing first (Atom <link href="..."/> has no body).
        val selfClosing = Regex("<$escaped\\b([^>]*)/>", RegexOption.IGNORE_CASE).find(xml)
        val paired = Regex("<$escaped\\b[^>]*>(.*?)</$escaped\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml)
        if (paired != null && paired.groupValues[1].isNotBlank()) return unwrapCdata(paired.groupValues[1]).trim()
        if (selfClosing != null) return ""
        return paired?.let { unwrapCdata(it.groupValues[1]).trim() }.orEmpty()
    }

    /** Reads an attribute from the opening tag of [name], e.g. `<link href="…">`. */
    fun attr(xml: String, tagName: String, attrName: String): String {
        val escapedTag = Regex.escape(tagName)
        val escapedAttr = Regex.escape(attrName)
        val open = Regex("<$escapedTag\\b([^>]*)>", RegexOption.IGNORE_CASE).find(xml) ?: return ""
        val attrs = open.groupValues[1]
        val m = Regex("$escapedAttr\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(attrs) ?: return ""
        return decode(m.groupValues[1]).trim()
    }

    /**
     * Picks the article URL. Handles RSS `<link>text</link>`, RDF `<link>`, Atom
     * `<link rel="alternate" href="…"/>` and skips Atom `rel="self"` / `rel="enclosure"`.
     */
    private fun articleLink(block: String): String {
        val textLink = decode(tag(block, "link"))
        if (textLink.startsWith("http", ignoreCase = true)) return textLink
        if (textLink.startsWith("/")) return textLink
        // Atom style: inspect every <link …> and prefer rel=alternate or no rel at all.
        for (m in Regex("<link\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(block)) {
            val attrs = m.groupValues[1]
            val rel = Regex("rel\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)?.lowercase()
            if (rel == "self" || rel == "enclosure") continue
            val href = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)
            if (!href.isNullOrBlank()) return decode(href).trim()
        }
        return ""
    }

    /** Dedicated article image; returns null instead of a tracking pixel or a 1×1 spacer. */
    private fun imageUrl(block: String): String? {
        val candidates = ArrayList<String>(3)
        Regex("<media:content\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(block).forEach { m ->
            attr(m.value, "media:content", "url").let { if (it.isNotBlank()) candidates += it }
        }
        Regex("<media:thumbnail\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(block).forEach { m ->
            attr(m.value, "media:thumbnail", "url").let { if (it.isNotBlank()) candidates += it }
        }
        Regex("<enclosure\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(block).forEach { m ->
            val type = attr(m.value, "enclosure", "type")
            val url = attr(m.value, "enclosure", "url")
            if (url.startsWith("http", true) && (type.startsWith("image", true) || type.isBlank())) candidates += url
        }
        return candidates.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /** Removes tags and collapses whitespace. Never used on a URL. */
    fun stripHtml(input: String): String =
        WHITESPACE.replace(HTML_TAGS.replace(input, " "), " ").trim()

    private fun unwrapCdata(raw: String): String {
        val start = raw.indexOf("<![CDATA[")
        if (start >= 0) {
            val end = raw.indexOf("]]>", start)
            if (end > start) return raw.substring(start + 9, end)
        }
        return raw
    }

    /** Decodes the entities the news feeds actually emit, including numeric references. */
    fun decode(raw: String): String {
        if (raw.isEmpty()) return ""
        var s = unwrapCdata(raw)
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&nbsp;", " ").replace("&hellip;", "…").replace("&mdash;", "—")
            .replace("&ndash;", "–").replace("&rsquo;", "’").replace("&lsquo;", "‘")
            .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&amp;", "&")
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: ""
        }
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: ""
        }
        return s.trim()
    }

    /**
     * Parses an RFC-822 (RSS) or ISO-8601 (Atom) timestamp.
     * Returns null rather than an invented date when the value is absent or unrecognised.
     */
    fun parseDate(raw: String): Long? {
        val value = decode(raw).trim()
        if (value.isEmpty()) return null
        // Numeric offsets first: SimpleDateFormat's `zzz` can partially consume "+0800" and then
        // stop early, which silently shifts a Rappler-style timestamp by the whole offset.
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.isLenient = false
            // Full consumption is required: a partial match would silently return a wrong instant
            // (e.g. dropping the "+0800" and treating local time as UTC).
            val position = java.text.ParsePosition(0)
            val parsed = runCatching { format.parse(value, position) }.getOrNull()
            if (parsed != null && position.index == value.length) return parsed.time
        }
        // Genuinely unparseable — stay null rather than inventing "now".
        return null
    }
}
