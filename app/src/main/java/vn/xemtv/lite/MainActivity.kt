package vn.xemtv.lite

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

private val Colors = darkColorScheme(
    primary = Color(0xFFFF4D6D),
    background = Color(0xFF0F1420),
    surface = Color(0xFF1A2133),
    onSurface = Color.White,
    onBackground = Color.White,
)

class MainActivity : ComponentActivity() {
    // Phím số: chuyển cho màn hình đang mở (danh sách hoặc trình phát) để chọn kênh theo số
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val d = digitOf(e.keyCode)
            if (d != null) { KeyBus.digits.tryEmit(d); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(color = Colors.background, contentColor = Color.White) { App() }
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()

    // Thứ tự và SỐ KÊNH: VTV, VTVcab, SCTV, HTVC, Địa phương, Thể thao, Phim, nhóm của bạn, Khác
    val ordered = remember(ui.alive, ui.sources) {
        orderChannels(ui.alive, ui.sources.filter { !it.auto }.map { it.name })
    }
    val numbers = remember(ordered) { ordered.withIndex().associate { it.value.id to it.index + 1 } }

    val shown = remember(ordered, ui.favorites, ui.group) {
        ordered.filter {
            when (ui.group) {
                "" -> true
                FAV -> it.id in ui.favorites
                else -> it.group == ui.group
            }
        }
    }

    var playList by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playIndex by remember { mutableIntStateOf(-1) }

    if (playIndex >= 0 && playList.isNotEmpty()) {
        BackHandler { playIndex = -1 }
        PlayerScreen(
            channels = playList,
            startIndex = playIndex,
            allChannels = ordered,
            numbers = numbers,
            rank = vm::rank,
            onResult = vm::report,
            onExit = { playIndex = -1 },
        )
    } else {
        HomeScreen(
            ui = ui,
            shown = shown,
            numbers = numbers,
            onPlayNumber = { n ->
                ordered.firstOrNull { numbers[it.id] == n }?.let { t ->
                    val i = shown.indexOf(t)
                    if (i >= 0) { playList = shown; playIndex = i } else { playList = ordered; playIndex = ordered.indexOf(t) }
                }
            },
            onGroup = vm::setGroup,
            onFav = vm::toggleFav,
            onReload = vm::load,
            onAddSource = vm::addSource,
            onRemoveSource = vm::removeSource,
            onToggleBuiltIn = vm::toggleBuiltIn,
            onToggleExtras = vm::toggleExtras,
            onScan = { vm.scan(ScanMode.ALL) },
            onScanDead = { vm.scan(ScanMode.DEAD) },
            onToggleAutoFill = vm::toggleAutoFill,
            onToken = vm::setToken,
            onCheckToken = vm::checkToken,
            onDiscover = { vm.discover() },
            onClearFound = vm::clearFound,
            onPlay = { c ->
                playList = shown
                playIndex = shown.indexOf(c)
            },
        )
    }
}
