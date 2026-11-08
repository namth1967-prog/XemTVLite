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
/** Dấu hiệu Việt Nam chặt: không chứa vtv/htv/vtc… (các chữ này cũng có ở kênh nước ngoài). */
val KEEP_STRICT = Regex("viet|truyen hinh|thuyet minh|long tieng|\\bvn\\b|\\.vn\\b|vietsub|k\\+|bong da viet")
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
// Regex và kết quả chuẩn hoá tên được tạo MỘT LẦN rồi dùng lại (trước đây tạo mới hàng chục Regex cho mỗi tên kênh
// ở mỗi lần nạp danh sách -> khởi động rất chậm trên TV box).
private val RX_0 = Regex("\\p{Mn}+")
private val RX_1 = Regex("^[^\\p{L}\\p{N}]+")
private val RX_2 = Regex("\\s+")
private val RX_3 = Regex("^vtv[\\s\\-_.]*cab[\\s\\-_.]*(\\d{1,2})(?!\\d)")
private val RX_4 = Regex("^on[\\s\\-_.]*sports?$")
private val RX_5 = Regex("^vtv[\\s\\-_.]*(?:5[\\s\\-_.]*)?tay\\s*nguyen")
private val RX_6 = Regex("^vtv[\\s\\-_.]*(?:5[\\s\\-_.]*)?tay\\s*nam\\s*bo")
private val RX_7 = Regex("^vtv[\\s\\-_.]*can\\s*tho")
private val RX_8 = Regex("^vtv[\\s\\-_.]*(\\d{1,2})(?!\\d)")
private val RX_9 = Regex("^sctv[\\s\\-_.]*(\\d{1,2})(?!\\d)")
private val RX_10 = Regex("(?:ss|us|is)$")
private val RX_11 = Regex("[^a-z0-9]+")
private val RX_12 = Regex("^on\\s")
private val RX_13 = Regex("\\d+")
private val canonMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
private val keyMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
private val dedupeMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
private val classMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
private val natMemo = java.util.concurrent.ConcurrentHashMap<String, String>()

fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(RX_0, "").replace('đ', 'd')

private val QUALITY = Regex("""\s*[(\[]?\s*(?:hd|fhd|sd|uhd|4k|8k|2160p|1440p|1080[pi]|720[pi]|576[pi]|540[pi]|480[pi]|360p|240p)\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)
private val ANNOT = Regex(
    """\s*[(\[][^)\]]*(?:geo|backup|link|mirror|not\s*24|24/7|dự\s*phòng|vietsub|\b\d{3,4}[pi]\b|\bhd\b|\bsd\b|fhd|uhd|4k|test|lag|unstable|offline)[^)\]]*[)\]]\s*$""",
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

fun canonicalName(name: String): String = canonMemo.getOrPut(name) { canonicalNameRaw(name) }

private fun canonicalNameRaw(name: String): String {
    val plain = stripAnnotations(name).replace(RX_1, "").replace(RX_2, " ").trim()
    val f = fold(plain)
    RX_3.find(f)?.let { return "VTVcab ${it.groupValues[1].toInt()}" }
    if (RX_4.matches(f)) return "VTVcab 3"
    if (RX_5.containsMatchIn(f)) return "VTV5 Tây Nguyên"
    if (RX_6.containsMatchIn(f)) return "VTV5 Tây Nam Bộ"
    if (RX_7.containsMatchIn(f)) return "VTV Cần Thơ"
    RX_8.find(f)?.let { return "VTV${it.groupValues[1].toInt()}" }
    RX_9.find(f)?.let { return "SCTV${it.groupValues[1].toInt()}" }
    return plain
}

private val NOISE = setOf("the", "channel", "chanel", "kenh", "tv", "hd", "fhd", "sd", "uhd", "4k", "1080p", "720p", "576p", "480p")
private val KEEP_S = setOf("news", "plus", "mass", "gas", "bus", "xtreme")
private fun singular(w: String) =
    if (w.length < 4 || w in KEEP_S || !w.endsWith("s") || RX_10.containsMatchIn(w)) w else w.dropLast(1)

fun channelKey(name: String): String = keyMemo.getOrPut(name) { channelKeyRaw(name) }

private fun channelKeyRaw(name: String): String =
    fold(canonicalName(name)).split(RX_11).filter { it.isNotEmpty() && it !in NOISE }
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

// ---------- Khóa chống trùng kênh ----------
// Gộp các cách viết khác nhau của cùng một kênh: "Da Nang TV 1" = "Đà Nẵng TV1" = "Truyền hình Đà Nẵng 01"…
private val PROV_JOIN = Regex("(?<![a-z0-9])(?:${PROVINCES.sortedByDescending { it.length }.joinToString("|")})(?![a-z0-9])")
private val NOISE2 = NOISE + setOf("truyen", "hinh", "dai", "ptth", "thtp",
    "1080i", "720i", "576i", "480i", "540i", "2160p", "1440p", "540p", "360p", "240p", "8k")

// Tên khác nhau nhưng là MỘT kênh (viết tắt <-> tên đầy đủ). Mỗi dòng: cụm từ đầu của tên (đã bỏ dấu) -> khoá chung.
// Muốn thêm cặp mới chỉ cần thêm một dòng ở đây.
private val ALIAS_PREFIXES: List<Pair<List<String>, List<String>>> = listOf(
    listOf("an", "ninh") to listOf("antv"),                      // An Ninh TV = ANTV
    listOf("quoc", "phong", "viet", "nam") to listOf("qpvn"),    // Quốc phòng Việt Nam = QPVN
    listOf("quoc", "phong") to listOf("qpvn"),
)

// Tên viết tắt dài ra bằng phần mô tả được phép gộp vào tên ngắn (ANTV = "ANTV - TH Công an nhân dân", QPVN = "QPVN - TH Quân đội")
private val NAME_FAMILY = setOf("antv", "qpvn")

// Tên tỉnh viết liền (cantho, danang…): "Cần Thơ TV" = "Cần Thơ TV 1" (kênh số 1 của đài)
private val PROV_COMPACT = PROVINCES.map { it.replace(" ", "") }.toSet()
private val TV_NUM = Regex("^tv0*(\\d+)$")

fun dedupeKey(name: String): String = dedupeMemo.getOrPut(name) { dedupeKeyRaw(name) }

private fun dedupeKeyRaw(name: String): String {
    val f = PROV_JOIN.replace(fold(canonicalName(name))) { it.value.replace(" ", "") }   // "can tho" -> "cantho"
    var toks = f.split(RX_11)
        .filter { it.isNotEmpty() && it !in NOISE2 }
        .map { w ->
            val m = TV_NUM.find(w)
            if (m != null) m.groupValues[1]                                                // "tv1" -> "1"
            else if (w.all { it.isDigit() }) w.trimStart('0').ifEmpty { "0" } else w     // "03" -> "3"
        }
        .filter { it !in NOISE2 }
        .map { singular(it) }
    for ((from, to) in ALIAS_PREFIXES) {
        if (toks.size >= from.size && toks.take(from.size) == from) { toks = to + toks.drop(from.size); break }
    }
    if (toks.size == 2 && toks[1] == "1" && toks[0] in PROV_COMPACT) toks = listOf(toks[0])   // "cantho 1" = "cantho"
    return toks.joinToString(" ")
}

private fun hasAccent(s: String) = fold(s) != s.lowercase()
// Viết tắt đài địa phương chỉ được nhận khi theo sau là số / vài hậu tố quen thuộc (HTV7, THVL1, HTV Thể thao…).
// Nhờ vậy "HTV Houston", "CTV" (Canada), "TRT" (Thổ Nhĩ Kỳ)… không lọt vào nhóm Địa phương.
private val LOCAL_ABBR_RE = Regex("^(?:thvl|htv|btv|brt|la\\s*34|dnrtv|dntv|hanoitv|thdt|thtg|thbt|thct|thcm|qrt|prt|drt|bltv|hgtv)[\\s\\-_.]*\\d{0,2}(?:[\\s\\-_.]+(?:the thao|sports?|phim|movies?|co[\\s.]*op[\\s\\-]*mart|co\\s*mart|news|plus))?[\\s\\-_.]*\$")
// Nhóm "VTV" chỉ có đúng 12 kênh: VTV1..VTV10, VTV5 Tây Nam Bộ, VTV5 Tây Nguyên. Kênh "VTV…" khác (nước ngoài) KHÔNG vào đây.
// Các kênh nhà nước khác (ANTV, QPVN, TVB, Vietnam Today…) vào nhóm "Thiết yếu".
private val NATIONAL_RE = Regex("^(?:vtv(?:[1-9]|10)|vtv5 tay (?:nguyen|nam bo))\$")
private val ESSENTIAL_RE = Regex("^(?:antv|an ninh|qptv|qpvn|quoc phong|vnews|vov|vtc|quoc hoi|thong tan xa|tvb|viet\\s*nam\\s*today)")

fun classifyVn(name: String): String = classMemo.getOrPut(name) { classifyVnRaw(name) }

private fun classifyVnRaw(name: String): String {
    val f = fold(name)
    return when {
        f.startsWith("vtvcab") || f.startsWith("vtv cab") || RX_12.containsMatchIn(f) -> G_VTVCAB
        f.startsWith("sctv") -> G_SCTV
        f.startsWith("htvc") -> G_HTVC
        NATIONAL_RE.containsMatchIn(f) -> G_NATIONAL
        ESSENTIAL_RE.containsMatchIn(f) -> G_ESSENTIAL
        LOCAL_ABBR_RE.containsMatchIn(f) || PROVINCE_RE.containsMatchIn(f) -> G_LOCAL
        else -> G_OTHER
    }
}

private fun natKey(s: String): String = natMemo.getOrPut(s) { natKeyRaw(s) }

private fun natKeyRaw(s: String): String =
    fold(s).replace(RX_2, "").replace(RX_13) { it.value.padStart(5, '0') }

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
    e.tvgId.contains(".vn", true) || KEEP_STRICT.containsMatchIn(fold(e.name + " " + e.group)) ||
        classifyVn(canonicalName(e.name)) != G_OTHER

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
    /** Lỗi khi đang xem: chỉ trừ điểm để link đó xuống cuối hàng, KHÔNG đánh dấu chết (kênh không biến mất). */
    fun penalize(url: String) {
        val o = m[url] ?: return
        if (o.score > 0) m[url] = Stat(maxOf(1, o.score / 2), o.fails)
    }
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
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

    /** id các kênh người dùng đã xoá (ẩn mãi cho đến khi khôi phục). */
    var hidden: Set<String>
        get() = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet("hidden", HashSet(v)).apply() }

    /** Kênh người dùng đã di chuyển: id -> tên nhóm mới. */
    var moved: Map<String, String>
        get() = try {
            val o = JSONObject(prefs.getString("moved", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
        set(v) { prefs.edit().putString("moved", JSONObject(HashMap(v) as Map<*, *>).toString()).apply() }

    /** Tự tìm nguồn bổ sung khi có kênh chết. */
    var autoFill: Boolean
        get() = prefs.getBoolean("autofill", true)
        set(v) { prefs.edit().putBoolean("autofill", v).apply() }

    /** Cân bằng âm lượng giữa các kênh (bộ tự chỉnh mức tiếng). */
    var leveler: Boolean
        get() = prefs.getBoolean("leveler", true)
        set(v) { prefs.edit().putBoolean("leveler", v).apply() }

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
        for (id in wanted) { val i = id.indexOf('|'); if (i > 0) m[dedupeKey(id.substring(i + 1))] = id.substring(0, i) }
        return m
    }

    private fun filterFor(s: Source, list: List<Entry>, wantedKeys: Set<String>): List<Entry> {
        var l = list
        if (s.discovered) l = l.filter { e ->
            // Nguồn tự tìm: chỉ giữ kênh phân loại được là kênh VN, hoặc có dấu hiệu VN rõ ràng, hoặc là kênh đang thiếu link
            classifyVn(canonicalName(e.name)) != G_OTHER ||
                KEEP_STRICT.containsMatchIn(fold(e.name + " " + e.group)) ||
                dedupeKey(e.name) in wantedKeys
        }
        if (s.mode == "sports") l = l.filter { SPORT_RE.containsMatchIn(fold(it.name + " " + it.group)) }
        if (s.mode == "vn") l = l.filter { isVietnamese(it) }
        if (s.limit > 0) l = l.take(s.limit)
        return l
    }

    // ----- Tải & gộp -----
    // Thứ tự = thứ tự ưu tiên link: link gốc trong app -> danh sách công khai -> link của bạn -> nguồn tự tìm
    /** Nguồn lõi (có sẵn + nhóm do bạn thêm): tải xong là hiện kênh ngay, không chờ Thể thao/Phim/nguồn tự tìm. */
    private fun coreSources(): List<Source> =
        (if (builtIn) listOf(Source("Việt Nam", EXTRA_ASSET, true)) + BUILT_IN_URLS.map { Source("Việt Nam", it, true) }
         else emptyList()) + sources.filter { it.url.isNotBlank() }   // nhóm trống (không có link) không cần tải

    private fun lateSources(): List<Source> =
        (if (extras) EXTRA_SOURCES else emptyList()) +
            found.map { Source("Tìm được", it, auto = true, discovered = true) }

    private fun allSources(): List<Source> = coreSources() + lateSources()

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

    /** assets/logos.txt: mỗi dòng "Tên kênh|link logo" (bỏ qua dòng bắt đầu bằng #). */
    private val logoMap: Map<String, String> by lazy {
        try {
            ctx.assets.open("logos.txt").bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('|') }
                .associate { l -> dedupeKey(l.substringBefore('|')) to l.substringAfter('|').trim() }
                .filter { it.key.isNotEmpty() && it.value.startsWith("http", true) }
        } catch (e: Exception) { emptyMap() }
    }

    private fun nameScore(n: String) = (if (hasAccent(n)) 2 else 0) + (if (!n.contains('(')) 1 else 0)

    /** Thứ hạng nhóm khi gộp kênh trùng: số nhỏ = ưu tiên giữ kênh ở nhóm đó. -1: nhóm do người dùng tự thêm (không gộp chéo nhóm). */
    private fun groupRank(g: String): Int = when {
        g in VN_GROUPS -> VN_GROUPS.indexOf(g)
        g == G_SPORT -> 20
        g == G_MOVIE -> 21
        g == G_OTHER -> 30
        else -> -1
    }

    /**
     * Gộp kênh trùng và GIỮ LẠI mọi link (tối đa MAX_LINKS):
     *  - Trùng khít tên (sau chuẩn hoá) nhưng nằm ở nhóm khác nhau (VD "HTVC Phim" vừa ở HTVC vừa ở Phim, hoặc cùng kênh nằm
     *    ở "Khác"): gộp vào nhóm ưu tiên cao hơn (VTV > Thiết yếu > VTVcab > SCTV > HTVC > Địa phương > Thể thao > Phim > Khác).
     *  - Tên này là PHẦN ĐẦU của tên kia (ON Movies ⊂ "ON Movies - You TV", ANTV ⊂ "ANTV - TH Công an nhân dân"):
     *    cùng nhóm: tên ngắn có ≥ 2 từ (và logo không mâu thuẫn), hoặc thuộc NAME_FAMILY, hoặc cùng logo;
     *    khác nhóm: thuộc NAME_FAMILY hoặc cùng logo.
     *    Phần dư không được chứa số (Cần Thơ 2 ≠ Cần Thơ 3).
     * Nhóm do người dùng tự thêm không bị gộp chéo nhóm (đó là chủ ý của người dùng).
     * Khi gộp giữ id của kênh mà người dùng đã đánh dấu (yêu thích / đã xoá / đã di chuyển) để không mất lựa chọn đó.
     */
    private fun dedupeChannels(input: List<Channel>): List<Channel> {
        if (input.size < 2) return input
        val userIds = favorites + hidden + moved.keys
        class Node(var ch: Channel, val toks: List<String>, val pool: String)
        val nodes = input.map { c ->
            Node(c, dedupeKey(c.name).split(' ').filter { it.isNotEmpty() }, if (groupRank(c.group) >= 0) "*" else c.group)
        }
        val gone = BooleanArray(nodes.size)
        for ((_, idxs) in nodes.indices.groupBy { nodes[it].pool }) {
            if (idxs.size < 2) continue
            val sorted = idxs.sortedWith(compareBy({ nodes[it].toks.size }, { it }))
            for (ci in sorted) {
                if (gone[ci]) continue
                val c = nodes[ci]
                var best = -1
                for (ai in sorted) {
                    if (ai == ci) break                       // đã xếp tăng dần: chỉ xét kênh đứng trước (tên ngắn hơn hoặc trùng khít)
                    if (gone[ai]) continue
                    val a = nodes[ai]
                    if (c.toks.take(a.toks.size) != a.toks) continue
                    val extra = c.toks.drop(a.toks.size)
                    if (extra.any { t -> t.any { ch -> ch.isDigit() } }) continue
                    val sameGroup = a.ch.group == c.ch.group
                    val sameLogo = a.ch.logo.isNotBlank() && a.ch.logo == c.ch.logo
                    val logoConflict = a.ch.logo.isNotBlank() && c.ch.logo.isNotBlank() && a.ch.logo != c.ch.logo   // hai kênh khác nhau (Sky Sports / Sky Sports News)
                    val ok = extra.isEmpty() ||
                        (a.toks.size == 1 && a.toks[0] in NAME_FAMILY) ||
                        sameLogo ||
                        (sameGroup && a.toks.size >= 2 && !logoConflict)
                    if (ok && (best < 0 || a.toks.size > nodes[best].toks.size)) best = ai
                }
                if (best < 0) continue
                val p = nodes[best]
                val exact = p.toks.size == c.toks.size
                val group = if (groupRank(p.ch.group) <= groupRank(c.ch.group)) p.ch.group else c.ch.group
                val id = if (c.ch.id in userIds && p.ch.id !in userIds) c.ch.id else p.ch.id
                val name = if (exact && nameScore(c.ch.name) > nameScore(p.ch.name)) c.ch.name else p.ch.name
                val urls = (p.ch.urls + c.ch.urls.filter { it !in p.ch.urls }).take(MAX_LINKS)
                p.ch = p.ch.copy(id = id, name = name, group = group, logo = p.ch.logo.ifBlank { c.ch.logo }, urls = urls)
                gone[ci] = true
            }
        }
        return nodes.indices.filter { !gone[it] }.map { nodes[it].ch }
    }

    private fun merge(parts: List<Pair<Source, List<Entry>>>): List<Channel> {
        val map = LinkedHashMap<String, Channel>()
        val wantedGroup = wantedGroups()
        for ((s, entries) in parts) for (e in entries) {
            if (isRadio(e.name, e.group, e.url)) continue
            val key = channelKey(e.name)
            val dk = dedupeKey(e.name)
            if (key.isEmpty() || dk.isEmpty()) continue
            val display = canonicalName(e.name)
            val group = (if (s.discovered) wantedGroup[dk] else null) ?: if (s.auto) classifyVn(display) else s.name
            val mk = "$group|$dk"                 // khóa gộp kênh trùng
            val cur = map[mk]
            if (cur == null) map[mk] = Channel("$group|$key", display, e.logo, group, listOf(e.url))   // id giữ như cũ -> không mất yêu thích
            else {
                val urls = if (e.url !in cur.urls && cur.urls.size < MAX_LINKS) cur.urls + e.url else cur.urls
                val name = if (nameScore(display) > nameScore(cur.name)) display else cur.name         // ưu tiên tên có dấu, không có ghi chú (…)
                map[mk] = cur.copy(name = name, logo = cur.logo.ifBlank { e.logo }, urls = urls)
            }
        }
        // Gộp kênh trùng còn sót (khác nhóm, hoặc tên này là phần đầu của tên kia) mà vẫn giữ mọi link
        val deduped = dedupeChannels(map.values.toList())
        // Logo tự đặt trong assets/logos.txt luôn được ưu tiên
        val withLogo = deduped.map { c -> logoMap[dedupeKey(c.name)]?.let { c.copy(logo = it) } ?: c }
        val (auto, user) = withLogo.partition { it.group in AUTO_GROUPS }
        return auto.sortedBy { natKey(it.name) } + user
    }

    // ----- Ảnh chụp danh sách đã gộp: mở app là hiện ngay, không phải đọc + gộp lại hàng nghìn dòng -----
    private fun snapshotFile() = File(dir, "snapshot.json")

    /** Đổi phiên bản app hoặc đổi cấu hình nguồn thì ảnh chụp cũ không còn dùng. */
    private fun snapshotSig(): String {
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }
        val srcs = allSources().joinToString("|") { "${it.name}~${it.url}~${it.auto}~${it.discovered}~${it.mode}~${it.limit}" }
        return "$ver#${srcs.hashCode()}"
    }

    private fun saveSnapshot(list: List<Channel>) {
        try {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(JSONObject().put("i", c.id).put("n", c.name).put("l", c.logo).put("g", c.group).put("u", JSONArray(c.urls)))
            }
            snapshotFile().writeText(JSONObject().put("sig", snapshotSig()).put("ch", arr).toString())
        } catch (_: Exception) {}
    }

    suspend fun loadSnapshot(): List<Channel>? = withContext(Dispatchers.IO) {
        try {
            val f = snapshotFile()
            if (!f.exists()) return@withContext null
            val o = JSONObject(f.readText())
            if (o.optString("sig") != snapshotSig()) return@withContext null
            val a = o.getJSONArray("ch")
            (0 until a.length()).map { k ->
                val c = a.getJSONObject(k)
                val u = c.getJSONArray("u")
                Channel(c.getString("i"), c.getString("n"), c.optString("l"), c.getString("g"), (0 until u.length()).map { u.getString(it) })
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    suspend fun cached(): List<Channel> = withContext(Dispatchers.IO) {
        merge(allSources().map { s -> s to readCached(s) })
    }

    /** Tải tất cả nguồn (tối đa 8 cùng lúc). Nguồn nào lỗi thì dùng bản đã lưu. */
    /** [onCore] được gọi ngay khi nguồn lõi tải xong (kèm danh sách kênh của riêng nguồn lõi) để hiện kênh sớm. */
    suspend fun fetch(onCore: ((List<Channel>) -> Unit)? = null): List<Channel> = withContext(Dispatchers.IO) {
        val core = coreSources()
        val late = lateSources()
        val sem = Semaphore(8)
        val wk = wantedGroups().keys
        val gone = java.util.concurrent.ConcurrentLinkedQueue<String>()   // nguồn tự tìm đã bị xóa (404/410)
        coroutineScope {
            suspend fun one(s: Source): Pair<Source, List<Entry>>? = sem.withPermit {
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
            val coreD = core.map { async { one(it) } }
            val lateD = late.map { async { one(it) } }
            val coreRes = coreD.awaitAll()
            if (onCore != null && coreRes.any { it != null }) onCore(merge(coreRes.filterNotNull()))
            val lateRes = lateD.awaitAll()
            val results = coreRes + lateRes
            if ((core + late).isNotEmpty() && results.all { it == null }) error("không tải được nguồn nào, kiểm tra mạng hoặc link")
            if (gone.isNotEmpty()) { val g = gone.toSet(); found = found.filter { it !in g } }
            merge(results.filterNotNull()).also { saveSnapshot(it) }
        }
    }
}
