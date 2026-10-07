package com.schedulewidget.mobile.notes.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.NoteImport

/**
 * "다른 앱으로 열기" / "공유" of a PDF, PPT, Word or Flexcil (.flex/.flx) file into this app (the OpenWithNotes alias in
 * the manifest): imports it in the background; the 노트 tab opens the editor once it is ready. Several shared files
 * (ACTION_SEND_MULTIPLE) are imported one after another.
 */
object NoteOpenWith {
    private fun streams(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!list.isNullOrEmpty()) return list
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun urisOf(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri),
            )
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
    }

    /** Only external content providers may supply imports; our FileProvider exposes app-owned cache files. */
    internal fun isImportableUri(context: Context, uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        val authority = uri.authority?.substringAfterLast('@') ?: return false
        return !authority.equals("${context.packageName}.files", ignoreCase = true)
    }

    /** True when [intent] was a file for 노트 and its import started (the caller then shows the 노트 tab). */
    fun handle(context: Context, intent: Intent?): Boolean {
        val all = urisOf(intent)
        if (all.isEmpty()) return false
        val importable = all.filter { isImportableUri(context, it) }
        if (importable.size < all.size) {
            Toast.makeText(context, "열 수 없는 파일 위치의 항목 ${all.size - importable.size}개는 건너뛰어요", Toast.LENGTH_SHORT).show()
        }
        if (importable.isEmpty()) return true
        val settings = Repository.get(context).data.value.notes
        if (!settings.enabled) {
            Toast.makeText(context, "설정 > 노트에서 노트 기능을 켜면 파일을 노트로 열 수 있어요", Toast.LENGTH_LONG).show()
            return false
        }
        val convert = NoteImport.Convert.of(settings.convert)
        val started = if (importable.size == 1) NoteImport.start(context, importable[0], convert, folder = null, open = true)
        else NoteImport.startBatch(context, importable, convert, folder = null, open = false)
        if (!started) {
            Toast.makeText(context, "다른 파일을 가져오는 중이에요. 끝난 뒤 다시 열어 주세요", Toast.LENGTH_SHORT).show()
        }
        return true
    }
}
