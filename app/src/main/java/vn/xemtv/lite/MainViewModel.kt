package vn.xemtv.lite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
) {
    /** Chỉ kênh có ít nhất 1 link ĐÃ kiểm tra và chạy được. */
    val alive: List<Channel> = all.filter { c -> c.urls.any { it in ok } }
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
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var scanJob: Job? = null
    private var discoverJob: Job? = null

    init { load() }

    private fun refreshHealth() = _ui.update { it.copy(ok = repo.health.ok(), dead = repo.health.dead()) }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val cached = repo.cached()
            if (cached.isNotEmpty()) _ui.update { it.copy(all = cached) }
            try {
                val fresh = repo.fetch()
                _ui.update { it.copy(all = fresh, loading = false) }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(loading = false, error = if (it.all.isEmpty()) "Lỗi: ${e.message}" else null)
                }
            }
            scan(ScanMode.NEW)   // tự kiểm tra kênh nền; kênh còn sống sẽ hiện dần
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
        for ((u, sc) in res) { if (sc > 0) h.succeed(u, sc) else h.fail(u) }
        h.save()
        refreshHealth()
        val live = pool.filter { (res[it] ?: -1) > 0 }.sortedByDescending { h.score(it) }
        val unsure = pool.filter { (res[it] ?: -1) < 0 }
        val failed = pool.filter { res[it] == 0 }
        return live + unsure + all.drop(6) + failed + deadU
    }

    /** Kết quả khi đang xem: phát được thật -> xóa lỗi; lỗi 1 lần chưa loại, lỗi liên tiếp 2 lần mới coi là chết. */
    fun report(c: Channel, ok: Boolean, url: String?) {
        if (url == null) return
        if (ok) repo.health.confirm(url) else repo.health.fail(url)
        repo.health.save()
        refreshHealth()
    }

    // ----- Kiểm tra kênh (chỉ hiện kênh chắc chắn sống; kết quả lưu mãi) -----
    fun scan(mode: ScanMode = ScanMode.NEW) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val h = repo.health
            val chans = _ui.value.all
            val ok0 = h.ok()
            val dead0 = h.dead()
            val targets = when (mode) {
                ScanMode.ALL -> chans
                ScanMode.DEAD -> chans.filter { c -> c.urls.none { it in ok0 } }
                ScanMode.NEW -> chans.filter { c -> c.urls.none { it in ok0 } && !c.urls.all { it in dead0 } }
            }
            if (targets.isNotEmpty()) runScan(targets)
            if (isActive) maybeAutoFill()
        }
    }

    private suspend fun runScan(targets: List<Channel>) {
        _ui.update { it.copy(scanTotal = targets.size, scanDone = 0) }
        val h = repo.health
        val done = AtomicInteger(0)
        val sem = Semaphore(12)
        fun push(finished: Boolean) {
            h.save()
            _ui.update {
                it.copy(
                    ok = h.ok(), dead = h.dead(),
                    scanDone = done.get(), scanTotal = if (finished) 0 else it.scanTotal,
                )
            }
        }
        try {
            coroutineScope {
                targets.forEach { c ->
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            // Đo từng link của kênh, dừng khi đã có 3 link tốt (đủ để xếp hạng, đỡ tốn mạng)
                            var good = 0
                            for (u in c.urls) {
                                if (!isActive) return@withPermit
                                var sc = prober.probe(u)
                                if (sc == 0) sc = prober.probe(u)   // đo lại 1 lần để không loại oan link chập chờn
                                if (sc > 0) { h.succeed(u, sc); good++; if (good >= 3) break }
                                else if (sc == 0) h.kill(u)
                            }
                            val d = done.incrementAndGet()
                            if (d % 3 == 0) push(false)
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
        val missing = st.all.filter { c -> c.urls.none { it in st.ok } }
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
            load()
        }
    }

    fun clearFound() {
        repo.found = emptyList()
        _ui.update { it.copy(foundCount = 0, discoverMsg = null) }
        load()
    }
}
