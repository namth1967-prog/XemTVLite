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
import androidx.media3.common.C
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
    allChannels: List<Channel>,
    numbers: Map<String, Int>,
    rank: suspend (List<String>) -> List<String>,
    onResult: (Channel, Boolean, String?) -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(channels) }
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, channels.lastIndex)) }
    var status by remember { mutableStateOf<String?>("Đang tải…") }
    var showInfo by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var urlIdx by remember { mutableIntStateOf(0) }
    var readyTick by remember { mutableIntStateOf(0) }
    var numBuf by remember { mutableStateOf("") }
    var numMsg by remember { mutableStateOf<String?>(null) }
    var retryCh by remember { mutableStateOf("") }      // kênh đã được tự thử lại 1 lượt
    var reloadTick by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }     // đã thử hết các nguồn mà vẫn không mở được
    var advance by remember { mutableIntStateOf(0) }     // yêu cầu chuyển sang link kế tiếp
    var advanceReason by remember { mutableStateOf("") }
    var advanceBusy by remember { mutableStateOf(false) }
    var rampTick by remember { mutableIntStateOf(0) }    // mỗi lần bắt đầu có hình: tăng âm lượng dần
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
        ExoPlayer.Builder(ctx, LevelingRenderersFactory(ctx))   // có cân bằng âm lượng + bộ giải mã dự phòng
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

    // Phát một link: âm lượng về 0 rồi tăng dần khi bắt đầu có hình (kênh to không làm giật mình)
    fun play(url: String) {
        runCatching {
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    // Link hiện tại không dùng được (lỗi / không có tiếng): chỉ GHI YÊU CẦU. Việc đổi link làm ở LaunchedEffect bên dưới,
    // không làm ngay trong hàm gọi lại của trình phát.
    fun tryNext(reason: String) {
        if (advanceBusy) return
        advanceReason = reason
        advance++
    }

    LaunchedEffect(advance) {
        if (advance == 0) return@LaunchedEffect
        advanceBusy = true
        try {
            val reason = advanceReason
            val ch = list[index]
            val cands = candidates
            cands.getOrNull(urlIdx)?.let { onResult(ch, false, it) }
            val next = urlIdx + 1
            if (next < cands.size) {
                urlIdx = next
                status = "$reason, đổi sang nguồn ${next + 1}/${cands.size}…"
                runCatching { player.stop() }
                delay(300)       // cho bộ giải mã nghỉ giữa hai lần thử, tránh dồn dập
                if (candidates === cands && list[index].id == ch.id) play(cands[next])
            } else if (retryCh != ch.id) {
                // Hết link dự phòng: đo lại toàn bộ 1 lượt (link chập chờn có thể đã chạy lại) rồi mới bỏ cuộc
                retryCh = ch.id
                status = "$reason, đang đo lại các nguồn…"
                reloadTick++
            } else {
                status = "Chưa mở được kênh ($reason)."
                failed = true
                showInfo = true
                infoTick++
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Lỗi bất ngờ: báo trên màn hình thay vì để app thoát
            status = "Chưa mở được kênh (${e.javaClass.simpleName})."
            failed = true
            showInfo = true
            infoTick++
        } finally {
            advanceBusy = false
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { tryNext("Link lỗi") }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { status = null; readyTick++; rampTick++ }
                    Player.STATE_BUFFERING -> if (status == null) status = "Đang đệm…"
                    else -> {}
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); runCatching { player.release() } }
    }

    // Đã phát được 2,5 giây: phải có luồng âm thanh dùng được, nếu không coi là kênh chết
    LaunchedEffect(readyTick) {
        if (readyTick == 0) return@LaunchedEffect
        val ch = list[index]
        val url = candidates.getOrNull(urlIdx)
        delay(2_500)
        if (list[index].id != ch.id || candidates.getOrNull(urlIdx) != url) return@LaunchedEffect
        if (player.playbackState != Player.STATE_READY) return@LaunchedEffect
        val hasAudio = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        val hasVideo = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        when {
            !hasVideo -> tryNext("Chỉ có âm thanh, không có hình")
            !hasAudio -> tryNext("Không có tiếng")
            url != null -> onResult(ch, true, url)
        }
    }

    // Đổi kênh: thử các link song song, chọn link chạy được rồi mới phát
    LaunchedEffect(list, index, reloadTick) {
        val ch = list[index]
        status = "Đang tìm nguồn tốt nhất (${ch.urls.size} link)…"
        failed = false
        showInfo = true
        infoTick++
        runCatching { player.stop() }
        val order = try {
            rank(ch.urls)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ch.urls          // đo lỗi bất ngờ thì cứ thử các link theo thứ tự có sẵn, không để app thoát
        }
        candidates = order
        urlIdx = 0
        play(order.first())
    }

    // Vào kênh: tăng âm lượng từ 0 lên bình thường trong ~0,5 giây
    LaunchedEffect(rampTick) {
        if (rampTick == 0 || player.volume >= 1f) return@LaunchedEffect
        for (i in 1..8) {
            delay(60)
            runCatching { player.volume = i / 8f }
        }
    }

    // ----- Chọn kênh bằng phím số -----
    fun jumpTo(n: Int) {
        val t = allChannels.firstOrNull { numbers[it.id] == n }
        if (t == null) { numMsg = "Không có kênh $n"; return }
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) index = i
        else { list = allChannels; index = allChannels.indexOfFirst { it.id == t.id } }
    }
    LaunchedEffect(Unit) {
        KeyBus.digits.collect { d -> if (numBuf.length < 4) numBuf += d.toString() }
    }
    LaunchedEffect(numBuf) {
        if (numBuf.isEmpty()) return@LaunchedEffect
        val n = numBuf.toInt()
        if (n * 10 > allChannels.size) { jumpTo(n); numBuf = "" }   // không thể còn số dài hơn -> chuyển ngay
        else { delay(1_500); jumpTo(n); numBuf = "" }
    }
    LaunchedEffect(numMsg) { if (numMsg != null) { delay(2_000); numMsg = null } }

    // Tự ẩn thông tin sau 3 giây
    LaunchedEffect(infoTick) { delay(3_000); showInfo = false }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun go(delta: Int) {
        val n = list.size
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
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
                        if (numBuf.isNotEmpty()) { jumpTo(numBuf.toInt()); numBuf = ""; true } else false
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
        if (failed) {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(horizontal = 40.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Chưa mở được kênh này", fontSize = 28.sp, color = Color.White)
                Text("Kênh vẫn được giữ trong danh sách (có thể chỉ tạm thời bị lỗi).", fontSize = 16.sp, color = Color(0xFFCCCCCC))
                Text("Bấm ↑/↓ để đổi kênh, Back để quay lại. Thử lại sau bằng Cài đặt → Thử lại các kênh đang chết.", fontSize = 16.sp, color = Color(0xFFFFC857))
            }
        }
        if (numBuf.isNotEmpty() || numMsg != null) {
            Text(
                numMsg ?: numBuf,
                Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xAA000000)).padding(horizontal = 24.dp, vertical = 12.dp),
                fontSize = 48.sp, color = Color(0xFFFFC857),
            )
        }
        AnimatedVisibility(showInfo || status != null, Modifier.align(Alignment.BottomStart)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(horizontal = 32.dp, vertical = 20.dp)
            ) {
                Text("${numbers[list[index].id] ?: (index + 1)}   ${list[index].name}", fontSize = 24.sp, color = Color.White)
                status?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFFC857)) }
            }
        }
    }
}
