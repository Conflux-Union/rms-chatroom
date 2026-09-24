package cn.net.rms.chatroom.ui.settings

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.local.AppDatabase
import coil.imageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class StorageCategoryKind {
    IMAGE_CACHE,
    OTHER_CACHE,
    UPDATES,
    CHAT_DB,
    APP_DATA,
    APP_PROGRAM
}

data class StorageCategory(
    val kind: StorageCategoryKind,
    val bytes: Long,
    /** True when the user can clear this category from the storage screen. */
    val cleanable: Boolean
)

data class StorageStats(
    val deviceTotalBytes: Long = 0,
    val deviceAvailableBytes: Long = 0,
    val categories: List<StorageCategory> = emptyList()
) {
    val deviceUsedBytes: Long get() = deviceTotalBytes - deviceAvailableBytes
    val appTotalBytes: Long get() = categories.sumOf { it.bytes }
    /** Pure caches included in the one-tap clean (chat history cache excluded). */
    val cacheBytes: Long
        get() = categories
            .filter { it.kind == StorageCategoryKind.IMAGE_CACHE || it.kind == StorageCategoryKind.OTHER_CACHE || it.kind == StorageCategoryKind.UPDATES }
            .sumOf { it.bytes }
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase
) : ViewModel() {

    companion object {
        // The schema pages plus the always-present WAL shared-memory index
        // (-shm) of an emptied Room DB. Clearing cannot go below this floor
        // without destroying the live database connection, so once the category
        // shrinks under it the clear affordance disappears instead of offering
        // a no-op clean.
        private const val EMPTY_CHAT_DB_FLOOR_BYTES = 128L * 1024
    }

    private val _stats = MutableStateFlow<StorageStats?>(null)
    val stats: StateFlow<StorageStats?> = _stats.asStateFlow()

    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _stats.value = withContext(Dispatchers.IO) { computeStats() }
        }
    }

    /**
     * One-tap clean: image cache + other cache + downloaded update packages.
     * Returns the bytes freed, or null when there was nothing to clean.
     */
    fun clearCaches(onDone: (Long?) -> Unit) {
        clearInternal(
            kinds = setOf(
                StorageCategoryKind.IMAGE_CACHE,
                StorageCategoryKind.OTHER_CACHE,
                StorageCategoryKind.UPDATES
            ),
            onDone = onDone
        )
    }

    fun clearCategory(kind: StorageCategoryKind, onDone: (Long?) -> Unit) {
        clearInternal(kinds = setOf(kind), onDone = onDone)
    }

    private fun clearInternal(kinds: Set<StorageCategoryKind>, onDone: (Long?) -> Unit) {
        val before = _stats.value?.categories
            ?.filter { it.kind in kinds && it.cleanable }
            ?.sumOf { it.bytes } ?: 0L
        if (before == 0L) return onDone(null)

        viewModelScope.launch {
            _clearing.value = true
            try {
                withContext(Dispatchers.IO) {
                    if (StorageCategoryKind.IMAGE_CACHE in kinds) clearImageCache()
                    if (StorageCategoryKind.OTHER_CACHE in kinds) clearOtherCache()
                    if (StorageCategoryKind.UPDATES in kinds) clearUpdates()
                    if (StorageCategoryKind.CHAT_DB in kinds) clearChatDb()
                }
                val updated = withContext(Dispatchers.IO) { computeStats() }
                _stats.value = updated
                // Report the measured drop rather than the pre-clear estimate so
                // the toast matches what actually left the disk.
                val after = updated.categories.filter { it.kind in kinds }.sumOf { it.bytes }
                onDone(before - after)
            } finally {
                _clearing.value = false
            }
        }
    }

    private fun computeStats(): StorageStats {
        val volume = context.getExternalFilesDir(null)
            ?.takeIf { it.exists() }
            ?: Environment.getExternalStorageDirectory()
        val statFs = StatFs(volume.absolutePath)

        val imageCache = dirSize(File(context.cacheDir, "image_cache"))
        val cacheTotal = dirSize(context.cacheDir)
        val externalCache = dirSize(context.externalCacheDir)
        val updates = dirSize(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        val chatDb = dirSize(File(context.applicationInfo.dataDir, "databases"))
        val appData = dirSize(context.filesDir) +
            dirSize(context.codeCacheDir) +
            dirSize(File(context.applicationInfo.dataDir, "shared_prefs"))
        val appInfo = context.applicationInfo
        val appProgram = (listOfNotNull(appInfo.sourceDir) + (appInfo.splitSourceDirs?.toList() ?: emptyList()))
            .sumOf { File(it).length() }

        return StorageStats(
            deviceTotalBytes = statFs.totalBytes,
            deviceAvailableBytes = statFs.availableBytes,
            categories = listOf(
                StorageCategory(StorageCategoryKind.IMAGE_CACHE, imageCache, cleanable = true),
                StorageCategory(StorageCategoryKind.OTHER_CACHE, cacheTotal - imageCache + externalCache, cleanable = true),
                StorageCategory(StorageCategoryKind.UPDATES, updates, cleanable = true),
                StorageCategory(StorageCategoryKind.CHAT_DB, chatDb, cleanable = chatDb > EMPTY_CHAT_DB_FLOOR_BYTES),
                StorageCategory(StorageCategoryKind.APP_DATA, appData, cleanable = false),
                StorageCategory(StorageCategoryKind.APP_PROGRAM, appProgram, cleanable = false)
            )
        )
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    private fun clearImageCache() {
        // Coil keeps a DiskLruCache journal over this directory; clearing through
        // the loader keeps its in-memory index consistent, then sweep any files
        // the journal-less state left behind.
        runCatching { context.imageLoader.diskCache?.clear() }
        deleteChildren(File(context.cacheDir, "image_cache"))
    }

    private fun clearOtherCache() {
        val imageCacheDir = File(context.cacheDir, "image_cache")
        context.cacheDir.listFiles()?.forEach { if (it != imageCacheDir) it.deleteRecursively() }
        deleteChildren(context.externalCacheDir)
    }

    private fun clearUpdates() {
        deleteChildren(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
    }

    // clearAllTables empties the pages but keeps them allocated, and the delete
    // transaction itself lands in the WAL. VACUUM then compacts the main file
    // and a truncating checkpoint zeroes the WAL, so the on-disk size drops to
    // the empty-schema floor.
    private fun clearChatDb() {
        appDatabase.clearAllTables()
        val db = appDatabase.openHelper.writableDatabase
        db.execSQL("VACUUM")
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    private fun deleteChildren(dir: File?) {
        dir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}
