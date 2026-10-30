package app.tvcouchbrowser

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * A small subset of an Adblock Plus / uBlock Origin filter engine, sized for a 1 GB TV.
 *
 * Supported: `||host^` and `||host/path` network rules (with `$third-party` / `$1p` / `$3p`),
 * `@@` exceptions of the same shapes, `@@||host^$generichide|elemhide`, and standard-CSS cosmetic
 * rules (`##`, `domain##`, `#@#`). Everything else (regex rules, `$domain=`, `$redirect`, scriptlets,
 * procedural selectors) is skipped. Lists are downloaded and parsed on a background thread.
 */
class AdBlocker(private val context: Context) {

    /**
     * Hosts as sorted 64-bit FNV-1a hashes with a flag byte each: loads from the cache as one array
     * and needs no String objects, which matters on a slow 32-bit TV CPU.
     */
    class HostTable(val hashes: LongArray, val flags: ByteArray) {
        operator fun get(host: String): Int? {
            val i = java.util.Arrays.binarySearch(hashes, hash(host))
            return if (i >= 0) flags[i].toInt() else null
        }

        companion object {
            val EMPTY = HostTable(LongArray(0), ByteArray(0))

            fun hash(s: String): Long {
                var h = -0x340d631b7bdddcdbL
                for (c in s) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
                return h
            }

            fun of(map: Map<String, Int>): HostTable {
                val pairs = map.entries.map { hash(it.key) to it.value }.sortedBy { it.first }
                return HostTable(LongArray(pairs.size) { pairs[it].first }, ByteArray(pairs.size) { pairs[it].second.toByte() })
            }
        }
    }

    class Filters {
        // filled while parsing, then frozen into the tables below
        val blockHostMap = HashMap<String, Int>(80_000)
        val allowHostMap = HashMap<String, Int>(4_000)
        // host -> flags (ANY, FIRST, THIRD) for `||host^` rules; a host and all its subdomains match.
        var blockHosts = HostTable.EMPTY
        var allowHosts = HostTable.EMPTY
        // host -> list of path prefixes for `||host/path` rules.
        var blockPaths = HashMap<String, MutableList<String>>(8_000)
        var allowPaths = HashMap<String, MutableList<String>>(1_000)
        val noCosmeticHosts = HashSet<String>()
        val noGenericHosts = HashSet<String>()
        // Generic hiding: selectors keyed by their leading class or id, plus ones without such a key.
        val genericByToken = HashMap<String, MutableList<String>>(20_000)
        val genericComplex = ArrayList<String>()
        val specific = HashMap<String, MutableList<String>>(20_000)
        val specificExceptions = HashMap<String, MutableSet<String>>(4_000)
        val genericExceptions = HashSet<String>()
        var ruleCount = 0
    }

    private val filters = AtomicReference<Filters?>(null)
    private val worker = Executors.newSingleThreadExecutor()
    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("adblock", Context.MODE_PRIVATE)

    val ready get() = filters.get() != null
    val ruleCount get() = filters.get()?.ruleCount ?: 0
    val lastUpdated get() = prefs.getLong("updated", 0L)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    fun isAllowlisted(host: String?) =
        host != null && prefs.getStringSet("allow", emptySet())!!.contains(host.removePrefix("www."))

    fun setAllowlisted(host: String, allowed: Boolean) {
        val set = HashSet(prefs.getStringSet("allow", emptySet())!!)
        if (allowed) set.add(host.removePrefix("www.")) else set.remove(host.removePrefix("www."))
        prefs.edit().putStringSet("allow", set).apply()
    }

    /** Loads the cached lists, downloading them first if missing or older than [MAX_AGE_MS]. */
    fun start(onDone: (String) -> Unit = {}) = worker.execute {
        val stale = System.currentTimeMillis() - lastUpdated > MAX_AGE_MS
        val missing = LISTS.any { !File(dir, it.first).exists() }
        if (filters.get() == null && !missing) load()
        onDone(if (missing || stale) download() else "Filter lists loaded")
    }

    fun updateNow(onDone: (String) -> Unit) = worker.execute { onDone(download()) }

    private fun download(): String {
        var ok = 0
        for ((name, url) in LISTS) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                val tmp = File(dir, "$name.tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() > 1000) { tmp.renameTo(File(dir, name)); ok++ }
            } catch (e: Exception) {
                Log.w(TAG, "download $url failed: $e")
            }
        }
        if (ok > 0) prefs.edit().putLong("updated", System.currentTimeMillis()).apply()
        load()
        return if (ok == LISTS.size) "Filter lists updated" else "Updated $ok of ${LISTS.size} filter lists"
    }

    private val cache = File(dir, "parsed.bin")

    /** Loads the parsed cache if it is newer than every list; otherwise parses the lists and rewrites it. */
    private fun load() {
        val t = System.currentTimeMillis()
        val newest = LISTS.maxOf { File(dir, it.first).lastModified() }
        var f = if (cache.exists() && cache.lastModified() >= newest) {
            try { readCache() } catch (e: Exception) { Log.w(TAG, "cache unreadable: $e"); null }
        } else null
        val fromCache = f != null
        if (f == null) {
            f = Filters()
            for ((name, _) in LISTS) {
                val file = File(dir, name)
                if (file.exists()) file.bufferedReader().useLines { lines -> lines.forEach { parse(f, it) } }
            }
            f.blockHosts = HostTable.of(f.blockHostMap); f.blockHostMap.clear()
            f.allowHosts = HostTable.of(f.allowHostMap); f.allowHostMap.clear()
            try { writeCache(f) } catch (e: Exception) { Log.w(TAG, "cache not written: $e"); cache.delete() }
        }
        filters.set(f)
        Log.i(TAG, "loaded ${f.ruleCount} rules in ${System.currentTimeMillis() - t} ms (cache=$fromCache)")
    }

    private fun writeCache(f: Filters) {
        val tmp = File(dir, "parsed.tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(CACHE_VERSION); o.writeInt(f.ruleCount)
            fun table(t: HostTable) {
                o.writeInt(t.hashes.size)
                val buf = ByteBuffer.allocate(t.hashes.size * 8); buf.asLongBuffer().put(t.hashes)
                o.write(buf.array()); o.write(t.flags)
            }
            fun lists(m: Map<String, List<String>>) {
                o.writeInt(m.size); m.forEach { (k, v) -> o.writeUTF(k); o.writeInt(v.size); v.forEach(o::writeUTF) }
            }
            fun strs(c: Collection<String>) { o.writeInt(c.size); c.forEach(o::writeUTF) }
            table(f.blockHosts); table(f.allowHosts); lists(f.blockPaths); lists(f.allowPaths)
            strs(f.noCosmeticHosts); strs(f.noGenericHosts); lists(f.genericByToken); strs(f.genericComplex)
            lists(f.specific); lists(f.specificExceptions.mapValues { it.value.toList() }); strs(f.genericExceptions)
        }
        tmp.renameTo(cache)
    }

    private fun readCache(): Filters? = DataInputStream(BufferedInputStream(cache.inputStream(), 1 shl 16)).use { i ->
        if (i.readInt() != CACHE_VERSION) return null
        val net = Filters()
        net.ruleCount = i.readInt()
        fun table(): HostTable {
            val n = i.readInt()
            val bytes = ByteArray(n * 8); i.readFully(bytes)
            val hashes = LongArray(n); ByteBuffer.wrap(bytes).asLongBuffer().get(hashes)
            val flags = ByteArray(n); i.readFully(flags)
            return HostTable(hashes, flags)
        }
        fun <C : MutableCollection<String>> lists(m: MutableMap<String, C>, make: (Int) -> C) {
            repeat(i.readInt()) {
                val k = i.readUTF(); val n = i.readInt(); val c = make(n)
                repeat(n) { c.add(i.readUTF()) }; m[k] = c
            }
        }
        fun strs(c: MutableCollection<String>) { repeat(i.readInt()) { c.add(i.readUTF()) } }
        net.blockHosts = table(); net.allowHosts = table()
        lists(net.blockPaths) { ArrayList(it) }; lists(net.allowPaths) { ArrayList(it) }
        // Network blocking goes live now; cosmetic hiding follows when the rest is read.
        filters.set(net)
        Log.i(TAG, "network rules ready")
        val f = Filters().apply {
            ruleCount = net.ruleCount; blockHosts = net.blockHosts; allowHosts = net.allowHosts
            blockPaths = net.blockPaths; allowPaths = net.allowPaths
        }
        strs(f.noCosmeticHosts); strs(f.noGenericHosts)
        lists(f.genericByToken) { ArrayList(it) }; strs(f.genericComplex)
        lists(f.specific) { ArrayList(it) }; lists(f.specificExceptions) { HashSet(it) }; strs(f.genericExceptions)
        f
    }

    // ---- parsing ------------------------------------------------------------------------------

    private fun parse(f: Filters, raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        var i = line.indexOf('#')
        while (i >= 0 && i < line.length - 1) {
            when {
                line[i + 1] == '#' -> { parseCosmetic(f, line, i, "##"); return }
                line.startsWith("#@#", i) -> { parseCosmetic(f, line, i, "#@#"); return }
                // #?#, #$#, #%#, #@?#, #@$#: procedural, CSS-injection and script rules are not supported
                line.startsWith("#?#", i) || line.startsWith("#$#", i) || line.startsWith("#%#", i) ||
                    line.startsWith("#@?#", i) || line.startsWith("#@$#", i) -> return
            }
            i = line.indexOf('#', i + 1)
        }
        parseNetwork(f, line)
    }

    private fun parseCosmetic(f: Filters, line: String, at: Int, sep: String) {
        if (sep != "##" && sep != "#@#") return
        val selector = line.substring(at + sep.length).trim()
        if (selector.isEmpty() || selector.startsWith("+js") || selector.startsWith("^") ||
            (selector.indexOf(':') >= 0 && PROCEDURAL.containsMatchIn(selector))) return
        val domains = line.substring(0, at).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val exception = sep == "#@#"
        if (domains.isEmpty()) {
            if (exception) f.genericExceptions.add(selector) else addGeneric(f, selector)
            f.ruleCount++
            return
        }
        for (d in domains) {
            if (d.startsWith("~") || d.endsWith(".*") || d.contains('/')) continue
            if (exception) f.specificExceptions.getOrPut(d) { HashSet() }.add(selector)
            else f.specific.getOrPut(d) { ArrayList(2) }.add(selector)
        }
        f.ruleCount++
    }

    private fun addGeneric(f: Filters, selector: String) {
        // Key by a leading ".class" or "#id" so a page only gets the selectors its elements could match.
        var end = 0
        if (selector.length > 1 && (selector[0] == '.' || selector[0] == '#') &&
            (selector[1].isLetter() || selector[1] == '_')) {
            end = 2
            while (end < selector.length && (selector[end].isLetterOrDigit() || selector[end] == '_' || selector[end] == '-')) end++
        }
        if (end > 0) f.genericByToken.getOrPut(selector.substring(0, end)) { ArrayList(1) }.add(selector)
        else f.genericComplex.add(selector)
    }

    private fun parseNetwork(f: Filters, line: String) {
        var rule = line
        val allow = rule.startsWith("@@")
        if (allow) rule = rule.substring(2)
        if (!rule.startsWith("||")) return
        var flags = ANY
        var cosmeticOff = 0
        var document = false
        val dollar = rule.indexOf('$')
        if (dollar >= 0) {
            for (opt in rule.substring(dollar + 1).split(',')) {
                when (opt.trim()) {
                    "third-party", "3p" -> flags = THIRD
                    "~third-party", "1p", "first-party" -> flags = FIRST
                    "generichide", "ghide" -> cosmeticOff = 1
                    "elemhide", "ehide" -> cosmeticOff = 2
                    "document", "doc" -> { cosmeticOff = 2; document = true }
                    "important", "script", "image", "stylesheet", "css", "subdocument", "frame",
                    "xmlhttprequest", "xhr", "media", "font", "object", "ping", "other", "websocket",
                    "all", "~script", "~image", "~stylesheet", "~subdocument", "~xmlhttprequest", "~media",
                    "~font", "~object", "~other" -> Unit
                    else -> return // domain=, redirect=, removeparam=, csp=, popup, badfilter...
                }
            }
            rule = rule.substring(0, dollar)
        }
        rule = rule.substring(2).lowercase()
        val hostEnd = rule.indexOfFirst { it == '^' || it == '/' }.let { if (it < 0) rule.length else it }
        val host = rule.substring(0, hostEnd)
        if (host.length < 3 || host[0] == '.' || host[0] == '-' || host.indexOf('.') < 0 ||
            host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') }) return
        var rest = rule.substring(hostEnd)
        if (allow && cosmeticOff > 0) {
            if (cosmeticOff == 1) f.noGenericHosts.add(host) else f.noCosmeticHosts.add(host)
            f.ruleCount++
            // $generichide / $elemhide only switch hiding off; $document also allows every request.
            if (!document) return
        }
        if (rest.isEmpty() || rest == "^" || rest == "^|") {
            (if (allow) f.allowHostMap else f.blockHostMap)[host] = flags
        } else if (rest.startsWith("/")) {
            rest = rest.trimEnd('^', '*', '|')
            if (rest.contains('*') || rest.contains('^') || rest.length < 2) return
            (if (allow) f.allowPaths else f.blockPaths).getOrPut(host) { ArrayList(2) }.add(rest)
        } else return
        f.ruleCount++
    }

    // ---- matching -----------------------------------------------------------------------------

    /** True if a subresource request to [url] made from a page on [pageHost] should be blocked. */
    fun shouldBlock(url: Uri, pageHost: String?): Boolean {
        val f = filters.get() ?: return false
        val host = url.host?.lowercase() ?: return false
        val third = pageHost == null || site(host) != site(pageHost)
        val path = (url.encodedPath ?: "") + (url.encodedQuery?.let { "?$it" } ?: "")
        if (matches(f.allowHosts, f.allowPaths, host, path, third)) return false
        return matches(f.blockHosts, f.blockPaths, host, path, third)
    }

    private fun matches(
        hosts: HostTable, paths: Map<String, List<String>>, host: String, path: String, third: Boolean
    ): Boolean {
        var h = host
        while (true) {
            hosts[h]?.let { if (it == ANY || (it == THIRD) == third) return true }
            paths[h]?.let { list -> if (list.any { path.startsWith(it) }) return true }
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
        }
    }

    /** CSS hiding the domain-specific selectors plus the generic ones without a class/id key. */
    fun cosmeticCss(host: String): String {
        val f = filters.get() ?: return ""
        if (hostMatches(f.noCosmeticHosts, host)) return ""
        val exceptions = exceptionsFor(f, host)
        val out = ArrayList<String>()
        forEachSuffix(host) { h -> f.specific[h]?.let { list -> list.filterTo(out) { it !in exceptions } } }
        if (!hostMatches(f.noGenericHosts, host)) f.genericComplex.filterTo(out) { it !in exceptions }
        return toCss(out)
    }

    /** CSS for generic selectors keyed by the given classes (".x") and ids ("#x") seen in the page. */
    fun genericCss(host: String, tokens: List<String>): String {
        val f = filters.get() ?: return ""
        if (hostMatches(f.noCosmeticHosts, host) || hostMatches(f.noGenericHosts, host)) return ""
        val exceptions = exceptionsFor(f, host)
        val out = ArrayList<String>()
        for (t in tokens) f.genericByToken[t]?.let { list -> list.filterTo(out) { it !in exceptions } }
        return toCss(out)
    }

    private fun exceptionsFor(f: Filters, host: String): Set<String> {
        val set = HashSet(f.genericExceptions)
        forEachSuffix(host) { h -> f.specificExceptions[h]?.let(set::addAll) }
        return set
    }

    private fun hostMatches(set: Set<String>, host: String): Boolean {
        var found = false
        forEachSuffix(host) { if (it in set) found = true }
        return found
    }

    private inline fun forEachSuffix(host: String, fn: (String) -> Unit) {
        var h = host.lowercase()
        while (true) {
            fn(h)
            val dot = h.indexOf('.')
            if (dot < 0) return
            h = h.substring(dot + 1)
        }
    }

    // Selectors are joined in chunks so one invalid selector only drops its own chunk.
    private fun toCss(selectors: List<String>): String =
        selectors.distinct().chunked(200).joinToString("\n") { "${it.joinToString(",")}{display:none!important}" }

    companion object {
        private const val TAG = "AdBlocker"
        private const val ANY = 0
        private const val FIRST = 1
        private const val THIRD = 2
        private const val MAX_AGE_MS = 4L * 24 * 3600 * 1000
        private val PROCEDURAL = Regex(
            ":(has-text|upward|xpath|matches-|remove|style|watch-attr|min-text-length|others|-abp-|contains)"
        )
        private const val CACHE_VERSION = 2
        private val TWO_PART_TLDS = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "co.kr",
            "com.br", "com.mx", "com.ar", "com.tr", "co.in", "co.za", "com.cn", "com.hk", "com.tw", "com.sg"
        )

        val LISTS = listOf(
            "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
            "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
            "ubo-filters.txt" to "https://ublockorigin.github.io/uAssets/filters/filters.txt",
            "ubo-privacy.txt" to "https://ublockorigin.github.io/uAssets/filters/privacy.txt",
        )

        /** Approximate registrable domain, used to decide first- vs third-party. */
        fun site(host: String): String {
            val parts = host.lowercase().removePrefix("www.").split('.')
            if (parts.size <= 2) return parts.joinToString(".")
            val lastTwo = parts.takeLast(2).joinToString(".")
            return if (lastTwo in TWO_PART_TLDS) parts.takeLast(3).joinToString(".") else lastTwo
        }
    }
}
