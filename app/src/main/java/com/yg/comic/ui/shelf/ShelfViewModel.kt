package com.yg.comic.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yg.comic.data.BookRepository
import com.yg.comic.data.SettingsStore
import com.yg.comic.engine.NaturalOrder
import com.yg.comic.model.*
import com.yg.comic.sr.SRModel
import com.yg.comic.sr.preload.BookRefsBuilder
import com.yg.comic.sr.preload.PreloadSrQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShelfViewModel(
    private val repo: BookRepository,
    private val store: SettingsStore,
) : ViewModel() {

    private val _currentCollection = MutableStateFlow<Long?>(null)
    val currentCollection: StateFlow<Long?> = _currentCollection.asStateFlow()

    private val _path = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val path: StateFlow<List<Pair<Long, String>>> = _path.asStateFlow()

    /** 书架网格滚动位置：进阅读器再返回时保留，避免布局回跳。切合集时重置。 */
    var gridIndex: Int = 0
    var gridOffset: Int = 0

    val filter = store.filter.stateIn(viewModelScope, SharingStarted.Eagerly, "全部")
    val sort = store.sort.stateIn(viewModelScope, SharingStarted.Eagerly, SortMode.NATURAL)

    val collections = _currentCollection.flatMapLatest { repo.children(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<com.yg.comic.model.ComicCollection>())

    val allBooks = _currentCollection.flatMapLatest { repo.booksIn(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全部书（跨合集），用于合集卡片取第一本作封面 */
    val globalBooks = repo.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全部进度，用于书名显示阅读进度 */
    val progressMap = repo.allProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 网络书 id 集合，用于卡片显示"网"角标 */
    val networkBookIds = repo.networkBookIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val books = combine(allBooks, filter, sort) { list, f, s ->
        val filtered = if (f == "全部") list else list.filter { it.mediaType.label == f }
        when (s) {
            // 自然语言排序（默认）= 数值序：01 < 2 < 10。lowercase 仅在此处抹平大小写，不改全局 NaturalOrder，避免波及书源/目录页
            SortMode.NATURAL -> filtered.sortedWith(compareBy(NaturalOrder) { it.title.lowercase() })
            // 文件名排序 = 纯字符字典序：逐字符比，002 在 01 前
            SortMode.FILE_NAME -> filtered.sortedBy { it.title }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun enter(id: Long, name: String) {
        _currentCollection.value = id
        _path.value = _path.value + (id to name)
        gridIndex = 0; gridOffset = 0
    }

    fun navigateTo(index: Int) {
        if (index < 0) {
            _currentCollection.value = null
            _path.value = emptyList()
        } else {
            val p = _path.value.take(index + 1)
            _path.value = p
            _currentCollection.value = p.lastOrNull()?.first
        }
        gridIndex = 0; gridOffset = 0
    }

    fun setFilter(v: String) = viewModelScope.launch { store.setFilter(v) }
    fun setSort(v: SortMode) = viewModelScope.launch { store.setSort(v) }
    fun toggleFavorite(id: Long, fav: Boolean) = viewModelScope.launch { repo.setFavorite(id, fav) }
    fun deleteBook(id: Long) = viewModelScope.launch { repo.deleteBook(id) }

    /** 重命名合集：同时修正当前路径里保存的名字 */
    fun renameCollection(id: Long, name: String) = viewModelScope.launch {
        repo.renameCollection(id, name)
        _path.value = _path.value.map { if (it.first == id) id to name else it }
    }

    /** 删除合集：书和子合集移到顶层；若当前正在该合集内则退回顶层 */
    fun deleteCollection(id: Long) = viewModelScope.launch {
        repo.deleteCollection(id)
        if (_currentCollection.value == id || _path.value.any { it.first == id }) {
            _currentCollection.value = null
            _path.value = emptyList()
        }
    }

    fun merge(sourceIds: List<Long>, title: String, onDone: (Long) -> Unit) = viewModelScope.launch {
        onDone(repo.mergeBooks(sourceIds, title))
    }

    /** 整本书一次入队做后台预超分。取图/构造 refs 在 IO，回结果给界面 Toast。 */
    fun preloadBook(bookId: Long, onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            val refs = BookRefsBuilder.build(bookId, repo)
            if (refs.isEmpty()) {
                "这本书没有找到可超分的页面"
            } else {
                val model = SRModel.fromKey(store.srModelKey.first())
                val autoCrop = store.autoCropEnabled.first()
                PreloadSrQueue.enqueue(refs, model, autoCrop)
                "已将整本 ${refs.size} 页加入后台预超分"
            }
        }
        onResult(result)
    }
}
