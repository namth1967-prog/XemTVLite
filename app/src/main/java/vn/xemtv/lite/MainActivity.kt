package vn.xemtv.lite

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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
        CrashLog.install(this)
        // Bộ tải logo: giả User-Agent trình duyệt (nhiều trang chặn UA mặc định -> logo không hiện) và đọc được ảnh SVG
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient {
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                                    .build()
                            )
                        }
                        .build()
                }
                .components { add(SvgDecoder.Factory()) }
                .build()
        )
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
    val ordered = remember(ui.alive, ui.sources, ui.customNumbers, ui.groupOrder) {
        applyNumbers(orderChannels(ui.alive, ui.sources.filter { !it.auto }.map { it.name }, ui.groupOrder), ui.customNumbers)
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

    var lastTop by remember { mutableStateOf("") }   // nút đã focus trước khi vào danh sách kênh / mở kênh
    var lastPlayed by remember { mutableStateOf("") }   // id kênh đang xem lúc thoát: Back sẽ focus vào ô kênh đó
    var playList by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playIndex by remember { mutableIntStateOf(-1) }

    if (playIndex >= 0 && playList.isNotEmpty()) {
        BackHandler {
            // Kênh đang xem nằm ngoài danh sách đang hiện (vd nhập số kênh): chuyển sang "Tất cả" để focus được vào ô của nó
            val id = lastPlayed
            if (id.isNotEmpty() && shown.none { it.id == id } && ordered.any { it.id == id }) vm.setGroup("")
            playIndex = -1
        }
        PlayerScreen(
            channels = playList,
            startIndex = playIndex,
            allChannels = ordered,
            numbers = numbers,
            rank = { urls, pref -> vm.rank(urls, pref) },
            preferred = ui.preferred,
            ok = ui.ok,
            dead = ui.dead,
            scoreOf = vm::linkScore,
            onPickLink = vm::setPreferred,
            onResult = vm::report,
            onDeleteLink = vm::deleteLink,
            heightOf = vm::linkHeight,
            onHeight = vm::reportHeight,
            onCurrent = { lastPlayed = it.id },
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
            restoreKey = lastTop,
            focusChannelId = lastPlayed,
            onTopFocus = { lastTop = it },
            onGroup = vm::setGroup,
            onFav = vm::toggleFav,
            onReload = { vm.load(manual = true) },
            onAddSource = vm::addSource,
            onRemoveSource = vm::removeSource,
            onToggleBuiltIn = vm::toggleBuiltIn,
            onToggleExtras = vm::toggleExtras,
            onScan = { vm.scan(ScanMode.ALL) },
            onScanDead = { vm.scan(ScanMode.DEAD) },
            onToggleAutoFill = vm::toggleAutoFill,
            onToggleLeveler = vm::toggleLeveler,
            onHide = vm::hideChannel,
            onMove = vm::moveChannel,
            onMerge = vm::mergeChannel,
            onUnmerge = vm::unmerge,
            onSetLogo = vm::setLogo,
            onDeleteLink = vm::deleteLink,
            onRenameGroup = vm::renameGroup,
            onMoveGroup = vm::setGroupOrder,
            onDeleteGroup = vm::deleteGroup,
            heightOf = vm::linkHeight,
            onSetNumber = vm::setNumber,
            onClearNumber = vm::clearNumber,
            onPickLink = vm::setPreferred,
            scoreOf = vm::linkScore,
            onRestore = vm::restoreChannels,
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
