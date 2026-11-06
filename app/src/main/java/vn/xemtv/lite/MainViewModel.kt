package vn.xemtv.lite

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
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
    val ok: Set<String> = emptySet(),      // link đã kiểm tra: chạy được
    val dead: Set<String> = emptySet(),    // link đã kiểm tra: chết
    val scanDone: Int = 0,
    val scanTotal: Int = 0,          // > 0: đang quét
    val ghToken: String = "",
    val foundCount: Int = 0,
    val discovering: Boolean = false,
    val discoverMsg: String? = null,
) {
    /** Chỉ kênh có ít nhất 1 link ĐÃ kiểm tra và chạy được. */
    val alive: List<Channel> = all.filter { c -> c.urls.any { it in ok } }
}

const val FAV = "__fav__"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo(app)
    private val prober = Prober()
    private val _ui = MutableStateFlow(
        UiState(
            favorites = repo.favorites, sources = repo.sources, builtIn = repo.builtIn, ok = repo.okStamps.fresh(), dead = repo.deadStamps.fresh(),
            ghToken = repo.ghToken, foundCount = repo.found.size,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var scanJob: Job? = null
    private var discoverJob: Job? = null

    init { load() }

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
            scan(false)   // tự kiểm tra kênh nền; kênh còn sống sẽ hiện dần
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
        _ui.update { it.copy(sources = repo.sources, builtIn = repo.builtIn, all = emptyList(), group = "") }
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

    fun toggleBuiltIn() {
        repo.builtIn = !repo.builtIn
        sourcesChanged()
    }

    // ----- Chọn link tốt nhất khi phát -----
    /** Thứ tự thử: link đã kiểm tra tốt -> link chưa biết -> link đã biết là chết. */
    suspend fun rank(urls: List<String>): List<String> {
        val st = _ui.value
        val okU = urls.filter { it in st.ok }
        val unknown = urls.filter { it !in st.ok && it !in st.dead }
        val deadU = urls.filter { it in st.dead && it !in st.ok }
        val pool = okU + unknown
        val best = prober.best(pool.take(8))
        return if (best != null) listOf(best) + (pool - best) + deadU else pool + deadU
    }

    fun report(c: Channel, ok: Boolean, url: String?) {
        if (ok && url != null) {
            repo.okStamps.add(listOf(url)); repo.deadStamps.remove(listOf(url))
        } else if (!ok) {
            repo.okStamps.remove(c.urls); repo.deadStamps.add(c.urls)
        }
        repo.okStamps.save(); repo.deadStamps.save()
        _ui.update { it.copy(ok = repo.okStamps.fresh(), dead = repo.deadStamps.fresh()) }
    }

    // ----- Kiểm tra kênh (chỉ hiện kênh chắc chắn sống) -----
    /** force = false: chỉ kiểm tra kênh chưa có kết quả mới (trong 6 giờ). force = true: kiểm tra lại tất cả. */
    fun scan(force: Boolean = false) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val chans = _ui.value.all
            val ok0 = repo.okStamps.fresh()
            val dead0 = repo.deadStamps.fresh()
            val targets = if (force) chans
            else chans.filter { c -> c.urls.none { it in ok0 } && !c.urls.all { it in dead0 } }
            if (targets.isEmpty()) return@launch
            _ui.update { it.copy(scanTotal = targets.size, scanDone = 0) }
            val done = AtomicInteger(0)
            val sem = Semaphore(12)
            fun push(total: Boolean) {
                repo.okStamps.save(); repo.deadStamps.save()
                _ui.update {
                    it.copy(
                        ok = repo.okStamps.fresh(), dead = repo.deadStamps.fresh(),
                        scanDone = done.get(), scanTotal = if (total) 0 else it.scanTotal,
                    )
                }
            }
            try {
                coroutineScope {
                    targets.forEach { c ->
                        launch(Dispatchers.IO) {
                            sem.withPermit {
                                val failed = ArrayList<String>()
                                var good: String? = null
                                for (u in c.urls) {
                                    if (!isActive) return@withPermit
                                    if (prober.check(u)) { good = u; break } else failed += u
                                }
                                if (good != null) { repo.okStamps.add(listOf(good)); repo.deadStamps.remove(listOf(good)) }
                                else repo.okStamps.remove(c.urls)
                                repo.okStamps.remove(failed)
                                repo.deadStamps.add(failed)
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
    }

    // ----- Tự tìm playlist trên GitHub -----
    fun setToken(t: String) {
        repo.ghToken = t
        _ui.update { it.copy(ghToken = repo.ghToken) }
    }

    fun discover() {
        if (discoverJob?.isActive == true) return
        discoverJob = viewModelScope.launch {
            _ui.update { it.copy(discovering = true, discoverMsg = "Đang tìm nguồn mới trên GitHub…") }
            val d = Discovery(repo.ghToken)
            val urls = try {
                d.run { msg -> _ui.update { it.copy(discoverMsg = msg) } }
            } catch (e: Exception) { emptyList() }
            val builtin = BUILT_IN_URLS.toSet()
            val merged = (repo.found + urls).distinct().filter { it !in builtin }.take(120)
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
