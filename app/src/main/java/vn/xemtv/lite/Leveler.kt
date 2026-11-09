package vn.xemtv.lite

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/** Bật/tắt cân bằng âm lượng (đặt từ Cài đặt). */
object Leveler {
    @Volatile var enabled: Boolean = true
}

/**
 * Tự chỉnh mức tiếng về một mức vừa phải, để chuyển kênh không bị "nhỏ rồi bỗng rất to".
 * - Đo mức tiếng (RMS) từng đoạn ngắn rồi làm mượt.
 * - Quá to: giảm nhanh. Quá nhỏ: tăng chậm (khoảng vài giây) để không nghe thấy "bơm" tiếng.
 * - Vài khối đầu của mỗi lần phát: chỉnh ngay để kênh to không làm giật mình.
 * - Đoạn im lặng không làm tăng gain (tránh khuếch đại nhiễu).
 * - Bộ giới hạn mềm chặn méo tiếng khi khuếch đại.
 * Chỉ xử lý PCM 16-bit (đa số kênh AAC); định dạng khác (AC3 passthrough…) được bỏ qua, không ảnh hưởng.
 */
@UnstableApi
class LevelerProcessor : BaseAudioProcessor() {
    private var level = 0.0     // mức RMS đã làm mượt (0..1)
    private var gain = 1.0      // hệ số khuếch đại hiện tại
    private var warm = 0        // số khối có tiếng đã xử lý kể từ lúc bắt đầu/flush

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush() {
        level = 0.0
        gain = 1.0
        warm = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining < 2) return
        val out = replaceOutputBuffer(remaining)
        val inp = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val start = inputBuffer.position()
        val n = remaining / 2

        var sum = 0.0
        for (i in 0 until n) {
            val v = inp.getShort(start + i * 2).toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / n) / 32768.0

        var g1 = gain
        if (!Leveler.enabled) {
            g1 = 1.0
        } else if (rms > SILENCE) {
            level = when {
                warm == 0 -> rms
                rms > level -> level + (rms - level) * 0.5
                else -> level + (rms - level) * 0.02
            }
            val desired = (TARGET / level).coerceIn(MIN_GAIN, MAX_GAIN)
            g1 = when {
                warm < WARM_BLOCKS -> desired
                desired < gain -> gain + (desired - gain) * 0.4
                else -> gain + (desired - gain) * 0.004
            }
            warm++
        }

        // Áp dụng gain, nội suy tuyến tính trong khối để không bị "lách tách"
        val dg = (g1 - gain) / n
        var g = gain
        for (i in 0 until n) {
            var y = inp.getShort(start + i * 2) * g / 32768.0
            val a = abs(y)
            if (a > KNEE) y = sign(y) * (KNEE + (1.0 - KNEE) * tanh((a - KNEE) / (1.0 - KNEE)))
            out.putShort((y * 32767.0).toInt().coerceIn(-32768, 32767).toShort())
            g += dg
        }
        gain = g1
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private companion object {
        const val TARGET = 0.164       // mức RMS mong muốn (~ -16 dBFS); 0.07 -> 0.091 (+30%) -> 0.109 (+20%) -> 0.164 (+50%)
        const val MIN_GAIN = 0.5       // giảm tối đa ~6 dB
        const val MAX_GAIN = 9.3       // tăng tối đa ~19 dB
        const val SILENCE = 0.003      // dưới ngưỡng này coi là im lặng
        const val WARM_BLOCKS = 8
        const val KNEE = 0.85
    }
}

/** Trình phát dùng bộ cân bằng âm lượng; bật bộ giải mã dự phòng để ít lỗi/treo khi gặp luồng lạ. */
@UnstableApi
class LevelingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(LevelerProcessor()))
            .build()
}
