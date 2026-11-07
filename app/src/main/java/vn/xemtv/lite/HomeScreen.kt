package vn.xemtv.lite

import android.view.Gravity
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

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
    numbers: Map<String, Int>,
    onPlayNumber: (Int) -> Unit,
    onGroup: (String) -> Unit,
    onFav: (Channel) -> Unit,
    onReload: () -> Unit,
    onAddSource: (String, String) -> Unit,
    onRemoveSource: (Source) -> Unit,
    onToggleBuiltIn: () -> Unit,
    onToggleExtras: () -> Unit,
    onScan: () -> Unit,
    onToken: (String) -> Unit,
    onCheckToken: () -> Unit,
    onDiscover: () -> Unit,
    onClearFound: () -> Unit,
    onPlay: (Channel) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var numBuf by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { KeyBus.digits.collect { d -> if (numBuf.length < 4) numBuf += d.toString() } }
    LaunchedEffect(numBuf) {
        if (numBuf.isEmpty()) return@LaunchedEffect
        val n = numBuf.toInt()
        if (n * 10 > numbers.size) { onPlayNumber(n); numBuf = "" }
        else { delay(1_500); onPlayNumber(n); numBuf = "" }
    }
    val ctx = LocalContext.current
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    // Các nhóm có kênh, theo thứ tự: nhóm VN cố định -> nhóm do bạn đặt -> Khác
    val counts = remember(ui.alive) { ui.alive.groupingBy { it.group }.eachCount() }
    val order = remember(ui.sources) {
        VN_GROUPS + listOf(G_SPORT, G_MOVIE) + ui.sources.filter { !it.auto }.map { it.name } + G_OTHER
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
            Spacer(Modifier.weight(1f))
            Pill("Tải lại", false, onClick = onReload)
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
                        ChannelCard(c, numbers[c.id] ?: 0, c.id in ui.favorites, { onPlay(c) }, { onFav(c) })
                    }
                }
            }
            if (numBuf.isNotEmpty()) {
                Text(
                    numBuf, Modifier.align(Alignment.TopEnd).background(Color(0xCC000000)).padding(horizontal = 24.dp, vertical = 12.dp),
                    fontSize = 44.sp, color = Color(0xFFFFC857),
                )
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

    if (showSettings) SettingsDialog(ui, { showSettings = false }, onAddSource, onRemoveSource, onToggleBuiltIn, onToggleExtras, onScan, onToken, onCheckToken, onDiscover, onClearFound)
}

@Composable
private fun Pill(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    textColor: Color = White,
    small: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .border(3.dp, if (focused) White else Color.Transparent, shape)
            .background(
                when {
                    focused && enabled -> MaterialTheme.colorScheme.primary   // đang chọn bằng remote: sáng hẳn lên
                    selected -> MaterialTheme.colorScheme.primary
                    else -> CardBg
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 18.dp, vertical = if (small) 6.dp else 10.dp)
    ) { Text(text, fontSize = if (small) 15.sp else 18.sp, color = if (enabled) textColor else Muted.copy(alpha = 0.5f), maxLines = 1) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(c: Channel, number: Int, fav: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
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
        Text("$number", Modifier.align(Alignment.Start), fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
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
private fun SettingRow(title: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), color = White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}

@Composable
private fun SettingsDialog(
    ui: UiState,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    onRemove: (Source) -> Unit,
    onToggleBuiltIn: () -> Unit,
    onToggleExtras: () -> Unit,
    onScan: () -> Unit,
    onToken: (String) -> Unit,
    onCheckToken: () -> Unit,
    onDiscover: () -> Unit,
    onClearFound: () -> Unit,
) {
    var editTok by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val rDiscover = remember { FocusRequester() }
    val scanning = ui.scanTotal > 0
    val divider = Muted.copy(alpha = 0.3f)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color(0xFF151C2E),
        title = { Text("Cài đặt", color = White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow(
                    if (scanning) "Đang kiểm tra ${ui.scanDone}/${ui.scanTotal} · hiện ${ui.alive.size} kênh"
                    else "Kênh đang hiện: ${ui.alive.size}/${ui.all.size}"
                ) { Pill("Kiểm tra lại", false, enabled = !scanning, small = true, onClick = onScan) }
                HorizontalDivider(color = divider)

                SettingRow("Nguồn có sẵn (VTV, địa phương, VTVcab…)") {
                    Pill(if (ui.builtIn) "BẬT" else "TẮT", ui.builtIn, small = true, onClick = onToggleBuiltIn)
                }
                SettingRow("Thể thao & Phim") {
                    Pill(if (ui.extras) "BẬT" else "TẮT", ui.extras, small = true, onClick = onToggleExtras)
                }
                ui.sources.forEach { s ->
                    SettingRow("Nhóm: ${s.name}") {
                        Pill("Xóa", false, textColor = Color(0xFFFF8A8A), small = true) { onRemove(s) }
                    }
                }
                HorizontalDivider(color = divider)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputRow("Token GitHub (không bắt buộc)", ui.ghToken, Modifier.weight(1f)) { editTok = true }
                    Pill("Kiểm tra", false, enabled = !ui.discovering, small = true,
                        modifier = Modifier.focusProperties { down = rDiscover }, onClick = onCheckToken)
                    val tm = ui.tokenMsg
                    when {
                        tm == null -> {}
                        tm.startsWith("✔") -> Text("✔", color = Color(0xFF4CD964), fontSize = 30.sp)
                        tm.startsWith("✘") -> Text("✘", color = Color(0xFFFF6B6B), fontSize = 30.sp)
                        else -> Text("…", color = Muted, fontSize = 30.sp)
                    }
                }
                ui.tokenMsg?.takeIf { it.startsWith("✘") }?.let { Text(it, fontSize = 13.sp, color = Color(0xFFFF9A9A)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Tìm nguồn mới", false, enabled = !ui.discovering, small = true,
                        modifier = Modifier.focusRequester(rDiscover), onClick = onDiscover)
                    Pill("Xóa nguồn tìm được", false, enabled = !ui.discovering && ui.foundCount > 0, small = true, onClick = onClearFound)
                    Text(
                        ui.discoverMsg ?: "Đã có ${ui.foundCount} nguồn",
                        Modifier.weight(1f), fontSize = 13.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = divider)

                SettingRow("Thêm nhóm mới (tên + link M3U)") {
                    Pill("Thêm…", false, small = true) { showAdd = true }
                }
            }
        },
        confirmButton = { Pill("Đóng", false, onClick = onDismiss) },
    )
    if (editTok) TextInputDialog(
        title = "Token GitHub", initial = ui.ghToken,
        onDone = { onToken(it); editTok = false }, onDismiss = { editTok = false },
    )
    if (showAdd) AddGroupDialog({ showAdd = false }) { n, u -> onAdd(n, u); showAdd = false }
}

/** Ô hiển thị giá trị đã nhập. Bấm OK sẽ mở một modal nhập riêng ở đỉnh màn hình (bàn phím ảo không che được). */
@Composable
private fun InputRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .border(3.dp, if (focused) White else Color.Transparent, shape)
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Muted)
        Text(
            value.ifEmpty { "(bấm OK để nhập)" },
            fontSize = 17.sp, color = if (value.isEmpty()) Muted else White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Hộp thoại luôn nằm ở ĐỈNH màn hình. */
@Composable
private fun TopDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.TOP) }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF151C2E))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/** Modal nhập chữ: đang gõ gì được hiện CỠ LỚN ngay phía trên ô nhập nên không bao giờ bị bàn phím che. */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    uri: Boolean = false,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tf by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val fr = remember { FocusRequester() }
    TopDialog(onDismiss) {
        Text(title, color = White, fontSize = 20.sp)
        Text(
            tf.text.ifEmpty { "(chưa nhập)" },
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B1020)).padding(12.dp),
            fontSize = 22.sp, color = Color(0xFFFFC857),
        )
        TextField(
            tf, { tf = it }, singleLine = true, colors = tfColors(),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone(tf.text.trim()) }),
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Xong", false, small = true) { onDone(tf.text.trim()) }
            Pill("Hủy", false, small = true, onClick = onDismiss)
        }
        LaunchedEffect(Unit) { try { fr.requestFocus() } catch (_: Exception) {} }
    }
}

/** Thêm nhóm: nhập tên + link bằng modal riêng; link được TẢI THỬ, chỉ hiện ✔ khi link thật sự tồn tại và là danh sách M3U. */
@Composable
private fun AddGroupDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var editing by remember { mutableStateOf("") }          // "", "name", "url"
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val u = url.trim()
    val bare = u.equals("https://", true) || u.equals("http://", true) || u.isEmpty()

    // Kiểm tra link sau khi dừng gõ
    LaunchedEffect(u) {
        result = null
        if (bare) { checking = false; return@LaunchedEffect }
        checking = true
        result = checkM3u(u)
        checking = false
    }

    TopDialog(onDismiss) {
        Text("Thêm nhóm mới", color = White, fontSize = 20.sp)
        InputRow("1. Tên nhóm", name, Modifier.fillMaxWidth()) { editing = "name" }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputRow("2. Link M3U", if (bare) "" else url, Modifier.weight(1f)) { editing = "url" }
            val r = result
            when {
                bare -> {}
                checking -> Text("Đang kiểm tra…", color = Muted, fontSize = 14.sp)
                r != null && r.first -> Text("✔ ${r.second}", color = Color(0xFF4CD964), fontSize = 18.sp)
                r != null -> Text("✘ ${r.second}", color = Color(0xFFFF6B6B), fontSize = 16.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Thêm nhóm", false, enabled = result?.first == true, small = true) { onAdd(name, url) }
            Pill("Hủy", false, small = true, onClick = onDismiss)
        }
    }
    if (editing == "name") TextInputDialog("Tên nhóm", name, onDone = { name = it; editing = "" }, onDismiss = { editing = "" })
    if (editing == "url") TextInputDialog("Link M3U", url, uri = true, onDone = { url = it; editing = "" }, onDismiss = { editing = "" })
}
