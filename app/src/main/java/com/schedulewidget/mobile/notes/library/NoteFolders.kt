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
 */
internal object NoteFolders {
    private const val PREFS = "notes_library"
    private const val KEY = "folders"
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saved(context: Context): Set<String> = prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    /** All folders (saved ones plus any folder a notebook is in), sorted by name. */
    fun all(context: Context, notes: List<NoteMeta>): List<String> =
        (saved(context) + notes.mapNotNull { it.folder }).filter { it.isNotBlank() }.distinct()
            .sortedWith(java.text.Collator.getInstance(java.util.Locale.KOREAN))

    fun add(context: Context, name: String) {
        prefs(context).edit { putStringSet(KEY, saved(context) + name) }
        _changes.value++
    }

    /** Renames the folder and moves its notebooks along. */
    suspend fun rename(context: Context, from: String, to: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val notes = NoteStore.list(app)
        prefs(app).edit { putStringSet(KEY, saved(app) - from + to) }
        notes.filter { it.folder == from }.forEach { NoteStore.move(app, it.id, to) }
        _changes.value++
    }

    /** Removes the folder; its notebooks go back to the top level (nothing is deleted). */
    suspend fun remove(context: Context, name: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val notes = NoteStore.list(app)
        prefs(app).edit { putStringSet(KEY, saved(app) - name) }
        notes.filter { it.folder == name }.forEach { NoteStore.move(app, it.id, null) }
        _changes.value++
    }
}
