package vn.xemtv.lite

import android.os.Bundle
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

    val shown = remember(ui.alive, ui.favorites, ui.query, ui.group) {
        ui.alive.asSequence()
            .filter {
                when (ui.group) {
                    "" -> true
                    FAV -> it.id in ui.favorites
                    else -> it.group == ui.group
                }
            }
            .filter { ui.query.isBlank() || fold(it.name).contains(fold(ui.query)) }
            .toList()
    }

    var playList by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playIndex by remember { mutableIntStateOf(-1) }

    if (playIndex >= 0 && playList.isNotEmpty()) {
        BackHandler { playIndex = -1 }
        PlayerScreen(
            channels = playList,
            startIndex = playIndex,
            rank = vm::rank,
            onResult = vm::report,
            onExit = { playIndex = -1 },
        )
    } else {
        HomeScreen(
            ui = ui,
            shown = shown,
            onQuery = vm::setQuery,
            onGroup = vm::setGroup,
            onFav = vm::toggleFav,
            onReload = vm::load,
            onAddSource = vm::addSource,
            onRemoveSource = vm::removeSource,
            onToggleBuiltIn = vm::toggleBuiltIn,
            onScan = { vm.scan(true) },
            onToken = vm::setToken,
            onDiscover = vm::discover,
            onClearFound = vm::clearFound,
            onPlay = { c ->
                playList = shown
                playIndex = shown.indexOf(c)
            },
        )
    }
}
