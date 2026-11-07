package vn.xemtv.lite

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit

// Các danh sách công khai (iptv-org, Free-TV). Mỗi kênh có thể có link ở nhiều danh sách -> dùng làm link dự phòng.
val BUILT_IN_URLS = listOf(
    "https://iptv-org.github.io/iptv/countries/vn.m3u",
    "https://iptv-org.github.io/iptv/languages/vie.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/vn.m3u",
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_vietnam.m3u8",
)

const val G_NATIONAL = "VTV"
const val G_ESSENTIAL = "Thiết yếu"
const val G_LOCAL = "Địa phương"
const val G_VTVCAB = "VTVcab"
const val G_SCTV = "SCTV"
const val G_HTVC = "HTVC"
const val G_SPORT = "Thể thao"
const val G_MOVIE = "Phim"
const val G_OTHER = "Khác"
// Thứ tự nhóm = thứ tự đánh số kênh: VTV, Thiết yếu, VTVcab, SCTV, HTVC, Địa phương, rồi Thể thao, Phim, nhóm của bạn, Khác
val VN_GROUPS = listOf(G_NATIONAL, G_ESSENTIAL, G_VTVCAB, G_SCTV, G_HTVC, G_LOCAL)
private val AUTO_GROUPS = VN_GROUPS + G_OTHER

private const val MAX_LINKS = 12
private const val DEAD_AFTER = 2   // lỗi liên tiếp từng này lần thì coi link là chết

/** Một kênh = nhiều link (urls). Link đầu là link chính, các link sau là dự phòng. */
data class Channel(val id: String, val name: String, val logo: String, val group: String, val urls: List<String>)

/** auto = true: nguồn có sẵn, tự chia nhóm VN. auto = false: link của bạn, cả danh sách vào 1 nhóm tên [name]. */
data class Source(
    val name: String, val url: String, val auto: Boolean = false, val discovered: Boolean = false,
    val mode: String = "all",   // "sports": chỉ giữ kênh thể thao; "vn": chỉ giữ kênh tiếng Việt
    val limit: Int = 0,          // > 0: chỉ lấy tối đa ngần này kênh đầu
)

private class Entry(val name: String, val logo: String, val url: String, val group: String = "", val tvgId: String = "")

// Nguồn tự tìm được chỉ giữ kênh có dấu hiệu Việt Nam
val KEEP_RE = Regex("vtv|htv|vtc|thvl|sctv|antv|vov|viet|truyen hinh|thuyet minh|long tieng|\\bvn\\b|\\.vn\\b|vietsub|vtvcab|k\\+|bong da viet")
private const val FT = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists"
private const val ORG = "https://iptv-org.github.io/iptv/categories"

/** Kênh thể thao và kênh phim (truyền hình trực tiếp) từ danh sách công khai. */
val EXTRA_SOURCES = listOf(
    Source(G_SPORT, "$ORG/sports.m3u", limit = 150),
    Source(G_SPORT, "$FT/playlist_uk.m3u8", mode = "sports"),
    Source(G_SPORT, "$FT/playlist_france.m3u8", mode = "sports"),
    Source(G_SPORT, "$FT/playlist_spain.m3u8", mode = "sports"),
    Source(G_SPORT, "$FT/playlist_italy.m3u8", mode = "sports"),
    Source(G_SPORT, "$FT/playlist_usa.m3u8", mode = "sports"),
    // Nhóm Phim chỉ giữ kênh tiếng Việt (app không dịch tức thì được)
    Source(G_MOVIE, "$ORG/movies.m3u", mode = "vn"),
    Source(G_MOVIE, "$ORG/series.m3u", mode = "vn"),
)
private val SPORT_RE = Regex("sport|the thao|bong da|football|soccer|\\bf1\\b|\\bk\\+|eurosport|canal\\+ (foot|live)|bein|espn|dazn")

const val EXTRA_ASSET = "asset://live_extra.m3u"


// ---------- Chuẩn hóa tên kênh (giống cách app mẫu gộp kênh trùng) ----------
fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')

private val QUALITY = Regex("""\s*[(\[]?\s*(?:hd|fhd|sd|uhd|4k|1080p|720p|576p|480p)\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)
private val ANNOT = Regex(
    """\s*[(\[][^)\]]*(?:geo|backup|link|mirror|not\s*24|24/7|dự\s*phòng|vietsub|\b\d{3,4}p\b|\bhd\b|\bsd\b|fhd|uhd|4k|test|lag|unstable|offline)[^)\]]*[)\]]\s*$""",
    RegexOption.IGNORE_CASE,
)

private fun stripAnnotations(name: String): String {
    var out = name.trim().replace(QUALITY, "").trim()
    repeat(3) {
        val next = out.replace(ANNOT, "").trim().replace(QUALITY, "").trim()
        if (next.isEmpty() || next == out) return out
        out = next
    }
    return out
}

fun canonicalName(name: String): String {
    val plain = stripAnnotations(name).replace(Regex("^[^\\p{L}\\p{N}]+"), "").replace(Regex("\\s+"), " ").trim()
    val f = fold(plain)
    Regex("^vtv[\\s\\-_.]*cab[\\s\\-_.]*(\\d{1,2})(?!\\d)").find(f)?.let { return "VTVcab ${it.groupValues[1].toInt()}" }
    if (Regex("^on[\\s\\-_.]*sports?$").matches(f)) return "VTVcab 3"
    if (Regex("^vtv[\\s\\-_.]*(?:5[\\s\\-_.]*)?tay\\s*nguyen").containsMatchIn(f)) return "VTV5 Tây Nguyên"
    if (Regex("^vtv[\\s\\-_.]*(?:5[\\s\\-_.]*)?tay\\s*nam\\s*bo").containsMatchIn(f)) return "VTV5 Tây Nam Bộ"
    if (Regex("^vtv[\\s\\-_.]*can\\s*tho").containsMatchIn(f)) return "VTV Cần Thơ"
    Regex("^vtv[\\s\\-_.]*(\\d{1,2})(?!\\d)").find(f)?.let { return "VTV${it.groupValues[1].toInt()}" }
    Regex("^sctv[\\s\\-_.]*(\\d{1,2})(?!\\d)").find(f)?.let { return "SCTV${it.groupValues[1].toInt()}" }
    return plain
}

private val NOISE = setOf("the", "channel", "chanel", "kenh", "tv", "hd", "fhd", "sd", "uhd", "4k", "1080p", "720p", "576p", "480p")
private val KEEP_S = setOf("news", "plus", "mass", "gas", "bus", "xtreme")
private fun singular(w: String) =
    if (w.length < 4 || w in KEEP_S || !w.endsWith("s") || Regex("(?:ss|us|is)$").containsMatchIn(w)) w else w.dropLast(1)

fun channelKey(name: String): String =
    fold(canonicalName(name)).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE }
        .joinToString(" ") { singular(it) }

private val PROVINCES = listOf(
    "an giang", "ba ria", "vung tau", "bac giang", "bac kan", "bac lieu", "bac ninh", "ben tre", "binh dinh", "binh duong",
    "binh phuoc", "binh thuan", "ca mau", "can tho", "cao bang", "da nang", "dak lak", "dac lak", "dak nong", "dien bien",
    "dong nai", "dong thap", "gia lai", "ha giang", "ha nam", "ha noi", "ha tinh", "hai duong", "hai phong", "hau giang",
    "hoa binh", "hung yen", "khanh hoa", "kien giang", "kon tum", "lai chau", "lam dong", "lang son", "lao cai", "long an",
    "nam dinh", "nghe an", "ninh binh", "ninh thuan", "phu tho", "phu yen", "quang binh", "quang nam", "quang ngai",
    "quang ninh", "quang tri", "soc trang", "son la", "tay ninh", "thai binh", "thai nguyen", "thanh hoa", "thua thien hue",
    "hue", "tien giang", "tra vinh", "tuyen quang", "vinh long", "vinh phuc", "yen bai", "ho chi minh", "tphcm", "sai gon",
)
private val PROVINCE_RE = Regex("(?:^|[^a-z])(?:${PROVINCES.joinToString("|")})(?:[^a-z]|$)")
private val LOCAL_ABBR_RE = Regex("^(?:thvl|htv|btv|brt|la\\s*34|dnrtv|dntv|hanoitv|thdt|thtg|thbt|thct|thcm|qrt|trt|prt|drt|bltv|hgtv|ctv)(?![a-z])")
// Nhóm "VTV" chỉ chứa kênh VTV. Các kênh khác (ANTV, QPVN, TVB, Vietnam Today…) vào nhóm "Thiết yếu".
private val NATIONAL_RE = Regex("^vtv")
private val ESSENTIAL_RE = Regex("^(?:antv|an ninh|qptv|qpvn|quoc phong|vnews|vov|vtc|quoc hoi|thong tan xa|tvb|viet\\s*nam\\s*today)")

fun classifyVn(name: String): String {
    val f = fold(name)
    return when {
        f.startsWith("vtvcab") || f.startsWith("vtv cab") || Regex("^on\\s").containsMatchIn(f) -> G_VTVCAB
        f.startsWith("sctv") -> G_SCTV
        f.startsWith("htvc") -> G_HTVC
        NATIONAL_RE.containsMatchIn(f) -> G_NATIONAL
        ESSENTIAL_RE.containsMatchIn(f) -> G_ESSENTIAL
        LOCAL_ABBR_RE.containsMatchIn(f) || PROVINCE_RE.containsMatchIn(f) -> G_LOCAL
        else -> G_OTHER
    }
}

private fun natKey(s: String) =
    fold(s).replace(Regex("\\s+"), "").replace(Regex("\\d+")) { it.value.padStart(5, '0') }

/** Sắp theo nhóm (đúng thứ tự đánh số); trong nhóm giữ nguyên thứ tự (VTV1, VTV2, … VTV10). */
fun orderChannels(list: List<Channel>, userGroups: List<String>): List<Channel> {
    val order = VN_GROUPS + listOf(G_SPORT, G_MOVIE) + userGroups + G_OTHER
    val rank = HashMap<String, Int>()
    order.forEachIndexed { i, g -> if (g !in rank) rank[g] = i }
    return list.sortedBy { rank[it.group] ?: order.size }
}

// Kênh radio / chỉ có âm thanh (VOV, FM, podcast…) -> bỏ hết
private val RADIO_RE = Regex("(^|[^a-z])(radio|fm|podcast|audio)([^a-z]|$)|^vov(?!\\s*tv)(?![a-z])|tieng noi viet nam|dai tieng noi")
private val AUDIO_URL_RE = Regex("\\.(mp3|aac|ogg|m4a|opus|wav)(\\?|$)", RegexOption.IGNORE_CASE)
private fun isRadio(name: String, group: String, url: String) =
    RADIO_RE.containsMatchIn(fold(name)) || fold(group).contains("radio") || AUDIO_URL_RE.containsMatchIn(url)

/** Kênh có dấu hiệu tiếng Việt: tvg-id đuôi .vn hoặc tên/nhóm có từ khóa Việt Nam. */
private fun isVietnamese(e: Entry) =
    e.tvgId.contains(".vn", true) || KEEP_RE.containsMatchIn(fold(e.name + " " + e.group))

private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

private fun parseM3u(text: String): List<Entry> {
    val out = ArrayList<Entry>()
    var name: String? = null
    var logo = ""
    var group = ""
    var tvg = ""
    for (raw in text.removePrefix("﻿").lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF", ignoreCase = true)) {
            val attrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            name = line.substringAfterLast(',').trim().ifBlank { attrs["tvg-id"] ?: "" }
            logo = attrs["tvg-logo"].orEmpty()
            group = attrs["group-title"].orEmpty()
            tvg = attrs["tvg-id"].orEmpty()
        } else if (!line.startsWith("#") && name != null) {
            if (name.isNotBlank() && line.startsWith("http", ignoreCase = true)) out += Entry(name, logo, line, group, tvg)
            name = null
        }
    }
    return out
}

/**
 * Kết quả kiểm tra từng link, LƯU MÃI trên máy cho đến khi người dùng kiểm tra lại.
 * score > 0: chạy được (càng cao càng tốt: tốc độ tải + băng thông khai báo).
 * fails: số lần lỗi liên tiếp; từ [DEAD_AFTER] lần trở lên thì coi là chết (1 lần lỗi lẻ không bị loại oan).
 */
class Health(private val prefs: android.content.SharedPreferences, private val key: String) {
    private class Stat(val score: Int, val fails: Int)
    private val m = java.util.concurrent.ConcurrentHashMap<String, Stat>()

    init {
        try {
            val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
            o.keys().forEach { k -> val a = o.getJSONArray(k); m[k] = Stat(a.getInt(0), a.getInt(1)) }
        } catch (_: Exception) {}
    }

    fun ok(): Set<String> = m.filter { it.value.score > 0 && it.value.fails < DEAD_AFTER }.keys.toSet()
    fun dead(): Set<String> = m.filter { it.value.fails >= DEAD_AFTER }.keys.toSet()
    fun score(url: String): Int = m[url]?.score ?: 0

    /** Đo được [s] điểm: làm mượt với điểm cũ để một lần đo nhiễu không đảo thứ tự. */
    fun succeed(url: String, s: Int) {
        val old = m[url]?.score ?: 0
        m[url] = Stat(if (old > 0) (old + s) / 2 else s, 0)
    }
    /** Đang phát được thật: xóa lỗi, giữ điểm cũ. */
    fun confirm(url: String) { m[url] = Stat(maxOf(m[url]?.score ?: 0, 1), 0) }
    /** Lỗi 1 lần (khi đang xem hoặc đo nhanh). */
    fun fail(url: String) { val o = m[url]; m[url] = Stat(o?.score ?: 0, (o?.fails ?: 0) + 1) }
    /** Đã đo lại chắc chắn là chết. */
    fun kill(url: String) { m[url] = Stat(0, DEAD_AFTER) }

    fun save() {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.score, v.fails))) }
        prefs.edit().putString(key, o.toString()).apply()
    }
}

class Repo(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("xemtv", Context.MODE_PRIVATE)
    private val dir = ctx.filesDir
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    var builtIn: Boolean
        get() = prefs.getBoolean("builtin", true)
        set(v) { prefs.edit().putBoolean("builtin", v).apply() }

    /** Bật/tắt nhóm Thể thao và Phim (kênh quốc tế). */
    var extras: Boolean
        get() = prefs.getBoolean("extras", true)
        set(v) { prefs.edit().putBoolean("extras", v).apply() }

    /** Chỉ các nguồn do bạn thêm. */
    var sources: List<Source>
        get() {
            val json = prefs.getString("sources", null) ?: return emptyList()
            return try {
                val a = JSONArray(json)
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Source(o.getString("name"), o.getString("url"), false)
                }
            } catch (e: Exception) { emptyList() }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url)) }
            prefs.edit().putString("sources", a.toString()).apply()
        }

    var ghToken: String
        get() = prefs.getString("ghtoken", "") ?: ""
        set(v) { prefs.edit().putString("ghtoken", v.replace(Regex("\\s+"), "")).apply() }

    /** Link playlist đã tìm được trên GitHub. */
    var found: List<String>
        get() = try {
            val a = JSONArray(prefs.getString("found", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
        set(v) { prefs.edit().putString("found", JSONArray(v).toString()).apply() }

    var favorites: Set<String>
        get() = (prefs.getStringSet("fav2", emptySet()) ?: emptySet()).map { id ->
            // Đổi tên nhóm cũ "Quốc gia (VTV)" -> "VTV" hoặc "Thiết yếu" để không mất kênh yêu thích
            val old = "Quốc gia (VTV)|"
            if (id.startsWith(old)) {
                val k = id.removePrefix(old)
                val g = if (k.startsWith("vtv")) G_NATIONAL else G_ESSENTIAL
                "$g|$k"
            } else id
        }.toSet()
        set(v) { prefs.edit().putStringSet("fav2", HashSet(v)).apply() }

    // Kết quả kiểm tra từng link: lưu mãi cho đến khi người dùng bấm kiểm tra lại
    val health = Health(prefs, "health3")

    /** Tự tìm nguồn bổ sung khi có kênh chết. */
    var autoFill: Boolean
        get() = prefs.getBoolean("autofill", true)
        set(v) { prefs.edit().putBoolean("autofill", v).apply() }

    var lastDiscover: Long
        get() = prefs.getLong("lastdisc", 0L)
        set(v) { prefs.edit().putLong("lastdisc", v).apply() }

    /** id các kênh đang không có link sống: nguồn tự tìm được ưu tiên gộp vào đúng các kênh này. */
    var wanted: Set<String>
        get() = prefs.getStringSet("wanted2", emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet("wanted2", HashSet(v)).apply() }

    /** key kênh -> nhóm, của các kênh đang thiếu link sống. */
    private fun wantedGroups(): Map<String, String> {
        val m = HashMap<String, String>()
        for (id in wanted) { val i = id.indexOf('|'); if (i > 0) m[id.substring(i + 1)] = id.substring(0, i) }
        return m
    }

    private fun filterFor(s: Source, list: List<Entry>, wantedKeys: Set<String>): List<Entry> {
        var l = list
        if (s.discovered) l = l.filter { KEEP_RE.containsMatchIn(fold(it.name + " " + it.group)) || channelKey(it.name) in wantedKeys }
        if (s.mode == "sports") l = l.filter { SPORT_RE.containsMatchIn(fold(it.name + " " + it.group)) }
        if (s.mode == "vn") l = l.filter { isVietnamese(it) }
        if (s.limit > 0) l = l.take(s.limit)
        return l
    }

    // ----- Tải & gộp -----
    // Thứ tự = thứ tự ưu tiên link: link gốc trong app -> danh sách công khai -> link của bạn -> nguồn tự tìm
    private fun allSources(): List<Source> =
        (if (builtIn) listOf(Source("Việt Nam", EXTRA_ASSET, true)) + BUILT_IN_URLS.map { Source("Việt Nam", it, true) }
         else emptyList()) +
            sources +
            (if (extras) EXTRA_SOURCES else emptyList()) +
            found.map { Source("Tìm được", it, auto = true, discovered = true) }

    private fun file(s: Source) = File(dir, "src_${s.url.hashCode()}.m3u")

    fun forget(s: Source) { file(s).delete() }

    private fun serialize(entries: List<Entry>) = buildString {
        entries.forEach {
            append("#EXTINF:-1 tvg-logo=\"").append(it.logo.replace("\"", "")).append("\" group-title=\"")
                .append(it.group.replace("\"", "")).append("\" tvg-id=\"").append(it.tvgId.replace("\"", "")).append("\",").append(it.name.replace("\n", " ")).append('\n')
            append(it.url).append('\n')
        }
    }

    private fun readCached(s: Source): List<Entry> = try {
        if (s.url == EXTRA_ASSET) parseM3u(ctx.assets.open("live_extra.m3u").bufferedReader().readText())
        else file(s).takeIf { it.exists() }?.let { filterFor(s, parseM3u(it.readText()), wantedGroups().keys) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun merge(parts: List<Pair<Source, List<Entry>>>): List<Channel> {
        val map = LinkedHashMap<String, Channel>()
        val wantedGroup = wantedGroups()
        for ((s, entries) in parts) for (e in entries) {
            if (isRadio(e.name, e.group, e.url)) continue
            val key = channelKey(e.name)
            if (key.isEmpty()) continue
            val display = canonicalName(e.name)
            val group = (if (s.discovered) wantedGroup[key] else null) ?: if (s.auto) classifyVn(display) else s.name
            val id = "$group|$key"
            val cur = map[id]
            if (cur == null) map[id] = Channel(id, display, e.logo, group, listOf(e.url))
            else if (e.url !in cur.urls && cur.urls.size < MAX_LINKS)
                map[id] = cur.copy(logo = cur.logo.ifBlank { e.logo }, urls = cur.urls + e.url)
        }
        val (auto, user) = map.values.partition { it.group in AUTO_GROUPS }
        return auto.sortedBy { natKey(it.name) } + user
    }

    suspend fun cached(): List<Channel> = withContext(Dispatchers.IO) {
        merge(allSources().map { s -> s to readCached(s) })
    }

    /** Tải tất cả nguồn (tối đa 8 cùng lúc). Nguồn nào lỗi thì dùng bản đã lưu. */
    suspend fun fetch(): List<Channel> = withContext(Dispatchers.IO) {
        val srcs = allSources()
        val sem = Semaphore(8)
        val wk = wantedGroups().keys
        val gone = java.util.concurrent.ConcurrentLinkedQueue<String>()   // nguồn tự tìm đã bị xóa (404/410)
        val results = coroutineScope {
            srcs.map { s ->
                async {
                    sem.withPermit {
                        if (s.url == EXTRA_ASSET) return@withPermit (s to readCached(s)).takeIf { it.second.isNotEmpty() }
                        try {
                            val req = Request.Builder().url(s.url).build()
                            val body = client.newCall(req).execute().use { r ->
                                if (!r.isSuccessful) error("HTTP ${r.code}")
                                r.body?.string().orEmpty()
                            }
                            val list = filterFor(s, parseM3u(body), wk)
                            if (list.isEmpty()) error("trống")
                            file(s).writeText(serialize(list))   // lưu bản đã lọc cho nhẹ
                            s to list
                        } catch (e: Exception) {
                            val m = e.message.orEmpty()
                            if (s.discovered && (m.startsWith("HTTP 404") || m.startsWith("HTTP 410"))) {
                                gone.add(s.url); forget(s); null     // nguồn chết hẳn -> bỏ để tìm nguồn thay thế
                            } else {
                                val c = readCached(s)
                                if (c.isNotEmpty()) s to c else null
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (srcs.isNotEmpty() && results.all { it == null }) error("không tải được nguồn nào, kiểm tra mạng hoặc link")
        if (gone.isNotEmpty()) { val g = gone.toSet(); found = found.filter { it !in g } }
        merge(results.filterNotNull())
    }
}
