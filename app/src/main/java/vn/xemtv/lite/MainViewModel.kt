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
    val scanLabel: String = "Đang kiểm tra kênh",   // đang quét nhóm nào (hiện cạnh tiến trình)
    val ghToken: String = "",
    val foundCount: Int = 0,
    val discovering: Boolean = false,
    val discoverMsg: String? = null,
    val tokenMsg: String? = null,
    val autoFill: Boolean = true,    // tự tìm nguồn bổ sung khi có kênh chết
    val leveler: Boolean = true,     // cân bằng âm lượng giữa các kênh
    val merges: Map<String, String> = emptyMap(),         // gộp thủ công: id kênh bị gộp -> id kênh đích
    val customNumbers: Map<String, Int> = emptyMap(),     // số kênh người dùng tự đặt
    val preferred: Map<String, String> = emptyMap(),      // link mặc định của từng kênh
    val customLogos: Map<String, String> = emptyMap(),    // logo người dùng tự đặt: id kênh -> link ảnh
    val deletedLinks: Set<String> = emptySet(),           // link đã xoá khỏi kênh
    val groupLabels: Map<String, String> = emptyMap(),    // tên hiển thị người dùng đổi cho nút nhóm
    val groupOrder: List<String> = emptyList(),           // thứ tự nút nhóm người dùng tự xếp
    val deletedGroups: Set<String> = emptySet(),          // nhóm dựng sẵn đã xoá nút
    val sourceStatus: Map<String, String> = emptyMap(),   // kết quả nạp từng link nguồn (OK:<số kênh> hoặc lý do lỗi)
    val logoIndex: Map<String, String> = emptyMap(),      // logo tự tìm trên GitHub: khoá tên kênh -> link ảnh
    val hidden: Set<String> = emptySet(),          // kênh người dùng đã xoá (id)
    val moved: Map<String, String> = emptyMap(),   // kênh người dùng đã di chuyển: id -> nhóm mới
    val notice: String? = null,                    // thông báo ngắn (VD: kết quả bấm Tải lại)
) {
    private val userGroups = sources.filter { !it.auto }.map { it.name }.toSet()

    /**
     * Kênh có ít nhất 1 link ĐÃ kiểm tra và chạy được, bỏ kênh đã xoá, áp dụng nhóm đã di chuyển.
     * Nhóm do bạn tự thêm thì hiện NGAY khi nạp xong (không chờ kiểm tra), chỉ ẩn khi mọi link đều đã kiểm tra là chết.
     */
    val alive: List<Channel> = run {
        val byId = all.associateBy { it.id }
        // Gộp thủ công: kênh bị gộp biến mất, mọi link của nó được nhập vào kênh đích
        fun root(id: String): String {
            var cur = id
            var n = 0
            while (n++ < 8) {
                val nx = merges[cur] ?: return cur
                if (byId.containsKey(nx) && nx !in hidden) cur = nx else return cur
            }
            return cur
        }
        val extra = HashMap<String, MutableList<String>>()
        val absorbed = HashSet<String>()
        for (src in merges.keys) {
            val s = byId[src] ?: continue
            val r = root(src)
            if (r != src) { absorbed += src; extra.getOrPut(r) { mutableListOf() }.addAll(s.urls) }
        }
        all.asSequence()
            .filter { it.id !in absorbed }
            .map { c -> extra[c.id]?.let { ex -> c.copy(urls = (c.urls + ex).distinct()) } ?: c }
            // Link người dùng đã xoá không hiện lại; kênh hết link thì ẩn
            .map { c ->
                val u = c.urls.filter { it !in deletedLinks }
                if (u.size != c.urls.size) c.copy(urls = u) else c
            }
            .filter { it.urls.isNotEmpty() }
            .filter { c ->
                c.id !in hidden &&
                    (c.urls.any { it in ok } || (c.group in userGroups && c.urls.any { it !in dead }))
            }
            .map { c ->
                // Logo: người dùng tự đặt > logo trong nguồn > logo tự tìm trên GitHub
                val lg = customLogos[c.id] ?: c.logo.ifBlank { logoIndex[dedupeKey(c.name)] ?: "" }
                if (lg != c.logo) c.copy(logo = lg) else c
            }
            .map { c -> moved[c.id]?.let { c.copy(group = it) } ?: c }
            .map { c -> if (c.group in deletedGroups) c.copy(group = G_OTHER) else c }   // nhóm đã xoá nút: kênh về "Khác"
            .toList()
    }
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
            merges = repo.merges, customNumbers = repo.customNumbers, preferred = repo.preferred,
            customLogos = repo.customLogos, logoIndex = repo.logoIndex(),
            deletedLinks = repo.deletedLinks, groupLabels = repo.groupLabels, groupOrder = repo.groupOrder, deletedGroups = repo.deletedGroups,
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
        // Tự tìm logo cho kênh thiếu logo (nền, không bắt buộc, tối đa 1 lần/7 ngày)
        viewModelScope.launch { if (repo.refreshLogoIndex()) _ui.update { it.copy(logoIndex = repo.logoIndex()) } }
    }

    private fun refreshHealth() = _ui.update { it.copy(ok = repo.health.ok(), dead = repo.health.dead()) }

    /**
     * [manual]: do người dùng bấm "Tải lại" -> có thông báo kết quả và thử lại cả các kênh đang chết.
     * [onFetched]: gọi khi đã nạp xong toàn bộ danh sách (để báo số kênh/link mới).
     */
    fun load(manual: Boolean = false, label2: String? = null, onFetched: ((List<Channel>) -> Unit)? = null) {
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
                    scan(ScanMode.NEW, autoFill = false, label = label2 ?: "Lượt 1/2: kênh Việt Nam")
                }
                _ui.update { it.copy(all = fresh, loading = false, sourceStatus = repo.statusSnapshot()) }
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
            // tự kiểm tra kênh nền; kênh còn sống sẽ hiện dần
            scan(
                if (manual) ScanMode.DEAD else ScanMode.NEW,
                label = label2 ?: if (manual) "Thử lại kênh chưa chạy được" else "Lượt 2/2: Thể thao, Phim & nguồn tự tìm",
            )
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

    private fun sourcesChanged(onFetched: ((List<Channel>) -> Unit)? = null) {
        _ui.update { it.copy(sources = repo.sources, builtIn = repo.builtIn, extras = repo.extras, all = emptyList(), group = "") }
        load(onFetched = onFetched)
    }

    /** Thêm link mới -> tạo nhóm mới mang tên [name]. */
    /** Thêm nhóm. Bỏ trống link = nhóm trống (không có nguồn) để tự chuyển kênh từ nhóm khác vào. */
    fun addSource(name: String, url: String) {
        val u = url.trim()
        val n = name.trim().ifBlank { "Nhóm mới" }
        val empty = u.isBlank() || u.equals("https://", true) || u.equals("http://", true)
        if (repo.sources.any { !it.auto && it.name.equals(n, true) } || n in VN_GROUPS || n == G_SPORT || n == G_MOVIE || n == G_OTHER) {
            showNotice("Đã có nhóm tên \"$n\", hãy chọn tên khác.")
            return
        }
        if (empty) {
            repo.sources = repo.sources + Source(n, "")
            _ui.update { it.copy(sources = repo.sources) }
            showNotice("Đã tạo nhóm trống \"$n\". Giữ OK trên một kênh → Di chuyển kênh… để đưa vào nhóm.")
            return
        }
        val fixed = normalizeSourceUrl(u)          // link GitHub dạng trang (…/blob/…) tự đổi sang link file thô
        repo.sources = repo.sources + Source(n, fixed)
        sourcesChanged { fresh ->
            // Báo kết quả: số kênh nạp được, hoặc lý do không ra kênh nào (trước đây im lặng)
            val cnt = fresh.count { it.group == n }
            val st = repo.statusOf(fixed)
            val fixNote = if (fixed != u) " (đã đổi sang link file thô của GitHub)" else ""
            showNotice(
                if (cnt > 0) "Nhóm \"$n\": nạp được $cnt kênh$fixNote. Đang kiểm tra link…"
                else "Nhóm \"$n\" chưa có kênh: ${st?.takeIf { !it.startsWith("OK:") } ?: "không đọc được kênh nào từ link này"}$fixNote."
            )
        }
    }

    fun removeSource(s: Source) {
        repo.forget(s)
        repo.sources = repo.sources.filter { it != s }
        // Kênh đã được di chuyển vào nhóm này: bỏ ghi nhớ để chúng về lại nhóm gốc
        val m = repo.moved.filterValues { it != s.name }
        if (m.size != repo.moved.size) { repo.moved = m; _ui.update { it.copy(moved = m) } }
        if (s.url.isBlank()) _ui.update { it.copy(sources = repo.sources, group = if (it.group == s.name) "" else it.group) }
        else sourcesChanged()
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
        repo.merges = emptyMap()
        repo.customNumbers = emptyMap()
        repo.deletedLinks = emptySet()
        repo.groupLabels = emptyMap()
        repo.groupOrder = emptyList()
        repo.deletedGroups = emptySet()
        _ui.update {
            it.copy(
                hidden = emptySet(), moved = emptyMap(), merges = emptyMap(), customNumbers = emptyMap(),
                deletedLinks = emptySet(), groupLabels = emptyMap(), groupOrder = emptyList(), deletedGroups = emptySet(),
            )
        }
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
    suspend fun rank(urls: List<String>, preferred: String? = null): List<String> {
        val h = repo.health
        val ok = h.ok()
        val dead = h.dead()
        val okU = urls.filter { it in ok }.sortedByDescending { h.score(it) }
        val unknown = urls.filter { it !in ok && it !in dead }
        val deadU = urls.filter { it in dead }
        val all = okU + unknown
        // Link mặc định (người dùng chọn / đang dùng lần trước) luôn được đo và thử ĐẦU TIÊN, trừ khi vừa đo thấy hỏng
        val pref = preferred?.takeIf { it in urls }
        val pool = (listOfNotNull(pref) + all.filter { it != pref }).take(6)
        val res = prober.measure(pool)
        for ((u, sc) in res) { if (sc > 0) h.succeed(u, sc, prober.heightOf(u)) else h.penalize(u) }
        h.save()
        refreshHealth()
        val live = pool.filter { (res[it] ?: -1) > 0 }.sortedByDescending { h.score(it) }
        val unsure = pool.filter { res[it] == null }
        val failed = pool.filter { res[it]?.let { v -> v <= 0 } == true }
        val body = live + unsure + all.filter { it !in pool } + failed + deadU
        val front = pref?.takeIf { p -> res[p]?.let { it <= 0 } != true }
        return (if (front != null) listOf(front) + body else body).distinct()
    }

    fun linkScore(url: String): Int = repo.health.score(url)

    /** Xoá một link khỏi kênh (không hiện lại khi nạp danh sách; khôi phục ở Cài đặt). */
    fun deleteLink(url: String) {
        val set = repo.deletedLinks + url
        repo.deletedLinks = set
        _ui.update { it.copy(deletedLinks = set) }
        showNotice("Đã xoá link. Khôi phục: Cài đặt → Khôi phục.")
    }

    // ----- Nút nhóm: đổi tên / dời / xoá -----
    fun renameGroup(g: String, label: String) {
        val m = LinkedHashMap(repo.groupLabels)
        val t = label.trim()
        if (t.isEmpty() || t == g) m.remove(g) else m[g] = t
        repo.groupLabels = m
        _ui.update { it.copy(groupLabels = m) }
        showNotice(if (m.containsKey(g)) "Đã đổi tên nhóm \"$g\" thành \"${m[g]}\"." else "Nhóm \"$g\" đã về tên gốc.")
    }

    fun setGroupOrder(order: List<String>) {
        repo.groupOrder = order
        _ui.update { it.copy(groupOrder = order) }
    }

    /** Xoá nút nhóm: nhóm tự thêm bị xoá cùng nguồn của nó; nhóm dựng sẵn thì kênh chuyển sang "Khác". */
    fun deleteGroup(g: String) {
        val own = repo.sources.firstOrNull { !it.auto && it.name == g }
        if (own != null) {
            removeSource(own)
        } else if (g != G_OTHER) {
            val set = repo.deletedGroups + g
            repo.deletedGroups = set
            _ui.update { it.copy(deletedGroups = set, group = if (it.group == g) "" else it.group) }
        }
        val labels = repo.groupLabels - g
        repo.groupLabels = labels
        repo.groupOrder = repo.groupOrder.filter { it != g }
        _ui.update { it.copy(groupLabels = labels, groupOrder = repo.groupOrder) }
        showNotice("Đã xoá nhóm \"$g\".")
    }
    fun linkHeight(url: String): Int = repo.health.height(url)

    /** Độ phân giải thực tế khi đang phát (chiều cao video). */
    fun reportHeight(url: String, h: Int) {
        if (repo.health.setHeight(url, h)) repo.health.save()
    }

    /** Đặt (hoặc xoá, nếu [url] trống) logo riêng cho kênh. */
    fun setLogo(c: Channel, url: String) {
        val m = LinkedHashMap(repo.customLogos)
        if (url.isBlank()) m.remove(c.id) else m[c.id] = url.trim()
        repo.customLogos = m
        _ui.update { it.copy(customLogos = m) }
        showNotice(if (url.isBlank()) "Đã xoá logo riêng của \"${c.name}\"." else "Đã đặt logo cho \"${c.name}\".")
    }

    /** Kết quả khi đang xem: phát được thật -> xóa lỗi; lỗi 1 lần chưa loại, lỗi liên tiếp 2 lần mới coi là chết. */
    fun report(c: Channel, ok: Boolean, url: String?) {
        if (url == null) return
        if (ok) repo.health.confirm(url) else repo.health.penalize(url)   // kênh không biến mất; chỉ link đó xuống cuối hàng
        repo.health.save()
        refreshHealth()
        // Link đang phát được trở thành mặc định cho lần sau
        if (ok && repo.preferred[c.id] != url) {
            val m = LinkedHashMap(repo.preferred)
            m[c.id] = url
            repo.preferred = m
            _ui.update { it.copy(preferred = m) }
        }
    }

    /** Người dùng tự chọn link cho kênh: trở thành link mặc định cho lần sau. */
    fun setPreferred(c: Channel, url: String) {
        val m = LinkedHashMap(repo.preferred)
        m[c.id] = url
        repo.preferred = m
        _ui.update { it.copy(preferred = m) }
        showNotice("Đã đặt link ${c.urls.indexOf(url) + 1}/${c.urls.size} làm mặc định cho \"${c.name}\".")
    }

    // ----- Gộp kênh thủ công / đổi số kênh -----
    private fun rootOf(m: Map<String, String>, id: String): String {
        var cur = id
        var n = 0
        while (n++ < 10) cur = m[cur] ?: return cur
        return cur
    }

    fun mergeChannel(c: Channel, target: Channel) {
        if (c.id == target.id) return
        val m = LinkedHashMap(repo.merges)
        var r = rootOf(m, target.id)
        if (r == c.id) {                                   // đích đang bị gộp vào chính kênh này: bỏ các liên kết trỏ về nó
            m.entries.removeAll { it.value == c.id }
            r = rootOf(m, target.id)
        }
        m[c.id] = r
        repo.merges = m
        if (c.id in repo.favorites && target.id !in repo.favorites) repo.favorites = repo.favorites + target.id
        _ui.update { it.copy(merges = m, favorites = repo.favorites) }
        showNotice("Đã gộp \"${c.name}\" vào \"${target.name}\" (giữ mọi link).")
    }

    /** Tách tất cả kênh đã gộp vào [target]. */
    fun unmerge(target: Channel) {
        val m = LinkedHashMap(repo.merges.filterValues { it != target.id })
        repo.merges = m
        _ui.update { it.copy(merges = m) }
        showNotice("Đã tách các kênh đã gộp vào \"${target.name}\".")
    }

    fun setNumber(c: Channel, n: Int) {
        val m = LinkedHashMap(repo.customNumbers)
        m.remove(c.id)
        m[c.id] = n.coerceIn(1, 9999)       // đặt sau cùng = được ưu tiên khi trùng số
        repo.customNumbers = m
        _ui.update { it.copy(customNumbers = m) }
        showNotice("Đã đặt số $n cho \"${c.name}\".")
    }

    fun clearNumber(c: Channel) {
        val m = LinkedHashMap(repo.customNumbers)
        m.remove(c.id)
        repo.customNumbers = m
        _ui.update { it.copy(customNumbers = m) }
        showNotice("\"${c.name}\" trở về số mặc định.")
    }

    // ----- Kiểm tra kênh (chỉ hiện kênh chắc chắn sống; kết quả lưu mãi) -----
    fun scan(mode: ScanMode = ScanMode.NEW, autoFill: Boolean = true, label: String? = null) {
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
            val lb = label ?: when (mode) {
                ScanMode.NEW -> "Kiểm tra kênh mới"
                ScanMode.DEAD -> "Thử lại kênh đang chết"
                ScanMode.ALL -> "Kiểm tra lại tất cả kênh"
            }
            if (targets.isNotEmpty()) runScan(targets, lb)
            if (isActive && autoFill) maybeAutoFill()
        }
    }

    private suspend fun runScan(targets: List<Channel>, label: String) {
        val deletedLinks = _ui.value.deletedLinks
        _ui.update { it.copy(scanTotal = targets.size, scanDone = 0, scanLabel = label) }
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
                                if (u in deletedLinks) continue          // link người dùng đã xoá: khỏi đo
                                var sc = prober.probe(u)
                                if (sc == 0) { delay(1_500); sc = prober.probe(u) }   // đo lại sau 1,5 s để không loại oan link chập chờn
                                if (sc > 0) { h.succeed(u, sc, prober.heightOf(u)); good++; if (good >= 2) break }
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
                done.set(0)
                _ui.update { it.copy(scanLabel = "Đang đo lại link chập chờn", scanTotal = again.size, scanDone = 0) }
                val sem2 = Semaphore(3)
                coroutineScope {
                    again.forEach { u ->
                        launch(Dispatchers.IO) {
                            sem2.withPermit {
                                if (!isActive) return@withPermit
                                val sc = prober.probe(u)
                                if (sc > 0) h.succeed(u, sc, prober.heightOf(u)) else h.kill(u)
                                done.incrementAndGet()
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
            load(label2 = "Kiểm tra link mới tìm được") { fresh ->
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
