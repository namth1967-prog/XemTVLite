package vn.xemtv.lite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Tự tìm file playlist .m3u của các kho mã công khai trên GitHub (giống app GetOut Lite).
 * Không có token GitHub: ~10 lượt tìm/phút và 60 lượt gọi/giờ -> sẽ dừng sớm khi hết lượt.
 * Có token (do bạn tự nhập): nhiều lượt hơn.
 */
class Discovery(private val token: String) {
    var problem: String? = null   // "token" | "quota" | "burst"
        private set

    private class Stop : Exception()
    private class Res(val code: Int, val remaining: String?, val retry: String?, val body: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val queries = listOf(
        "iptv vietnam", "iptv vn m3u", "iptv vietnam sports", "vietnam tv playlist m3u8",
        "iptv free vietnam github", "m3u8 vtv thvl htv hd", "kenh the thao m3u", "iptv worldwide sports m3u",
        "vtv htv m3u", "iptv the thao phim viet", "truyen hinh viet nam m3u", "iptv vtvcab sctv thvl",
        "vietnam m3u8", "viet iptv playlist", "vn iptv list", "vtv m3u8 github", "free iptv vietnam live",
        "vietnamese tv channels m3u", "iptv sctv htvc", "kenh phim viet nam m3u", "tv vietnam live stream",
        "iptv asia m3u", "iptv playlist updated daily", "htv thvl vtv playlist",
    )

    private fun call(url: String): Res {
        val b = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        return client.newCall(b.build()).execute().use { r ->
            Res(r.code, r.header("x-ratelimit-remaining"), r.header("retry-after"),
                if (r.isSuccessful) r.body!!.string() else "")
        }
    }

    private suspend fun getJson(url: String, retried: Boolean = false): String {
        val r = withContext(Dispatchers.IO) { call(url) }
        when {
            r.code == 401 -> { problem = "token"; throw Stop() }
            r.code == 403 || r.code == 429 -> {
                if (r.remaining == "0") { problem = "quota"; throw Stop() }
                if (!retried) {
                    delay((r.retry?.toLongOrNull() ?: 20L).coerceIn(5L, 60L) * 1000)
                    return getJson(url, true)
                }
                problem = "burst"; throw Stop()
            }
            r.code !in 200..299 -> error("HTTP ${r.code}")
        }
        return r.body
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Trả về danh sách link raw của các file .m3u tìm được. */
    suspend fun run(onProgress: (String) -> Unit): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val since = fmt.format(Date(System.currentTimeMillis() - 365L * 86_400_000))
        try {
            for ((qi, q) in queries.withIndex()) {
                onProgress("Đang tìm nguồn mới trên GitHub (${qi + 1}/${queries.size}) – đã có ${out.size} file…")
                val repos = try {
                    if (qi > 0) delay(if (token.isNotEmpty()) 2200 else 6500)
                    val url = "https://api.github.com/search/repositories?q=${enc("$q pushed:>$since")}&sort=updated&per_page=15"
                    JSONObject(getJson(url)).optJSONArray("items")
                } catch (e: Stop) { throw e } catch (e: Exception) { null } ?: continue

                for (i in 0 until repos.length()) {
                    val repo = repos.getJSONObject(i)
                    val full = repo.optString("full_name")
                    val branch = repo.optString("default_branch")
                    if (full.isEmpty() || branch.isEmpty() || !seen.add(full)) continue
                    if (repo.optLong("size") > 100_000) continue
                    try {
                        delay(150)
                        val tree = JSONObject(getJson("https://api.github.com/repos/$full/git/trees/${enc(branch)}?recursive=1"))
                            .optJSONArray("tree") ?: continue
                        val files = ArrayList<String>()
                        for (k in 0 until tree.length()) {
                            val t = tree.getJSONObject(k)
                            val p = t.optString("path")
                            if (t.optString("type") == "blob" && (p.endsWith(".m3u", true) || p.endsWith(".m3u8", true)) && t.optLong("size") in 300L..5_000_000L) files += p
                        }
                        files.sortBy { if (KEEP_RE.containsMatchIn(fold(it))) 0 else 1 }
                        for (p in files.take(8)) {
                            out += "https://raw.githubusercontent.com/$full/$branch/${p.split("/").joinToString("/") { enc(it) }}"
                        }
                    } catch (e: Stop) { throw e } catch (_: Exception) { }
                }
            }
        } catch (_: Stop) { }
        return out
    }
}

/** Kiểm tra token GitHub bằng cách hỏi hạn mức (rate_limit). Trả về câu thông báo cho người dùng. */
suspend fun checkGithubToken(token: String): String = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        val b = Request.Builder().url("https://api.github.com/rate_limit").header("Accept", "application/vnd.github+json")
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        client.newCall(b.build()).execute().use { r ->
            when {
                r.code == 401 -> "✘ Token SAI hoặc đã hết hạn. Hãy kiểm tra lại từng ký tự."
                !r.isSuccessful -> "✘ GitHub trả lỗi HTTP ${r.code}."
                else -> {
                    val res = JSONObject(r.body!!.string()).getJSONObject("resources")
                    val search = res.getJSONObject("search")
                    val core = res.getJSONObject("core")
                    if (token.isEmpty())
                        "Chưa nhập token: dùng chế độ không đăng nhập (tìm ${search.getInt("limit")} lượt/phút, gọi ${core.getInt("limit")} lượt/giờ)."
                    else
                        "✔ Token ĐÚNG. Tìm: còn ${search.getInt("remaining")}/${search.getInt("limit")} lượt/phút; gọi: còn ${core.getInt("remaining")}/${core.getInt("limit")} lượt/giờ."
                }
            }
        }
    } catch (e: Exception) {
        "✘ Không kết nối được GitHub (${e.message ?: "lỗi mạng"})."
    }
}
