package vn.xemtv.lite

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val White = Color.White
private val Muted = Color(0xFFB8C0D0)
private val CardBg = Color(0xFF1E2740)

@Composable
private fun tfColors() = TextFieldDefaults.colors(
    focusedTextColor = White, unfocusedTextColor = White,
    focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
    focusedPlaceholderColor = Muted, unfocusedPlaceholderColor = Muted,
    focusedLabelColor = Muted, unfocusedLabelColor = Muted,
    cursorColor = White,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = Color.Transparent,
)

@Composable
fun HomeScreen(
    ui: UiState,
    shown: List<Channel>,
    onQuery: (String) -> Unit,
    onGroup: (String) -> Unit,
    onFav: (Channel) -> Unit,
    onReload: () -> Unit,
    onAddSource: (String, String) -> Unit,
    onRemoveSource: (Source) -> Unit,
    onToggleBuiltIn: () -> Unit,
    onScan: () -> Unit,
    onToken: (String) -> Unit,
    onDiscover: () -> Unit,
    onClearFound: () -> Unit,
    onPlay: (Channel) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    // Các nhóm có kênh, theo thứ tự: nhóm VN cố định -> nhóm do bạn đặt -> Khác
    val counts = remember(ui.alive) { ui.alive.groupingBy { it.group }.eachCount() }
    val order = remember(ui.sources) {
        VN_GROUPS + ui.sources.filter { !it.auto }.map { it.name } + G_OTHER
    }
    val groups = remember(counts, order) { order.distinct().filter { (counts[it] ?: 0) > 0 } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Xem TV Lite v$ver", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            TextField(
                value = ui.query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("Tìm kênh…") },
                colors = tfColors(),
                modifier = Modifier.weight(1f),
            )
            Pill("Tải lại", false, onReload)
            Pill("Cài đặt", false) { showSettings = true }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Pill("Tất cả", ui.group == "") { onGroup("") } }
            item { Pill("★ Yêu thích", ui.group == FAV) { onGroup(FAV) } }
            items(groups) { g -> Pill("$g (${counts[g]})", ui.group == g) { onGroup(g) } }
        }
        if (ui.scanTotal > 0) {
            Spacer(Modifier.height(6.dp))
            Text("Đang kiểm tra kênh ${ui.scanDone}/${ui.scanTotal} · đang hiện ${ui.alive.size} kênh sống", fontSize = 14.sp, color = Muted)
        }
        Spacer(Modifier.height(14.dp))

        Box(Modifier.fillMaxSize()) {
            if (shown.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(shown, key = { it.id }) { c ->
                        ChannelCard(c, c.id in ui.favorites, { onPlay(c) }, { onFav(c) })
                    }
                }
            }
            val err = ui.error
            when {
                ui.loading && shown.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = White)
                err != null -> Text(err, Modifier.align(Alignment.Center), color = Color(0xFFFF9A9A), fontSize = 18.sp, textAlign = TextAlign.Center)
                shown.isEmpty() && ui.scanTotal > 0 -> Text(
                    "Đang kiểm tra kênh…\nKênh còn sống sẽ hiện dần ở đây.",
                    Modifier.align(Alignment.Center), color = Muted, fontSize = 18.sp, textAlign = TextAlign.Center,
                )
                shown.isEmpty() -> Text(
                    if (ui.group == FAV) "Chưa có kênh yêu thích.\nGiữ nút OK trên một kênh để thêm." else "Không có kênh nào.",
                    Modifier.align(Alignment.Center), color = Muted, fontSize = 18.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showSettings) SettingsDialog(ui, { showSettings = false }, onAddSource, onRemoveSource, onToggleBuiltIn, onScan, onToken, onDiscover, onClearFound)
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .border(3.dp, if (focused) White else Color.Transparent, shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) { Text(text, fontSize = 18.sp, color = White, maxLines = 1) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(c: Channel, fav: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "scale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .border(3.dp, if (focused) White else Color.Transparent, shape)
            .clip(shape)
            .background(CardBg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            if (c.logo.isNotBlank()) {
                AsyncImage(c.logo, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(c.name.take(2).uppercase(), fontSize = 26.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            (if (fav) "★ " else "") + c.name,
            fontSize = 15.sp, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SettingsDialog(
    ui: UiState,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    onRemove: (Source) -> Unit,
    onToggleBuiltIn: () -> Unit,
    onScan: () -> Unit,
    onToken: (String) -> Unit,
    onDiscover: () -> Unit,
    onClearFound: () -> Unit,
) {
    var tok by remember { mutableStateOf(ui.ghToken) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val scanning = ui.scanTotal > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151C2E),
        title = { Text("Cài đặt", color = White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kiểm tra kênh", color = White, fontSize = 17.sp)
                Text(
                    if (scanning) "Đang kiểm tra ${ui.scanDone}/${ui.scanTotal} kênh…"
                    else "App chỉ hiện kênh đã kiểm tra và phát được (kết quả giữ 6 giờ, sau đó tự kiểm tra lại). Đang hiện ${ui.alive.size}/${ui.all.size} kênh.",
                    fontSize = 14.sp, color = Muted,
                )
                Button(onClick = onScan, enabled = !scanning) { Text("Kiểm tra lại tất cả kênh", color = White) }
                HorizontalDivider(color = Muted.copy(alpha = 0.4f))
                Text("Nguồn kênh", color = White, fontSize = 17.sp)
                Text("Mỗi kênh có nhiều link; app tự chọn link chạy tốt nhất và tự đổi link khi lỗi.", fontSize = 14.sp, color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nguồn có sẵn: link gốc trong app + iptv-org + Free-TV", color = White, fontSize = 16.sp)
                        Text("Tự chia nhóm VTV / Địa phương / VTVcab / SCTV / HTVC", color = Muted, fontSize = 12.sp)
                    }
                    TextButton({ onToggleBuiltIn() }) { Text(if (ui.builtIn) "Tắt" else "Bật", color = White) }
                }
                ui.sources.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Nhóm: ${s.name}", color = White, fontSize = 16.sp)
                            Text(s.url, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton({ onRemove(s) }) { Text("Xóa", color = Color(0xFFFF8A8A)) }
                    }
                }
                HorizontalDivider(color = Muted.copy(alpha = 0.4f))
                Text("Tự tìm nguồn trên GitHub", color = White, fontSize = 17.sp)
                Text(
                    ui.discoverMsg ?: "Tìm các file .m3u công khai có kênh Việt Nam để làm link dự phòng. Đã có ${ui.foundCount} nguồn tìm được.",
                    fontSize = 14.sp, color = Muted,
                )
                TextField(tok, { tok = it; onToken(it) }, singleLine = true, label = { Text("Token GitHub (không bắt buộc)") },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = tfColors(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDiscover, enabled = !ui.discovering) { Text("Tìm nguồn mới", color = White) }
                    TextButton(onClick = onClearFound, enabled = !ui.discovering && ui.foundCount > 0) { Text("Xóa nguồn tìm được", color = White) }
                }
                HorizontalDivider(color = Muted.copy(alpha = 0.4f))
                Text("Thêm nhóm mới", color = White, fontSize = 16.sp)
                TextField(name, { name = it }, singleLine = true, label = { Text("Tên nhóm (VD: Thể thao)") },
                    colors = tfColors(), modifier = Modifier.fillMaxWidth())
                TextField(url, { url = it }, singleLine = true, label = { Text("Link M3U (https://…)") },
                    colors = tfColors(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { onAdd(name, url); name = ""; url = "" }, enabled = url.isNotBlank()) {
                    Text("Thêm nhóm", color = White)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Đóng", color = White) } },
    )
}
