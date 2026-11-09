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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.sp
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
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
    restoreKey: String,
    onTopFocus: (String) -> Unit,
    onGroup: (String) -> Unit,
    onFav: (Channel) -> Unit,
    onReload: () -> Unit,
    onAddSource: (String, String) -> Unit,
    onRemoveSource: (Source) -> Unit,
    onToggleBuiltIn: () -> Unit,
    onToggleExtras: () -> Unit,
    onScan: () -> Unit,
    onScanDead: () -> Unit,
    onToggleAutoFill: () -> Unit,
    onToggleLeveler: () -> Unit,
    onToken: (String) -> Unit,
    onCheckToken: () -> Unit,
    onDiscover: () -> Unit,
    onClearFound: () -> Unit,
    onPlay: (Channel) -> Unit,
    onHide: (Channel) -> Unit,
    onMove: (Channel, String) -> Unit,
    onMerge: (Channel, Channel) -> Unit,
    onUnmerge: (Channel) -> Unit,
    onSetNumber: (Channel, Int) -> Unit,
    onClearNumber: (Channel) -> Unit,
    onPickLink: (Channel, String) -> Unit,
    scoreOf: (String) -> Int,
    onRestore: () -> Unit,
) {
    var numberFor by remember { mutableStateOf<Channel?>(null) }   // hộp đổi số kênh
    var linksFor by remember { mutableStateOf<Channel?>(null) }    // hộp chọn link phát
    var showSettings by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Channel?>(null) }   // kênh đang mở menu nhấn giữ
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
    val userNames = remember(ui.sources) { ui.sources.filter { !it.auto }.map { it.name }.toSet() }
    // Nhóm do bạn tự thêm luôn hiện (kể cả đang trống) để chọn và chuyển kênh vào
    val groups = remember(counts, order, userNames) { order.distinct().filter { (counts[it] ?: 0) > 0 || it in userNames } }

    // ----- Nhớ và trả lại vị trí focus: bấm Back sẽ về nút đã đứng trước khi vào danh sách kênh / mở kênh -----
    val rowState = rememberLazyListState()
    val reqs = remember { HashMap<String, FocusRequester>() }
    var topKey by remember { mutableStateOf(restoreKey) }
    var topFocusedKey by remember { mutableStateOf("") }
    var gridFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun req(key: String) = reqs.getOrPut(key) { FocusRequester() }
    fun topMod(key: String): Modifier = Modifier.focusRequester(req(key)).onFocusChanged { st ->
        if (st.isFocused) { topKey = key; topFocusedKey = key; onTopFocus(key) }
        else if (topFocusedKey == key) topFocusedKey = ""
    }
    suspend fun focusTop(key: String) {
        val i = when {
            key == "all" -> 0
            key == "fav" -> 1
            key.startsWith("g:") -> 2 + groups.indexOf(key.drop(2)).coerceAtLeast(0)
            else -> -1
        }
        if (i >= 0) runCatching { rowState.scrollToItem(i) }
        repeat(6) {
            if (topFocusedKey == key) return
            delay(80)
            runCatching { req(key).requestFocus() }
        }
    }
    LaunchedEffect(Unit) { if (restoreKey.isNotEmpty()) focusTop(restoreKey) }   // vừa thoát màn hình phát
    BackHandler(enabled = gridFocused && !showSettings && menuFor == null) {
        scope.launch { focusTop(topKey.ifEmpty { "all" }) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Xem TV Lite v$ver", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Pill("Tải lại", false, modifier = topMod("reload"), onClick = onReload)
            Pill("Cài đặt", false, modifier = topMod("settings")) { showSettings = true }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Pill("Tất cả", ui.group == "", modifier = topMod("all")) { onGroup("") } }
            item { Pill("★ Yêu thích", ui.group == FAV, modifier = topMod("fav")) { onGroup(FAV) } }
            items(groups) { g -> Pill("$g (${counts[g] ?: 0})", ui.group == g, modifier = topMod("g:$g")) { onGroup(g) } }
        }
        ui.notice?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 14.sp, color = Color(0xFFFFC857))
        }
        if (ui.scanTotal > 0) {
            Spacer(Modifier.height(6.dp))
            Text("${ui.scanLabel}: ${ui.scanDone}/${ui.scanTotal} · đang hiện ${ui.alive.size} kênh", fontSize = 14.sp, color = Muted)
        }
        Spacer(Modifier.height(14.dp))

        Box(Modifier.fillMaxSize()) {
            if (shown.isNotEmpty()) {
                LazyVerticalGrid(
                    modifier = Modifier.onFocusChanged { gridFocused = it.hasFocus },
                    columns = GridCells.Adaptive(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(shown, key = { it.id }) { c ->
                        ChannelCard(c, numbers[c.id] ?: 0, c.id in ui.favorites, { onPlay(c) }, { menuFor = c })
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
                    when {
                        ui.group == FAV -> "Chưa có kênh yêu thích.\nGiữ nút OK trên một kênh để thêm."
                        ui.group in userNames -> "Nhóm này đang trống.\nGiữ nút OK trên kênh ở nhóm khác, chọn \"Di chuyển kênh…\" để đưa vào đây."
                        else -> "Không có kênh nào."
                    },
                    Modifier.align(Alignment.Center), color = Muted, fontSize = 18.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showSettings) SettingsDialog(ui, { showSettings = false }, onAddSource, onRemoveSource, onToggleBuiltIn, onToggleExtras, onScan, onScanDead, onToggleAutoFill, onToggleLeveler, onToken, onCheckToken, onDiscover, onClearFound, onRestore)

    menuFor?.let { c ->
        ChannelMenu(
            c = c, number = numbers[c.id] ?: 0, fav = c.id in ui.favorites, groups = order.distinct(),
            all = ui.alive, numbers = numbers, mergedCount = ui.merges.values.count { it == c.id },
            onDismiss = { menuFor = null },
            onFav = { onFav(c); menuFor = null },
            onHide = { onHide(c); menuFor = null },
            onMove = { g -> onMove(c, g); menuFor = null },
            onMerge = { t -> onMerge(c, t); menuFor = null },
            onUnmerge = { onUnmerge(c); menuFor = null },
            onPickLink = { linksFor = c; menuFor = null },
            onNumber = { numberFor = c; menuFor = null },
        )
    }
    numberFor?.let { c ->
        NumberDialog(
            name = c.name, current = numbers[c.id] ?: 1, total = numbers.size, hasCustom = c.id in ui.customNumbers,
            onDismiss = { numberFor = null },
            onSave = { n -> onSetNumber(c, n); numberFor = null },
            onClear = { onClearNumber(c); numberFor = null },
        )
    }
    linksFor?.let { c ->
        LinkPickerDialog(
            name = c.name, urls = c.urls, current = ui.preferred[c.id], ok = ui.ok, dead = ui.dead, scoreOf = scoreOf,
            onPick = { u -> onPickLink(c, u); linksFor = null },
            onDismiss = { linksFor = null },
        )
    }
}

private enum class MenuMode { MAIN, MOVE, MERGE, MERGE_LIST }

/** Chặn phím OK cho tới khi người dùng nhả hẳn phím (hộp thoại mở ra khi còn đang giữ OK sẽ không bị bấm nhầm). */
@Composable
private fun okGuard(): Modifier {
    var armed by remember { mutableStateOf(false) }
    val lastOk = remember { LongArray(1) { System.currentTimeMillis() } }
    LaunchedEffect(Unit) {
        while (!armed) {
            delay(100)
            if (System.currentTimeMillis() - lastOk[0] > 350) armed = true
        }
    }
    return Modifier.onPreviewKeyEvent { ev ->
        val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
        if (ok && !armed) { lastOk[0] = System.currentTimeMillis(); true } else false
    }
}

/** Menu khi nhấn giữ một kênh: yêu thích, chọn link, đổi số, di chuyển, gộp, xoá. */
@Composable
private fun ChannelMenu(
    c: Channel,
    number: Int,
    fav: Boolean,
    groups: List<String>,
    all: List<Channel>,
    numbers: Map<String, Int>,
    mergedCount: Int,
    onDismiss: () -> Unit,
    onFav: () -> Unit,
    onHide: () -> Unit,
    onMove: (String) -> Unit,
    onMerge: (Channel) -> Unit,
    onUnmerge: () -> Unit,
    onPickLink: () -> Unit,
    onNumber: () -> Unit,
) {
    var mode by remember { mutableStateOf(MenuMode.MAIN) }
    var mergeGroup by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val firstGroup = groups.firstOrNull { it != c.group }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(mode, mergeGroup) { if (started) runCatching { first.requestFocus() }; started = true }

    val others = remember(c.id, all) { all.filter { it.id != c.id }.sortedBy { numbers[it.id] ?: Int.MAX_VALUE } }
    val myToks = remember(c.id) { dedupeKey(c.name).split(' ').filter { it.isNotEmpty() }.toSet() }
    // Gợi ý kênh có tên giống (cùng từ khoá) để gộp nhanh
    val suggest = remember(c.id, others) {
        others.map { o -> o to dedupeKey(o.name).split(' ').count { t -> t in myToks } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(6).map { it.first }
    }
    val groupCounts = remember(others) { others.groupingBy { it.group }.eachCount() }

    fun back() {
        when (mode) {
            MenuMode.MERGE_LIST -> mode = MenuMode.MERGE
            MenuMode.MAIN -> onDismiss()
            else -> mode = MenuMode.MAIN
        }
    }

    AlertDialog(
        modifier = okGuard(),
        onDismissRequest = { back() },
        containerColor = Color(0xFF151C2E),
        title = {
            Text(
                when (mode) {
                    MenuMode.MAIN -> "$number   ${c.name}"
                    MenuMode.MOVE -> "Chuyển \"${c.name}\" sang nhóm:"
                    MenuMode.MERGE -> "Gộp \"${c.name}\" vào kênh:"
                    MenuMode.MERGE_LIST -> "Gộp vào kênh trong nhóm $mergeGroup:"
                },
                color = White, maxLines = 2,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (mode) {
                    MenuMode.MAIN -> {
                        Pill(if (fav) "☆ Bỏ yêu thích" else "★ Thêm vào yêu thích", false, modifier = Modifier.focusRequester(first), onClick = onFav)
                        Pill("Chọn link phát…", false, onClick = onPickLink)
                        Pill("Đổi số kênh…", false, onClick = onNumber)
                        Pill("Di chuyển kênh…", false) { mode = MenuMode.MOVE }
                        Pill("Gộp vào kênh khác…", false) { mode = MenuMode.MERGE }
                        if (mergedCount > 0) Pill("Tách $mergedCount kênh đã gộp vào kênh này", false, onClick = onUnmerge)
                        Pill("Xoá kênh", false, textColor = Color(0xFFFF8A8A), onClick = onHide)
                    }
                    MenuMode.MOVE -> groups.forEach { g ->
                        Pill(
                            if (g == c.group) "$g (hiện tại)" else g, g == c.group, enabled = g != c.group,
                            modifier = if (g == firstGroup) Modifier.focusRequester(first) else Modifier,
                        ) { onMove(g) }
                    }
                    MenuMode.MERGE -> {
                        if (suggest.isNotEmpty()) Text("Gợi ý (tên giống):", color = Muted, fontSize = 13.sp)
                        suggest.forEachIndexed { i, o ->
                            Pill(
                                "${numbers[o.id] ?: ""}  ${o.name} · ${o.group}", false, small = true,
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            ) { onMerge(o) }
                        }
                        Text("Hoặc chọn trong nhóm:", color = Muted, fontSize = 13.sp)
                        groups.filter { (groupCounts[it] ?: 0) > 0 }.forEachIndexed { i, g ->
                            Pill(
                                "$g (${groupCounts[g]})", false, small = true,
                                modifier = if (suggest.isEmpty() && i == 0) Modifier.focusRequester(first) else Modifier,
                            ) { mergeGroup = g; mode = MenuMode.MERGE_LIST }
                        }
                    }
                    MenuMode.MERGE_LIST -> others.filter { it.group == mergeGroup }.forEachIndexed { i, o ->
                        Pill(
                            "${numbers[o.id] ?: ""}  ${o.name}", false, small = true,
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        ) { onMerge(o) }
                    }
                }
            }
        },
        confirmButton = { Pill(if (mode == MenuMode.MAIN) "Đóng" else "Quay lại", false) { back() } },
    )
}

/** Nhãn ngắn của link: tên miền + đuôi đường dẫn. */
private fun linkLabel(url: String): String {
    val rest = url.substringAfter("://")
    val host = rest.substringBefore('/').take(32)
    val path = rest.substringAfter('/', "").substringBefore('?')
    return if (path.isEmpty()) host else "$host/…${path.takeLast(20)}"
}

/** Chọn link phát của một kênh. Link được chọn (hoặc đang phát được) trở thành mặc định cho lần sau. */
@Composable
internal fun LinkPickerDialog(
    name: String,
    urls: List<String>,
    current: String?,
    ok: Set<String>,
    dead: Set<String>,
    scoreOf: (String) -> Int,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    AlertDialog(
        modifier = okGuard(),
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151C2E),
        title = { Text("Chọn link phát: $name", color = White, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Link bạn chọn sẽ được thử đầu tiên và là mặc định cho lần sau.", fontSize = 13.sp, color = Muted)
                urls.forEachIndexed { i, u ->
                    val st = when {
                        u in dead -> "chết"
                        u in ok -> "tốt (${scoreOf(u)})"
                        else -> "chưa đo"
                    }
                    Pill(
                        "${i + 1}. ${linkLabel(u)} · $st" + if (u == current) " · ★ mặc định" else "",
                        u == current, small = true,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { onPick(u) }
                }
            }
        },
        confirmButton = { Pill("Đóng", false, onClick = onDismiss) },
    )
}

/** Đổi số kênh: nhập số mới (hoặc dùng các nút −10/−1/+1/+10). Các kênh khác tự dời chỗ. */
@Composable
private fun NumberDialog(
    name: String,
    current: Int,
    total: Int,
    hasCustom: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var v by remember { mutableStateOf(TextFieldValue(current.toString(), TextRange(current.toString().length))) }
    val saveFocus = remember { FocusRequester() }
    val n = v.text.toIntOrNull()
    val valid = n != null && n in 1..9999
    fun bump(d: Int) {
        val x = ((v.text.toIntOrNull() ?: current) + d).coerceIn(1, maxOf(total, 1))
        v = TextFieldValue(x.toString(), TextRange(x.toString().length))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.TOP) }
        val leave = {
            hideKeyboard(view)
            runCatching { saveFocus.requestFocus() }
            Unit
        }
        Column(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF151C2E))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Đổi số kênh: $name", color = White, fontSize = 20.sp, maxLines = 2)
            Text("Số hiện tại: $current (có $total kênh). Các kênh khác tự dời chỗ.", color = Muted, fontSize = 14.sp)
            TextField(
                v, { nv -> v = nv.copy(text = nv.text.filter { it.isDigit() }.take(4)) }, singleLine = true,
                label = { Text("Số kênh mới") }, colors = tfColors(),
                modifier = Modifier.fillMaxWidth().leaveOnDpad(leave),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = nextActions(leave),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("−10", false, small = true) { bump(-10) }
                Pill("−1", false, small = true) { bump(-1) }
                Pill("+1", false, small = true) { bump(1) }
                Pill("+10", false, small = true) { bump(10) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Lưu", false, enabled = valid, small = true, modifier = Modifier.focusRequester(saveFocus)) { onSave(n ?: current) }
                if (hasCustom) Pill("Bỏ số riêng", false, small = true, onClick = onClear)
                Pill("Hủy", false, small = true, onClick = onDismiss)
            }
        }
    }
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

/** Có logo thì hiện logo; không có (hoặc tải lỗi) thì hiện ô màu riêng của kênh kèm chữ cái đầu. */
@Composable
private fun LogoBox(c: Channel) {
    var failed by remember(c.logo) { mutableStateOf(false) }
    if (c.logo.isNotBlank() && !failed) {
        AsyncImage(
            model = c.logo, contentDescription = c.name, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit, onError = { failed = true },
        )
    } else {
        val hue = ((c.name.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
        val words = c.name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val initials = if (words.size >= 2) words.take(3).joinToString("") { it.first().uppercase() } else c.name.take(4).uppercase()
        Box(
            Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Color.hsv(hue, 0.45f, 0.55f)),
            contentAlignment = Alignment.Center,
        ) { Text(initials, fontSize = 18.sp, color = White, maxLines = 1) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(c: Channel, number: Int, fav: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }   // lần giữ OK hiện tại đã mở menu rồi
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "scale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                // Remote TV: chỉ phím OK, và chỉ khi NHẤN VÀ GIỮ (phím lặp) mới mở menu; bấm OK nhanh vẫn là mở kênh
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                when {
                    !ok -> false
                    ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0 -> { longFired = false; false }
                    ev.type == KeyEventType.KeyDown -> { if (!longFired) { longFired = true; onLong() }; true }   // đang giữ
                    ev.type == KeyEventType.KeyUp && longFired -> { longFired = false; true }                    // nhả phím sau khi giữ: không tính là bấm
                    else -> false
                }
            }
            .border(3.dp, if (focused) White else Color.Transparent, shape)
            .clip(shape)
            .background(CardBg)
            .combinedClickable(onClick = onClick, onLongClick = onLong)   // cảm ứng / chuột: nhấn giữ
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Số kênh nằm đè góc trên bên trái ô logo (gọn hơn: ô thấp hơn khoảng 1/4)
        Box(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.Center) {
            LogoBox(c)
            Text("$number", Modifier.align(Alignment.TopStart), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            (if (fav) "★ " else "") + c.name,
            fontSize = 14.sp, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
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
    onScanDead: () -> Unit,
    onToggleAutoFill: () -> Unit,
    onToggleLeveler: () -> Unit,
    onToken: (String) -> Unit,
    onCheckToken: () -> Unit,
    onDiscover: () -> Unit,
    onClearFound: () -> Unit,
    onRestore: () -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }
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
                    if (scanning) "${ui.scanLabel}: ${ui.scanDone}/${ui.scanTotal} · hiện ${ui.alive.size} kênh"
                    else "Kênh đang hiện: ${ui.alive.size}/${ui.all.size}"
                ) { Pill("Kiểm tra lại tất cả", false, enabled = !scanning, small = true, onClick = onScan) }
                SettingRow("Thử lại các kênh đang chết") {
                    Pill("Thử lại", false, enabled = !scanning, small = true, onClick = onScanDead)
                }
                SettingRow("Tự bổ sung nguồn khi có kênh chết") {
                    Pill(if (ui.autoFill) "BẬT" else "TẮT", ui.autoFill, small = true, onClick = onToggleAutoFill)
                }
                SettingRow("Cân bằng âm lượng giữa các kênh") {
                    Pill(if (ui.leveler) "BẬT" else "TẮT", ui.leveler, small = true, onClick = onToggleLeveler)
                }
                val appCtx = LocalContext.current
                var crash by remember { mutableStateOf(CrashLog.summary(appCtx)) }
                crash?.let { text ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Lần thoát bất thường gần nhất (gửi nội dung này để được hỗ trợ):", color = White, fontSize = 14.sp)
                        Text(text, color = Color(0xFFFFC857), fontSize = 12.sp, maxLines = 8)
                        Pill("Xoá thông tin này", false, small = true) { CrashLog.clear(appCtx); crash = null }
                    }
                }
                SettingRow("Xoá ${ui.hidden.size} · di chuyển ${ui.moved.size} · gộp ${ui.merges.size} · đổi số ${ui.customNumbers.size}") {
                    Pill("Khôi phục", false, enabled = ui.hidden.isNotEmpty() || ui.moved.isNotEmpty() || ui.merges.isNotEmpty() || ui.customNumbers.isNotEmpty(), small = true, onClick = onRestore)
                }
                HorizontalDivider(color = divider)

                SettingRow("Nguồn có sẵn (VTV, địa phương, VTVcab…)") {
                    Pill(if (ui.builtIn) "BẬT" else "TẮT", ui.builtIn, small = true, onClick = onToggleBuiltIn)
                }
                SettingRow("Thể thao & Phim") {
                    Pill(if (ui.extras) "BẬT" else "TẮT", ui.extras, small = true, onClick = onToggleExtras)
                }
                ui.sources.forEach { s ->
                    SettingRow(if (s.url.isBlank()) "Nhóm: ${s.name} (trống)" else "Nhóm: ${s.name}") {
                        Pill("Xóa", false, textColor = Color(0xFFFF8A8A), small = true) { onRemove(s) }
                    }
                }
                HorizontalDivider(color = divider)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (ui.ghToken.isBlank()) "Token GitHub (không bắt buộc): chưa nhập" else "Token GitHub: ••••" + ui.ghToken.takeLast(4),
                        Modifier.weight(1f), fontSize = 15.sp, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Pill(if (ui.ghToken.isBlank()) "Nhập token…" else "Đổi token…", false, small = true) { showToken = true }
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
    if (showAdd) AddGroupDialog({ showAdd = false }) { n, u -> onAdd(n, u); showAdd = false }
    if (showToken) TokenDialog(ui.ghToken, { showToken = false }) { t -> onToken(t); showToken = false }
}

/** Hộp nhập token nằm sát mép trên màn hình (giống hộp "Thêm nhóm") để bàn phím ảo không che. */
@Composable
private fun TokenDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var tok by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val saveFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.TOP) }
        val leave = {
            hideKeyboard(view)
            runCatching { saveFocus.requestFocus() }
            Unit
        }
        Column(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF151C2E))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Token GitHub (không bắt buộc)", color = White, fontSize = 20.sp)
            TextField(
                tok, { tok = it }, singleLine = true, label = { Text("Dán token (ghp_… hoặc github_pat_…)") },
                colors = tfColors(), modifier = Modifier.fillMaxWidth().leaveOnDpad(leave),
                keyboardOptions = nextOptions, keyboardActions = nextActions(leave),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Lưu", false, small = true, modifier = Modifier.focusRequester(saveFocus)) { onSave(tok.text.trim()) }
                Pill("Hủy", false, small = true, onClick = onDismiss)
            }
        }
    }
}

/** Hộp thoại nhập nhóm mới: đặt ở ĐỈNH màn hình để bàn phím ảo không che, kèm dấu ✔/✘ báo link đúng sai. */
@Composable
private fun AddGroupDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    // Sẵn "https://" và đặt con trỏ ở cuối để người dùng gõ tiếp phần còn lại
    var url by remember { mutableStateOf(TextFieldValue("https://", TextRange(8))) }
    val u = url.text.trim()
    val bare = u.equals("https://", true) || u.equals("http://", true)
    val urlOk = !bare && (u.startsWith("http://", true) || u.startsWith("https://", true))
    val empty = u.isEmpty() || bare                       // bỏ trống link = nhóm trống
    val canAdd = if (empty) name.isNotBlank() else urlOk
    val addFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val fm = LocalFocusManager.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.TOP) }
        // Rời ô nhập: ẩn bàn phím rồi đưa focus HẲN sang nút (ô nhập mất focus nên bàn phím không hiện lại)
        val leave = {
            hideKeyboard(view)
            runCatching { if (canAdd) addFocus.requestFocus() else cancelFocus.requestFocus() }
            Unit
        }
        Column(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF151C2E))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Thêm nhóm mới", color = White, fontSize = 20.sp)
            TextField(name, { name = it }, singleLine = true, label = { Text("1. Tên nhóm") },
                colors = tfColors(), modifier = Modifier.fillMaxWidth().leaveOnDpad(leave),
                keyboardOptions = nextOptions, keyboardActions = nextActions { fm.moveFocus(FocusDirection.Down) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(url, { url = it }, singleLine = true, label = { Text("2. Link M3U (có thể bỏ trống)") },
                    colors = tfColors(), modifier = Modifier.weight(1f).leaveOnDpad(leave),
                    keyboardOptions = nextOptions, keyboardActions = nextActions(leave))
                when {
                    u.isEmpty() || bare -> {}
                    urlOk -> Text("✔", color = Color(0xFF4CD964), fontSize = 30.sp)
                    else -> Text("✘", color = Color(0xFFFF6B6B), fontSize = 30.sp)
                }
            }
            if (u.isNotEmpty() && !bare && !urlOk) Text("Link phải bắt đầu bằng http:// hoặc https://", fontSize = 13.sp, color = Color(0xFFFF9A9A))
            if (empty) Text("Bỏ trống link = tạo nhóm trống để tự chuyển kênh từ nhóm khác vào.", fontSize = 13.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Thêm nhóm", false, enabled = canAdd, small = true, modifier = Modifier.focusRequester(addFocus)) { onAdd(name, if (empty) "" else url.text) }
                Pill("Hủy", false, small = true, modifier = Modifier.focusRequester(cancelFocus), onClick = onDismiss)
            }
        }
    }
}


// ---------- Ô nhập trên TV: thoát focus được ----------
private fun hideKeyboard(view: android.view.View) {
    (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(view.windowToken, 0)
}

/**
 * Ô nhập văn bản mặc định nuốt phím ↑/↓ và Back nên remote không rời ô được. Ở đây:
 * Back = rời ô ([onLeave]: ẩn bàn phím và đưa focus sang nút, xử lý cả lúc nhấn lẫn lúc nhả phím);
 * ↑/↓ = ẩn bàn phím rồi chuyển focus sang ô/nút kế bên.
 */
@Composable
private fun Modifier.leaveOnDpad(onLeave: () -> Unit): Modifier {
    val fm = LocalFocusManager.current
    val view = LocalView.current
    var backDown by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { ev ->
        when {
            ev.key == Key.Back -> {
                if (ev.type == KeyEventType.KeyDown) { backDown = true; onLeave() }
                else { if (!backDown) onLeave(); backDown = false }
                true     // nuốt Back (cả lúc nhấn và nhả) để hộp thoại không đóng theo
            }
            ev.type != KeyEventType.KeyDown -> false
            ev.key == Key.DirectionDown -> { hideKeyboard(view); fm.moveFocus(FocusDirection.Down); true }
            ev.key == Key.DirectionUp -> { hideKeyboard(view); fm.moveFocus(FocusDirection.Up); true }
            else -> false
        }
    }
}

private val nextOptions = KeyboardOptions(imeAction = ImeAction.Done)

/** Phím Xong/Next trên bàn phím ảo chạy [onDone] (thường là rời ô nhập hoặc sang ô kế tiếp). */
private fun nextActions(onDone: () -> Unit): KeyboardActions =
    KeyboardActions(onNext = { onDone() }, onDone = { onDone() })
