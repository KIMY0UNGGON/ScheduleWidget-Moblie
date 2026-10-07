package com.schedulewidget.mobile.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

internal object NoteImportTree {
    private val extensions = setOf("pdf", "pptx", "docx", "flex", "flx")
    private const val MAX_FILES = 500
    private const val MAX_DIRECTORIES = 500

    /** Lists supported documents below a SAF directory, sorted by name within each folder. */
    fun list(app: Context, tree: Uri, stage: (String) -> Unit): List<Uri> {
        val out = ArrayList<Uri>()
        var visitedDirectories = 0
        stage("폴더 살펴보는 중")
        fun visit(docId: String, depth: Int) {
            if (depth > 12 || out.size >= MAX_FILES || visitedDirectories >= MAX_DIRECTORIES) return
            visitedDirectories++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val files = ArrayList<Pair<String, String>>()
            val dirs = ArrayList<Pair<String, String>>()
            app.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && files.size + dirs.size < MAX_FILES) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) dirs += name to id
                    else if (name.substringAfterLast('.', "").lowercase() in extensions) files += name to id
                }
            }
            files.sortedBy { it.first.lowercase() }.forEach { (_, id) ->
                if (out.size < MAX_FILES) out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
            stage("폴더 살펴보는 중 · ${out.size}개 찾음")
            dirs.sortedBy { it.first.lowercase() }.forEach { (_, id) -> visit(id, depth + 1) }
        }
        visit(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }
}
