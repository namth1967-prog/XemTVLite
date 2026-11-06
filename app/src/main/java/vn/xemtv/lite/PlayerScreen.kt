package vn.xemtv.lite

import android.app.Activity
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    startIndex: Int,
    rank: suspend (List<String>) -> List<String>,
    onResult: (Channel, Boolean, String?) -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var status by remember { mutableStateOf<String?>("Đang tải…") }
    var showInfo by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var urlIdx by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent("Mozilla/5.0 (Linux; Android) XemTVLite/1.0")
        // Bộ đệm nhỏ -> vào kênh nhanh, ít tốn RAM.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 30_000, 1_500, 3_000)
            .build()
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
    }

    // Toàn màn hình + giữ sáng màn hình
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        val view: View? = window?.decorView
        val ctl = if (window != null && view != null) WindowInsetsControllerCompat(window, view) else null
        if (window != null) WindowCompat.setDecorFitsSystemWindows(window, false)
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val next = urlIdx + 1
                if (next < candidates.size) {
                    // Link lỗi -> tự đổi sang link dự phòng của cùng kênh
                    urlIdx = next
                    status = "Link lỗi, đổi sang nguồn ${next + 1}/${candidates.size}…"
                    player.setMediaItem(MediaItem.fromUri(candidates[next]))
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    status = "Kênh hiện không phát được. Bấm ↑/↓ để đổi kênh."
                    showInfo = true
                    infoTick++
                    onResult(channels[index], false, null)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        status = null
                        candidates.getOrNull(urlIdx)?.let { onResult(channels[index], true, it) }
                    }
                    Player.STATE_BUFFERING -> if (status == null) status = "Đang đệm…"
                    else -> {}
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    // Đổi kênh: thử các link song song, chọn link chạy được rồi mới phát
    LaunchedEffect(index) {
        val ch = channels[index]
        status = "Đang tìm nguồn tốt nhất (${ch.urls.size} link)…"
        showInfo = true
        infoTick++
        player.stop()
        val order = rank(ch.urls)
        candidates = order
        urlIdx = 0
        player.setMediaItem(MediaItem.fromUri(order.first()))
        player.prepare()
        player.playWhenReady = true
    }
    // Tự ẩn thông tin sau 3 giây
    LaunchedEffect(infoTick) { delay(3_000); showInfo = false }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun go(delta: Int) {
        val n = channels.size
        index = ((index + delta) % n + n) % n
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { go(1); true }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { go(-1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                showInfo = !showInfo
                infoTick++
            }
    ) {
        val exo = player
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = exo
                    keepScreenOn = true
                    isFocusable = false
                }
            },
        )
        AnimatedVisibility(showInfo || status != null, Modifier.align(Alignment.BottomStart)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(horizontal = 32.dp, vertical = 20.dp)
            ) {
                Text("${index + 1}/${channels.size}  ${channels[index].name}", fontSize = 22.sp, color = Color.White)
                status?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFFC857)) }
            }
        }
    }
}
