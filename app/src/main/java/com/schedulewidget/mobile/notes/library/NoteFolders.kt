package com.schedulewidget.mobile.notes.library

import android.content.Context
import androidx.core.content.edit
import com.schedulewidget.mobile.notes.NoteMeta
import com.schedulewidget.mobile.notes.NoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Library folders. A folder is just [NoteMeta.folder]; folders made with "새 폴더" that hold no notebook yet are kept
 * here (SharedPreferences) so they don't vanish before something is moved in.
 *
 * Folders nest by '/' in the name ("백업/수학/미적분"): a virtual path, never a file system path. A parent exists
 * whenever anything below it does.
 */
internal object NoteFolders {
    private const val PREFS = "notes_library"
    private const val KEY = "folders"
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saved(context: Context): Set<String> = prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    /** All folders (saved ones, any folder a notebook is in, and their parents), sorted by name. */
    fun all(context: Context, notes: List<NoteMeta>): List<String> = ancestors(saved(context) + notes.mapNotNull { it.folder })

    /** [paths] plus every parent they imply, distinct and sorted by name. */
    fun ancestors(paths: Collection<String>): List<String> =
        paths.flatMap { p -> p.indices.filter { p[it] == '/' }.map { p.substring(0, it) } + p }
            .filter { it.isNotBlank() }.distinct()
            .sortedWith(java.text.Collator.getInstance(java.util.Locale.KOREAN))

    /** The folders directly inside [parent] (null = top level), as full paths. */
    fun children(paths: Collection<String>, parent: String?): List<String> = ancestors(paths).filter { parentOf(it) == parent }

    fun parentOf(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }

    /** The last part of [path], what the folder is called on screen. */
    fun leaf(path: String): String = path.substringAfterLast('/')

    /** [path] is [folder] itself or somewhere below it ("A/B" is within "A", "AB" is not). */
    fun isWithin(path: String?, folder: String): Boolean = path != null && (path == folder || path.startsWith("$folder/"))

    /** [path] after moving [from] to [to] (null = top level): descendants keep their place under [to]. */
    fun remap(path: String?, from: String, to: String?): String? = when {
        path == from -> to
        path != null && path.startsWith("$from/") -> path.removePrefix("$from/").let { rest -> to?.let { "$it/$rest" } ?: rest }
        else -> path
    }

    /** [path], or "[path] (2)", "(3)"… when a folder (or something below one) already uses it. */
    fun uniquePath(path: String, existing: Collection<String>): String {
        val taken = ancestors(existing).toSet()
        if (path !in taken) return path
        return generateSequence(2) { it + 1 }.map { "$path ($it)" }.first { it !in taken }
    }

    fun add(context: Context, name: String) {
        prefs(context).edit { putStringSet(KEY, saved(context) + name) }
        _changes.value++
    }

    /** Renames the folder; its subfolders and notebooks move along. */
    suspend fun rename(context: Context, from: String, to: String) = relocate(context, from, to)

    /** Removes the folder; what was inside moves up one level (nothing is deleted). */
    suspend fun remove(context: Context, name: String) = relocate(context, name, parentOf(name))

    private suspend fun relocate(context: Context, from: String, to: String?) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val notes = NoteStore.list(app)
        prefs(app).edit { putStringSet(KEY, saved(app).mapNotNull { remap(it, from, to) }.toSet()) }
        notes.filter { isWithin(it.folder, from) }.forEach { NoteStore.move(app, it.id, remap(it.folder, from, to)) }
        _changes.value++
    }
}
