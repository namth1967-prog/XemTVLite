package vn.xemtv.lite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

data class UiState(
    val loading: Boolean = true,
    val all: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val query: String = "",
    val group: String = "",          // "" = tất cả, FAV = yêu thích, còn lại = tên nhóm
    val error: String? = null,
    val sources: List<Source> = emptyList(),
    val builtIn: Boolean = true,
    val extras: Boolean = true,
    val ok: Set<String> = emptySet(),      // link đã kiểm tra: chạy được
    val dead: Set<String> = emptySet(),    // link đã kiểm tra: chết
    val scanDone: Int = 0,
    val scanTotal: Int = 0,          // > 0: đang quét
    val ghToken: String = "",
    val foundCount: Int = 0,
    val discovering: Boolean = false,
    val discoverMsg: String? = null,
    val tokenMsg: String? = null,
    val autoFill: Boolean = true,    // tự tìm nguồn bổ sung khi có kênh chết
    val leveler: Boolean = true,     // cân bằng âm lượng giữa các kênh
    val hidden: Set<String> = emptySet(),          // kênh người dùng đã xoá (id)
    val moved: Map<String, String> = emptyMap(),   // kênh người dùng đã di chuyển: id -> nhóm mới
    val notice: String? = null,                    // thông báo ngắn (VD: kết quả bấm Tải lại)
) {
    private val userGroups = sources.filter { !it.auto }.map { it.name }.toSet()

    /**
     * Kênh có ít nhất 1 link ĐÃ kiểm tra và chạy được, bỏ kênh đã xoá, áp dụng nhóm đã di chuyển.
     * Nhóm do bạn tự thêm thì hiện NGAY khi nạp xong (không chờ kiểm tra), chỉ ẩn khi mọi link đều đã kiểm tra là chết.
     */
    val alive: List<Channel> = all
        .filter { c ->
            c.id !in hidden &&
                (c.urls.any { it in ok } || (c.group in userGroups && c.urls.any { it !in dead }))
        }
        .map { c -> moved[c.id]?.let { c.copy(group = it) } ?: c }
}

/** NEW: chỉ kênh chưa có kết quả. DEAD: thêm các kênh đang chết. ALL: kiểm tra lại tất cả. */
enum class ScanMode { NEW, DEAD, ALL }

const val FAV = "__fav__"

/** Tự tìm nguồn bổ sung tối đa mỗi 12 giờ. */
private const val FILL_EVERY_MS = 12L * 3600 * 1000

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo(app)
    private val prober = Prober()
    private val _ui = MutableStateFlow(
        UiState(
            favorites = repo.favorites, sources = repo.sources, builtIn = repo.builtIn, extras = repo.extras,
            ok = repo.health.ok(), dead = repo.health.dead(),
            ghToken = repo.ghToken, foundCount = repo.found.size, autoFill = repo.autoFill,
            hidden = repo.hidden, moved = repo.moved, leveler = repo.leveler,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var scanJob: Job? = null
    private var discoverJob: Job? = null
    private var noticeJob: Job? = null

    /** Hiện thông báo ngắn ở màn hình chính, tự tắt sau 7 giây. */
    private fun showNotice(msg: String) {
        _ui.update { it.copy(notice = msg) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch { delay(7_000); _ui.update { it.copy(notice = null) } }
    }

    init {
        Leveler.enabled = repo.leveler
        load()
    }

    private fun refreshHealth() = _ui.update { it.copy(ok = repo.health.ok(), dead = repo.health.dead()) }

    /**
     * [manual]: do người dùng bấm "Tải lại" -> có thông báo kết quả và thử lại cả các kênh đang chết.
     * [onFetched]: gọi khi đã nạp xong toàn bộ danh sách (để báo số kênh/link mới).
     */
    fun load(manual: Boolean = false, onFetched: ((List<Channel>) -> Unit)? = null) {
        viewModelScope.launch {
            val before = _ui.value.all.size
            if (manual) showNotice("Đang tải lại danh sách nguồn…")
            _ui.update { it.copy(loading = true, error = null) }
            val cached = repo.loadSnapshot() ?: repo.cached()   // ảnh chụp lần trước: hiện ngay
            if (cached.isNotEmpty()) _ui.update { it.copy(all = cached) }
            try {
                val fresh = repo.fetch { core ->
                    // Nguồn lõi xong: hiện ngay và bắt đầu kiểm tra, không chờ Thể thao/Phim/nguồn tự tìm
                    _ui.update { it.copy(all = core, loading = false) }
                    scan(ScanMode.NEW, autoFill = false)
                }
                _ui.update { it.copy(all = fresh, loading = false) }
                if (manual) {
                    val dc = (fresh.size - before).coerceAtLeast(0)
                    showNotice("Đã tải lại: ${fresh.size} kênh trong danh sách" + (if (dc > 0) ", thêm $dc kênh mới" else "") + ". Đang thử lại các kênh chưa chạy được…")
                }
                onFetched?.invoke(fresh)
            } catch (e: Exception) {
                if (manual) showNotice("Không tải lại được: ${e.message}")
                _ui.update {
                    it.copy(loading = false, error = if (it.all.isEmpty()) "Lỗi: ${e.message}" else null)
                }
            }
            scanJob?.join()      // đợi lượt kiểm tra nguồn lõi xong rồi mới kiểm tra phần còn lại
            scan(if (manual) ScanMode.DEAD else ScanMode.NEW)   // tự kiểm tra kênh nền; kênh còn sống sẽ hiện dần
        }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }
    fun setGroup(g: String) = _ui.update { it.copy(group = g) }

    fun toggleFav(c: Channel) {
        val f = _ui.value.favorites.toMutableSet()
        if (!f.add(c.id)) f.remove(c.id)
        repo.favorites = f
        _ui.update { it.copy(favorites = f) }
    }

    private fun sourcesChanged() {
        _ui.update { it.copy(sources = repo.sources, builtIn = repo.builtIn, extras = repo.extras, all = emptyList(), group = "") }
        load()
    }

    /** Thêm link mới -> tạo nhóm mới mang tên [name]. */
    fun addSource(name: String, url: String) {
        val u = url.trim()
        if (u.isBlank()) return
        repo.sources = repo.sources + Source(name.trim().ifBlank { "Nhóm mới" }, u)
        sourcesChanged()
    }

    fun removeSource(s: Source) {
        repo.forget(s)
        repo.sources = repo.sources.filter { it != s }
        sourcesChanged()
    }

    fun toggleExtras() {
        repo.extras = !repo.extras
        sourcesChanged()
    }

    fun toggleBuiltIn() {
        repo.builtIn = !repo.builtIn
        sourcesChanged()
    }

    // ----- Xoá / di chuyển kênh (menu nhấn giữ) -----
    fun hideChannel(c: Channel) {
        val h = _ui.value.hidden + c.id
        repo.hidden = h
        _ui.update { it.copy(hidden = h) }
    }

    fun moveChannel(c: Channel, group: String) {
        val m = _ui.value.moved + (c.id to group)
        repo.moved = m
        _ui.update { it.copy(moved = m) }
    }

    fun restoreChannels() {
        repo.hidden = emptySet()
        repo.moved = emptyMap()
        _ui.update { it.copy(hidden = emptySet(), moved = emptyMap()) }
    }

    fun toggleLeveler() {
        repo.leveler = !repo.leveler
        Leveler.enabled = repo.leveler
        _ui.update { it.copy(leveler = repo.leveler) }
    }

    fun toggleAutoFill() {
        repo.autoFill = !repo.autoFill
        _ui.update { it.copy(autoFill = repo.autoFill) }
    }

    // ----- Chọn link tốt nhất khi phát -----
    /**
     * Thứ tự thử: link đo lại ngay lúc mở và còn tốt (điểm cao trước) -> link chưa đo kịp / chưa biết
     * -> link vừa lỗi -> link đã biết là chết. Link đầu tiên là link tốt nhất, các link sau là dự phòng theo chất lượng.
     */
    suspend fun rank(urls: List<String>): List<String> {
        val h = repo.health
        val ok = h.ok()
        val dead = h.dead()
        val okU = urls.filter { it in ok }.sortedByDescending { h.score(it) }
        val unknown = urls.filter { it !in ok && it !in dead }
        val deadU = urls.filter { it in dead }
        val all = okU + unknown
        val pool = all.take(6)
        val res = prober.measure(pool)
        for ((u, sc) in res) { if (sc > 0) h.succeed(u, sc) else h.penalize(u) }
        h.save()
        refreshHealth()
        val live = pool.filter { (res[it] ?: -1) > 0 }.sortedByDescending { h.score(it) }
        val unsure = pool.filter { res[it] == null }
        val failed = pool.filter { res[it]?.let { v -> v <= 0 } == true }
        return live + unsure + all.drop(6) + failed + deadU
    }

    /** Kết quả khi đang xem: phát được thật -> xóa lỗi; lỗi 1 lần chưa loại, lỗi liên tiếp 2 lần mới coi là chết. */
    fun report(c: Channel, ok: Boolean, url: String?) {
        if (url == null) return
        if (ok) repo.health.confirm(url) else repo.health.penalize(url)   // kênh không biến mất; chỉ link đó xuống cuối hàng
        repo.health.save()
        refreshHealth()
    }

    // ----- Kiểm tra kênh (chỉ hiện kênh chắc chắn sống; kết quả lưu mãi) -----
    fun scan(mode: ScanMode = ScanMode.NEW, autoFill: Boolean = true) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val h = repo.health
            val st = _ui.value
            val chans = st.all.filter { it.id !in st.hidden }   // kênh đã xoá không cần kiểm tra
            val ok0 = h.ok()
            val dead0 = h.dead()
            val mine = st.sources.filter { !it.auto }.map { it.name }.toSet()
            val targets = when (mode) {
                ScanMode.ALL -> chans
                ScanMode.DEAD -> chans.filter { c -> c.urls.none { it in ok0 } }
                ScanMode.NEW -> chans.filter { c -> c.urls.none { it in ok0 } && !c.urls.all { it in dead0 } }
            }.sortedBy { if (it.group in mine) 0 else 1 }     // nhóm bạn tự thêm được kiểm tra trước
            if (targets.isNotEmpty()) runScan(targets)
            if (isActive && autoFill) maybeAutoFill()
        }
    }

    private suspend fun runScan(targets: List<Channel>) {
        _ui.update { it.copy(scanTotal = targets.size, scanDone = 0) }
        val h = repo.health
        val done = AtomicInteger(0)
        val sem = Semaphore(12)
        // Link bị chậm/ngắt quãng lúc quét song song: CHƯA kết luận chết, đo lại ở lượt 2 khi mạng đã rảnh
        val retryLater = java.util.concurrent.ConcurrentLinkedQueue<String>()
        fun push(finished: Boolean) {
            h.save()
            _ui.update {
                it.copy(
                    ok = h.ok(), dead = h.dead(),
                    scanDone = done.get(), scanTotal = if (finished) 0 else it.scanTotal,
                )
            }
        }
        // Cập nhật giao diện tối đa ~1 lần/giây: cập nhật dồn dập làm TV box giật và tốn công ghi dữ liệu
        val lastPush = java.util.concurrent.atomic.AtomicLong(0L)
        fun maybePush() {
            val now = System.currentTimeMillis()
            val last = lastPush.get()
            if (now - last >= 1_000 && lastPush.compareAndSet(last, now)) push(false)
        }
        try {
            coroutineScope {
                targets.forEach { c ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            // Đo từng link của kênh, dừng khi đã có 2 link tốt (đủ để hiện kênh + 1 dự phòng; xếp hạng kỹ hơn lúc phát)
                            var good = 0
                            for (u in c.urls) {
                                if (!isActive) return@withPermit
                                var sc = prober.probe(u)
                                if (sc == 0) { delay(1_500); sc = prober.probe(u) }   // đo lại sau 1,5 s để không loại oan link chập chờn
                                if (sc > 0) { h.succeed(u, sc); good++; if (good >= 2) break }
                                else if (sc == HARD_DEAD) h.kill(u)      // 404 / sai tên miền / từ chối kết nối: chết thật
                                else retryLater.add(u)                   // chậm hoặc ngắt quãng: đo lại ở lượt 2
                            }
                            done.incrementAndGet()
                            maybePush()
                        }
                    }
                }
            }
            // Lượt 2: chỉ 3 luồng nên mỗi link được dùng trọn băng thông; vẫn lỗi mới kết luận là chết
            val again = retryLater.toList()
            if (again.isNotEmpty()) {
                val sem2 = Semaphore(3)
                coroutineScope {
                    again.forEach { u ->
                        launch(Dispatchers.IO) {
                            sem2.withPermit {
                                if (!isActive) return@withPermit
                                val sc = prober.probe(u)
                                if (sc > 0) h.succeed(u, sc) else h.kill(u)
                                maybePush()
                            }
                        }
                    }
                }
            }
        } finally {
            push(true)
        }
    }

    /** Có kênh không còn link sống -> ghi nhớ các kênh đó và tự tìm nguồn bổ sung (tối đa 12 giờ/lần). */
    private fun maybeAutoFill() {
        val st = _ui.value
        if (st.all.isEmpty()) return
        val missing = st.all.filter { c -> c.id !in st.hidden && c.urls.none { it in st.ok } }
        repo.wanted = missing.map { it.id }.toSet()
        if (!repo.autoFill || missing.isEmpty() || st.discovering) return
        if (System.currentTimeMillis() - repo.lastDiscover < FILL_EVERY_MS) return
        discover(auto = true)
    }

    // ----- Tự tìm playlist trên GitHub -----
    fun setToken(t: String) {
        repo.ghToken = t
        _ui.update { it.copy(ghToken = repo.ghToken, tokenMsg = null) }
    }

    fun checkToken() {
        viewModelScope.launch {
            _ui.update { it.copy(tokenMsg = "Đang kiểm tra token…") }
            val msg = checkGithubToken(repo.ghToken)
            _ui.update { it.copy(tokenMsg = msg) }
        }
    }

    fun discover(auto: Boolean = false) {
        if (discoverJob?.isActive == true) return
        repo.lastDiscover = System.currentTimeMillis()
        discoverJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    discovering = true,
                    discoverMsg = if (auto) "Có kênh chết: đang tự tìm nguồn bổ sung…" else "Đang tìm nguồn mới trên GitHub…",
                )
            }
            val beforeCh = _ui.value.all.size
            val beforeLinks = _ui.value.all.sumOf { it.urls.size }
            val d = Discovery(repo.ghToken)
            val urls = try {
                d.run { msg -> _ui.update { it.copy(discoverMsg = msg) } }
            } catch (e: Exception) { emptyList() }
            val builtin = BUILT_IN_URLS.toSet()
            val merged = (repo.found + urls).distinct().filter { it !in builtin }.take(200)
            repo.found = merged
            val note = when (d.problem) {
                "token" -> " Token GitHub sai hoặc hết hạn."
                "quota" -> " GitHub báo hết lượt tìm (nhập token để tìm được nhiều hơn)."
                "burst" -> " GitHub tạm chặn vì gọi dồn dập, thử lại sau."
                else -> ""
            }
            _ui.update {
                it.copy(
                    discovering = false, foundCount = merged.size,
                    discoverMsg = "Xong: tìm thấy ${urls.size} file, tổng ${merged.size} nguồn.$note Đang nạp lại danh sách…",
                )
            }
            load { fresh ->
                val dc = (fresh.size - beforeCh).coerceAtLeast(0)
                val dl = (fresh.sumOf { it.urls.size } - beforeLinks).coerceAtLeast(0)
                _ui.update {
                    it.copy(discoverMsg = "Đã nạp thêm $dc kênh và $dl link mới. Số kênh hiện chỉ tăng khi link mới được kiểm tra là chạy được.")
                }
            }
        }
    }

    fun clearFound() {
        repo.found = emptyList()
        _ui.update { it.copy(foundCount = 0, discoverMsg = null) }
        load()
    }
}
