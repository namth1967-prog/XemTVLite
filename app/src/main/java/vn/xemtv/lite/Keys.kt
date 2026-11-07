package vn.xemtv.lite

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow

/** Phím số bấm bất cứ lúc nào (kể cả khi chưa chọn ô nào) đều được MainActivity chuyển vào đây. */
object KeyBus {
    val digits = MutableSharedFlow<Int>(extraBufferCapacity = 16)
}

fun digitOf(code: Int): Int? = when (code) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}
