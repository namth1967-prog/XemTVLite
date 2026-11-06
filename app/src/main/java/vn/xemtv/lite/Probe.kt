package vn.xemtv.lite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kiểm tra link có thật sự phát được không (giống app mẫu):
 * tải manifest HLS -> (nếu có nhiều chất lượng thì vào bản đầu) -> tải thử đoạn video mới nhất.
 */
class Prober {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private class Resp(val code: Int, val body: ByteArray, val finalUrl: String, val type: String, val ms: Long)

    private fun get(url: String, maxBytes: Long, calls: ConcurrentLinkedQueue<Call>): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) XemTVLite/1.2")
            .build()
        val call = client.newCall(req)
        calls.add(call)
        val t0 = System.currentTimeMillis()
        call.execute().use { r ->
            val src = r.body!!.source()
            src.request(maxBytes)               // đọc tối đa maxBytes (hoặc tới hết dữ liệu)
            val n = minOf(src.buffer.size, maxBytes)
            return Resp(r.code, src.buffer.readByteArray(n), r.request.url.toString(), r.header("Content-Type").orEmpty(),
                System.currentTimeMillis() - t0)
        }
    }

    private fun looksHtml(r: Resp): Boolean {
        if (r.type.contains("text/html", true)) return true
        val head = String(r.body, 0, minOf(r.body.size, 64), Charsets.UTF_8).trimStart()
        return head.startsWith("<")
    }

    private fun resolve(base: String, ref: String): String? =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString()

    private fun uris(text: String) = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    /** Đủ dữ liệu video và tải đủ nhanh (>= ~100 KB/s) thì mới coi là chạy được. */
    private fun healthy(r: Resp): Boolean {
        if (r.code !in 200..299 || looksHtml(r)) return false
        if (r.body.size < 30_000) return false
        val bytesPerSec = r.body.size * 1000L / maxOf(r.ms, 1L)
        return bytesPerSec >= 100_000
    }

    fun check(url: String, calls: ConcurrentLinkedQueue<Call> = ConcurrentLinkedQueue()): Boolean {
        try {
            val r = get(url, 128 * 1024, calls)
            if (r.code !in 200..299 || r.body.isEmpty()) return false
            val text = String(r.body, Charsets.UTF_8)
            if (!text.trimStart().startsWith("#EXTM3U")) {
                // Luồng trực tiếp (TS/MP4/...): có dữ liệu và không phải trang web
                return healthy(r)
            }
            var media = text
            var base = r.finalUrl
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variantUrl = uris(text).firstOrNull()?.let { resolve(base, it) } ?: return false
                val r2 = get(variantUrl, 128 * 1024, calls)
                if (r2.code !in 200..299) return false
                media = String(r2.body, Charsets.UTF_8)
                if (!media.trimStart().startsWith("#EXTM3U")) return false
                base = r2.finalUrl
            }
            val segUrl = uris(media).lastOrNull()?.let { resolve(base, it) } ?: return false
            val s = get(segUrl, 200_000, calls)
            if (Regex("""\.ts(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(segUrl) && !media.contains("#EXT-X-KEY") && s.body.isNotEmpty() && s.body[0] != 0x47.toByte()) return false
            return healthy(s)
        } catch (e: Exception) {
            return false
        }
    }

    /** Thử các link song song, link nào hợp lệ trước thì thắng. Không có link nào tốt -> null. */
    suspend fun best(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val calls = ConcurrentLinkedQueue<Call>()
            val winner = CompletableDeferred<String?>()
            val left = AtomicInteger(urls.size)
            try {
                coroutineScope {
                    urls.forEach { u ->
                        launch {
                            if (check(u, calls)) winner.complete(u)
                            if (left.decrementAndGet() == 0) winner.complete(null)
                        }
                    }
                    val r = winner.await()
                    calls.forEach { it.cancel() }   // dừng các link còn lại cho nhanh
                    r
                }
            } finally {
                calls.forEach { it.cancel() }
            }
        }
    }
}
