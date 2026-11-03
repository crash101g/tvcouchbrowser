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
        val popupHostMap = HashMap<String, Int>(4_000)
        // host -> flags (ANY, FIRST, THIRD) for `||host^` rules; a host and all its subdomains match.
        var blockHosts = HostTable.EMPTY
        var allowHosts = HostTable.EMPTY
        // `||host^$popup` rules: sites that only matter as the target of a pop-up window.
        var popupHosts = HostTable.EMPTY
        // host -> list of path prefixes for `||host/path` rules.
        var blockPaths = HashMap<String, MutableList<String>>(8_000)
        var allowPaths = HashMap<String, MutableList<String>>(1_000)
        val noCosmeticHosts = HashSet<String>()
        val noGenericHosts = HashSet<String>()
        // Generic hiding: selectors keyed by their leading class or id, plus ones without such a key.
        val genericByToken = HashMap<String, MutableList<String>>(20_000)
        val genericComplex = ArrayList<String>()
        val genericSeen = HashSet<String>() // parse-time only: the same selector appears in several lists
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

    // ---- which lists ----------------------------------------------------------------------------

    fun enabledLists(): Set<String> =
        prefs.getStringSet("lists", null) ?: LISTS.filter { it.defaultOn }.map { it.file }.toSet()

    fun setEnabledLists(files: Set<String>) = prefs.edit().putStringSet("lists", HashSet(files)).apply()

    private fun active() = LISTS.filter { it.file in enabledLists() }

    // ---- elements the user removed ("zapped"), remembered per site as CSS selectors -------------

    private fun zaps(): Set<String> = prefs.getStringSet("zaps", emptySet())!!

    /** Saves a selector to hide on [host]'s site. False if it doesn't look like a plain selector. */
    fun addZap(host: String, selector: String): Boolean {
        if (selector.length > 200 || selector.isBlank() || selector.any { it in "{}<>;@\n" }) return false
        val set = HashSet(zaps()); set.add(site(host) + "\u0001" + selector)
        if (set.size > 500) return false
        prefs.edit().putStringSet("zaps", set).apply()
        return true
    }

    fun zapCount(host: String) = zaps().count { it.startsWith(site(host) + "\u0001") }

    fun clearZaps(host: String) {
        val prefix = site(host) + "\u0001"
        prefs.edit().putStringSet("zaps", zaps().filterNot { it.startsWith(prefix) }.toSet()).apply()
    }

    private fun zapCss(host: String): String {
        val prefix = site(host) + "\u0001"
        // One rule per selector, so a selector the browser rejects doesn't take the others with it.
        return zaps().filter { it.startsWith(prefix) }.joinToString("\n") { it.substring(prefix.length) + "{display:none!important}" }
    }

    // ---- loading --------------------------------------------------------------------------------

    /** Loads the cached lists, downloading them first if missing or older than [MAX_AGE_MS]. */
    fun start(onDone: (String) -> Unit = {}) = worker.execute {
        val stale = System.currentTimeMillis() - lastUpdated > MAX_AGE_MS
        val missing = active().any { !File(dir, it.file).exists() }
        if (filters.get() == null && !missing) load()
        onDone(if (missing || stale) download() else "Filter lists loaded")
    }

    fun updateNow(onDone: (String) -> Unit) = worker.execute { onDone(download()) }

    private val updating = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Refreshes the lists if the last successful update is a day old. Called once a day while the browser
     * stays open, so a TV that is never restarted still updates daily.
     */
    fun updateIfStale(onDone: (String) -> Unit = {}) {
        if (System.currentTimeMillis() - lastUpdated < MAX_AGE_MS) return
        if (!updating.compareAndSet(false, true)) return
        worker.execute { try { onDone(download()) } finally { updating.set(false) } }
    }

    /** After the chosen lists change: fetch any that are missing, then rebuild the filters. */
    fun reload(onDone: (String) -> Unit) = worker.execute {
        val missing = active().any { !File(dir, it.file).exists() }
        if (missing) onDone(download()) else { load(); onDone("Filter lists loaded: $ruleCount rules") }
    }

    private fun download(): String {
        var ok = 0
        val lists = active()
        for (list in lists) {
            val name = list.file; val url = list.url
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
        return if (ok == lists.size) "Filter lists updated" else "Updated $ok of ${lists.size} filter lists"
    }

    private val cache = File(dir, "parsed.bin")

    /** Loads the parsed cache if it is newer than every list; otherwise parses the lists and rewrites it. */
    private fun load() {
        val t = System.currentTimeMillis()
        val lists = active()
        val signature = lists.joinToString(",") { it.file }
        val newest = lists.maxOfOrNull { File(dir, it.file).lastModified() } ?: 0L
        var f = if (cache.exists() && cache.lastModified() >= newest && prefs.getString("cacheLists", null) == signature) {
            try { readCache() } catch (e: Exception) { Log.w(TAG, "cache unreadable: $e"); null }
        } else null
        val fromCache = f != null
        if (f == null) {
            f = Filters()
            for (list in lists) {
                val file = File(dir, list.file)
                if (file.exists()) file.bufferedReader().useLines { lines -> lines.forEach { parse(f, it) } }
            }
            // A path rule under a host that is blocked outright can never decide anything.
            f.blockPaths.keys.removeAll { h -> coveredByHost(f.blockHostMap, h) }
            f.genericSeen.clear()
            f.blockHosts = HostTable.of(f.blockHostMap); f.blockHostMap.clear()
            f.allowHosts = HostTable.of(f.allowHostMap); f.allowHostMap.clear()
            f.popupHosts = HostTable.of(f.popupHostMap); f.popupHostMap.clear()
            try { writeCache(f); prefs.edit().putString("cacheLists", signature).apply() }
            catch (e: Exception) { Log.w(TAG, "cache not written: $e"); cache.delete() }
        }
        filters.set(f)
        Log.i(TAG, "loaded ${f.ruleCount} rules in ${System.currentTimeMillis() - t} ms (cache=$fromCache)")
    }

    private fun coveredByHost(hosts: Map<String, Int>, host: String): Boolean {
        var h = host
        while (true) {
            if (hosts[h] == ANY) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
        }
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
            table(f.blockHosts); table(f.allowHosts); table(f.popupHosts); lists(f.blockPaths); lists(f.allowPaths)
            strs(f.noCosmeticHosts); strs(f.noGenericHosts); lists(f.genericByToken); strs(f.genericComplex)
            lists(f.specific); lists(f.specificExceptions.mapValues { it.value.toList() }); strs(f.genericExceptions)
        }
        tmp.renameTo(cache)
    }

    private fun readCache(): Filters? = DataInputStream(BufferedInputStream(cache.inputStream(), 1 shl 16)).use { i ->
        if (i.readInt() != CACHE_VERSION) return null
        // One String per distinct selector: a selector shared by many sites is stored once, as when parsed.
        val pool = HashMap<String, String>(1 shl 15)
        fun str(): String { val s = i.readUTF(); return pool.getOrPut(s) { s } }
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
                repeat(n) { c.add(str()) }; m[k] = c
            }
        }
        fun strs(c: MutableCollection<String>) { repeat(i.readInt()) { c.add(str()) } }
        net.blockHosts = table(); net.allowHosts = table(); net.popupHosts = table()
        lists(net.blockPaths) { ArrayList(it) }; lists(net.allowPaths) { ArrayList(it) }
        // Network blocking goes live now; cosmetic hiding follows when the rest is read.
        filters.set(net)
        Log.i(TAG, "network rules ready")
        val f = Filters().apply {
            ruleCount = net.ruleCount; blockHosts = net.blockHosts; allowHosts = net.allowHosts; popupHosts = net.popupHosts
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
            else f.specific.getOrPut(d) { ArrayList(2) }.let { if (selector !in it) it.add(selector) }
        }
        f.ruleCount++
    }

    private fun addGeneric(f: Filters, selector: String) {
        if (!f.genericSeen.add(selector)) return
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
        var popup = false
        val dollar = rule.indexOf('$')
        if (dollar >= 0) {
            for (opt in rule.substring(dollar + 1).split(',')) {
                when (opt.trim()) {
                    "third-party", "3p" -> flags = THIRD
                    "~third-party", "1p", "first-party" -> flags = FIRST
                    "generichide", "ghide" -> cosmeticOff = 1
                    "elemhide", "ehide" -> cosmeticOff = 2
                    "document", "doc" -> { cosmeticOff = 2; document = true }
                    "popup" -> popup = true
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
        if (popup) {
            // Only whole-site pop-up rules; pop-up exceptions are rare and not worth the memory.
            if (allow || !(rest.isEmpty() || rest == "^" || rest == "^|")) return
            f.popupHostMap[host] = flags
            f.ruleCount++
            return
        }
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
        if (isChallenge(host, url.encodedPath ?: "")) return false
        val third = pageHost == null || site(host) != site(pageHost)
        val path = (url.encodedPath ?: "") + (url.encodedQuery?.let { "?$it" } ?: "")
        if (matches(f.allowHosts, f.allowPaths, host, path, third)) return false
        return matches(f.blockHosts, f.blockPaths, host, path, third)
    }

    /** True if a pop-up window to [url] opened from [pageHost] is an ad: a blocked host or a `$popup` rule. */
    fun shouldBlockPopup(url: Uri, pageHost: String?): Boolean {
        if (shouldBlock(url, pageHost)) return true
        val f = filters.get() ?: return false
        val host = url.host?.lowercase() ?: return false
        val third = pageHost == null || site(host) != site(pageHost)
        return matches(f.popupHosts, emptyMap(), host, "", third)
    }

    /**
     * Captcha and bot-check providers are never blocked: a blocked captcha can't be solved, and the
     * site behind it stays locked.
     */
    private fun isChallenge(host: String, path: String): Boolean {
        fun under(d: String) = host == d || host.endsWith(".$d")
        if (CHALLENGE_HOSTS.any(::under)) return true
        return (under("google.com") || under("gstatic.com")) && path.startsWith("/recaptcha")
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
    /**
     * CSS for a frame on [host]. Generic rules without a class/id key apply to every page, so they go
     * only to the top frame: ad frames are blocked by network rules, and each frame would otherwise
     * carry its own copy of this large style sheet.
     */
    fun cosmeticCss(host: String, topFrame: Boolean = true): String {
        val zapped = zapCss(host)
        val f = filters.get() ?: return zapped
        if (hostMatches(f.noCosmeticHosts, host)) return zapped
        val exceptions = exceptionsFor(f, host)
        val out = ArrayList<String>()
        forEachSuffix(host) { h -> f.specific[h]?.let { list -> list.filterTo(out) { it !in exceptions } } }
        if (topFrame && !hostMatches(f.noGenericHosts, host)) f.genericComplex.filterTo(out) { it !in exceptions }
        return toCss(out) + "\n" + zapped
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
        const val MAX_AGE_MS = 24L * 3600 * 1000 // lists are refreshed at least daily
        private val PROCEDURAL = Regex(
            ":(has-text|upward|xpath|matches-|remove|style|watch-attr|min-text-length|others|-abp-|contains)"
        )
        private const val CACHE_VERSION = 4
        private val TWO_PART_TLDS = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "co.kr",
            "com.br", "com.mx", "com.ar", "com.tr", "co.in", "co.za", "com.cn", "com.hk", "com.tw", "com.sg"
        )

        class FilterList(val file: String, val title: String, val url: String, val defaultOn: Boolean)

        private const val UBO = "https://ublockorigin.github.io/uAssets/filters/"
        val LISTS = listOf(
            FilterList("easylist.txt", "EasyList (ads)", "https://easylist.to/easylist/easylist.txt", true),
            FilterList("easyprivacy.txt", "EasyPrivacy (trackers)", "https://easylist.to/easylist/easyprivacy.txt", true),
            FilterList("ubo-filters.txt", "uBlock filters", UBO + "filters.txt", true),
            FilterList("ubo-privacy.txt", "uBlock filters: Privacy", UBO + "privacy.txt", true),
            FilterList("ubo-badware.txt", "uBlock filters: Badware risks (scam and fake-download sites)", UBO + "badware.txt", true),
            FilterList("ubo-quick-fixes.txt", "uBlock filters: Quick fixes", UBO + "quick-fixes.txt", true),
            FilterList("ubo-unbreak.txt", "uBlock filters: Unbreak (fixes sites the other lists break)", UBO + "unbreak.txt", true),
            FilterList("adguard-popups.txt", "AdGuard Popups", "https://filters.adtidy.org/extension/ublock/filters/19.txt", true),
            FilterList("peter-lowe.txt", "Peter Lowe's ad and tracking servers",
                "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext", true),
            // Off by default: mostly element-hiding rules, which cost far more memory per rule than the
            // blocked-server rules above, and they overlap EasyList. "Clean up page" covers most of the same.
            FilterList("ubo-annoyances-cookies.txt", "uBlock filters: Cookie notices", UBO + "annoyances-cookies.txt", false),
            FilterList("ubo-annoyances-others.txt", "uBlock filters: Other annoyances", UBO + "annoyances-others.txt", false),
            FilterList("adguard-mobile.txt", "AdGuard Mobile Ads (overlaps EasyList)", "https://filters.adtidy.org/extension/ublock/filters/11.txt", false),
            FilterList("adguard-other-annoyances.txt", "AdGuard Other Annoyances (large)", "https://filters.adtidy.org/extension/ublock/filters/21.txt", false),
            FilterList("fanboy-annoyance.txt", "Fanboy's Annoyance (very large: cookie, social, chat)", "https://secure.fanboy.co.nz/fanboy-annoyance.txt", false),
        )

        private val CHALLENGE_HOSTS = listOf(
            "challenges.cloudflare.com", "hcaptcha.com", "recaptcha.net", "arkoselabs.com", "funcaptcha.com",
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
