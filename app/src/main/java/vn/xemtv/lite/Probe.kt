package vn.xemtv.lite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kiểm tra link có thật sự phát được không (giống app mẫu):
 * tải manifest HLS -> (nếu có nhiều chất lượng thì vào bản đầu) -> tải thử đoạn video mới nhất.
 * Kết quả là ĐIỂM chất lượng: 0 = chết, > 0 = chạy được (càng cao càng tốt), -1 = bị hủy giữa chừng.
 */
private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x8A, 0x06)
private val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24, 0x42, 0xD1)
private val VIDEO_CODECS = listOf("avc", "hvc", "hev", "mp4v", "vp0", "vp9", "av01", "dvh")
private val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "opus", "mp3", "flac", "ac-4")

/** Đọc bảng PMT của luồng MPEG-TS, trả về các loại stream (video/audio…). null = không đọc được. */
fun tsStreamTypes(d: ByteArray): Set<Int>? {
    try {
        var off = -1
        for (i in 0 until minOf(188, d.size)) {
            if (d[i] == 0x47.toByte() && (i + 188 >= d.size || d[i + 188] == 0x47.toByte())) { off = i; break }
        }
        if (off < 0) return null
        var pmtPid = -1
        var p = off
        while (p + 188 <= d.size) {
            val pid = ((d[p + 1].toInt() and 0x1F) shl 8) or (d[p + 2].toInt() and 0xFF)
            val pusi = (d[p + 1].toInt() and 0x40) != 0
            val afc = (d[p + 3].toInt() shr 4) and 3
            if (afc != 2 && pusi && (pid == 0 || pid == pmtPid)) {
                var q = p + 4
                if (afc == 3) q += 1 + (d[p + 4].toInt() and 0xFF)
                val end = p + 188
                if (q < end) {
                    val t = q + 1 + (d[q].toInt() and 0xFF)
                    if (t + 12 < end) {
                        val table = d[t].toInt() and 0xFF
                        val secLen = ((d[t + 1].toInt() and 0x0F) shl 8) or (d[t + 2].toInt() and 0xFF)
                        val secEnd = minOf(t + 3 + secLen - 4, end)
                        if (pid == 0 && table == 0) {
                            var e = t + 8
                            while (e + 4 <= secEnd) {
                                val prog = ((d[e].toInt() and 0xFF) shl 8) or (d[e + 1].toInt() and 0xFF)
                                if (prog != 0) { pmtPid = ((d[e + 2].toInt() and 0x1F) shl 8) or (d[e + 3].toInt() and 0xFF); break }
                                e += 4
                            }
                        } else if (pid == pmtPid && table == 2) {
                            val infoLen = ((d[t + 10].toInt() and 0x0F) shl 8) or (d[t + 11].toInt() and 0xFF)
                            var e = t + 12 + infoLen
                            val types = HashSet<Int>()
                            while (e + 5 <= secEnd) {
                                types += d[e].toInt() and 0xFF
                                e += 5 + (((d[e + 3].toInt() and 0x0F) shl 8) or (d[e + 4].toInt() and 0xFF))
                            }
                            return types
                        }
                    }
                }
            }
            p += 188
        }
    } catch (e: Exception) { }
    return null
}

/** Gom các cuộc gọi mạng đang chạy để hủy cùng lúc. */
/** Kết quả probe: link lỗi cứng (404, sai tên miền, từ chối kết nối) - không cần đo lại. */
const val HARD_DEAD = -2

class ProbeCtl {
    val calls = ConcurrentLinkedQueue<Call>()
    @Volatile var stopped = false
    fun stop() { stopped = true; calls.forEach { it.cancel() } }
}

class Prober {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private class Resp(val code: Int, val body: ByteArray, val finalUrl: String, val type: String, val ms: Long)

    private fun get(url: String, maxBytes: Long, ctl: ProbeCtl): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) XemTVLite/1.2")
            .build()
        val call = client.newCall(req)
        ctl.calls.add(call)
        if (ctl.stopped) call.cancel()
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

    /** Điểm chất lượng = tốc độ tải đoạn video (KB/s, tối đa 4000) + băng thông khai báo (tối đa 500). */
    private fun scoreOf(r: Resp, bandwidth: Long): Int {
        val kbps = (r.body.size * 1000L / maxOf(r.ms, 1L) / 1000L).toInt()
        return maxOf(1, minOf(kbps, 4000) + minOf(bandwidth / 10_000L, 500L).toInt())
    }

    /** 0 = chết, > 0 = điểm chất lượng, -1 = bị hủy giữa chừng (chưa biết). */
    fun probe(url: String, ctl: ProbeCtl = ProbeCtl()): Int {
        try {
            val r = get(url, 128 * 1024, ctl)
            if (r.code in 400..499) return HARD_DEAD
            if (r.code !in 200..299 || r.body.isEmpty()) return 0
            val text = String(r.body, Charsets.UTF_8)
            if (!text.trimStart().startsWith("#EXTM3U")) {
                // Luồng trực tiếp (TS/MP4/...): có dữ liệu và không phải trang web
                return if (healthy(r)) scoreOf(r, 0) else 0
            }
            var media = text
            var base = r.finalUrl
            var bandwidth = 0L
            if (text.contains("#EXT-X-STREAM-INF")) {
                // Danh sách chất lượng khai báo toàn video (không có codec âm thanh, không có nhóm audio riêng) -> không có tiếng
                val info = text.lineSequence().firstOrNull { it.startsWith("#EXT-X-STREAM-INF") }.orEmpty()
                val codecs = Regex("CODECS=\"([^\"]*)\"").find(info)?.groupValues?.get(1)?.lowercase().orEmpty()
                if (codecs.isNotEmpty() && AUDIO_CODECS.none { codecs.contains(it) } && !text.contains("TYPE=AUDIO")) return 0
                if (codecs.isNotEmpty() && VIDEO_CODECS.none { codecs.contains(it) }) return 0   // chỉ có âm thanh
                bandwidth = Regex("BANDWIDTH=(\\d+)").findAll(text).maxOfOrNull { it.groupValues[1].toLongOrNull() ?: 0L } ?: 0L
                val variantUrl = uris(text).firstOrNull()?.let { resolve(base, it) } ?: return 0
                val r2 = get(variantUrl, 128 * 1024, ctl)
                if (r2.code !in 200..299) return 0
                media = String(r2.body, Charsets.UTF_8)
                if (!media.trimStart().startsWith("#EXTM3U")) return 0
                base = r2.finalUrl
            }
            val segUrl = uris(media).lastOrNull()?.let { resolve(base, it) } ?: return 0
            val s = get(segUrl, 200_000, ctl)
            if (Regex("""\.ts(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(segUrl) && !media.contains("#EXT-X-KEY") && s.body.isNotEmpty() && s.body[0] != 0x47.toByte()) return 0
            if (!healthy(s)) return 0
            // Luồng TS có video nhưng không có audio -> coi là chết
            val types = tsStreamTypes(s.body)
            if (types != null && (types.none { it in AUDIO_TYPES } || types.none { it in VIDEO_TYPES })) return 0
            return scoreOf(s, bandwidth)
        } catch (e: java.net.UnknownHostException) {
            return if (ctl.stopped) -1 else HARD_DEAD
        } catch (e: java.net.ConnectException) {
            return if (ctl.stopped) -1 else HARD_DEAD
        } catch (e: Exception) {
            return if (ctl.stopped) -1 else 0
        }
    }

    /**
     * Đo song song các link: chờ link đầu tiên chạy được, cho thêm [graceMs] để các link khác kịp đo,
     * rồi hủy phần còn lại. Trả về url -> điểm (0 = lỗi). Link chưa kịp đo thì không có trong kết quả.
     */
    suspend fun measure(urls: List<String>, graceMs: Long = 2_000): Map<String, Int> {
        if (urls.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val ctl = ProbeCtl()
            val res = ConcurrentHashMap<String, Int>()
            val first = CompletableDeferred<Unit>()
            val left = AtomicInteger(urls.size)
            try {
                coroutineScope {
                    val jobs = urls.map { u ->
                        launch {
                            val sc = probe(u, ctl)
                            if (sc != -1) res[u] = sc
                            if (sc > 0) first.complete(Unit)
                            if (left.decrementAndGet() == 0) first.complete(Unit)
                        }
                    }
                    first.await()
                    withTimeoutOrNull(graceMs) { jobs.joinAll() }
                    ctl.stop()
                }
            } finally {
                ctl.stop()
            }
            res.toMap()
        }
    }
}
