package com.schedulewidget.mobile.notes.importer

/** What an imported file is, from its first bytes (trusted first), its name and its MIME type. */
enum class FileKind {
    PDF, PPTX, DOCX, PPT, DOC, IMAGE,
    /** A Flexcil document (.flx) or backup (.flex): ZIP containers, read by [FlexcilArchive]. */
    FLEXCIL,
    UNKNOWN;

    val isOffice: Boolean get() = this == PPTX || this == DOCX || this == PPT || this == DOC
    val isSlides: Boolean get() = this == PPTX || this == PPT
    /** Old binary formats: only Google Drive can convert them. */
    val isLegacy: Boolean get() = this == PPT || this == DOC

    /** MIME type sent to Google Drive with the upload. */
    val mime: String get() = when (this) {
        PDF -> "application/pdf"
        PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        PPT -> "application/vnd.ms-powerpoint"
        DOC -> "application/msword"
        IMAGE -> "image/*"
        FLEXCIL -> "application/zip"
        UNKNOWN -> "application/octet-stream"
    }

    /** [NoteMeta.source] of the notebook made from this kind. */
    val source: String get() = when (this) {
        PDF -> "pdf"
        PPTX, PPT -> "pptx"
        DOCX, DOC -> "docx"
        IMAGE -> "image"
        FLEXCIL -> "flexcil"
        UNKNOWN -> "pdf"
    }

    companion object {
        /** Bounds the page metadata and native PDF work performed for one imported document. */
        const val MAX_IMPORT_PAGES = 10_000

        /** MIME types the file picker and the "열기" intent filter accept. */
        val PICKER_MIMES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/msword",
            "image/jpeg", "image/png", "image/webp", "image/heic",
            "application/zip",
        )

        private fun byName(name: String?, mime: String?): FileKind {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            when (ext) {
                "pdf" -> return PDF
                "pptx", "pptm", "ppsx", "potx" -> return PPTX
                "docx", "docm", "dotx" -> return DOCX
                "ppt", "pps", "pot" -> return PPT
                "doc", "dot" -> return DOC
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> return IMAGE
                "flex", "flx" -> return FLEXCIL
            }
            val m = mime?.lowercase().orEmpty()
            return when {
                m == "application/pdf" -> PDF
                m.contains("presentationml") -> PPTX
                m.contains("wordprocessingml") -> DOCX
                m == "application/vnd.ms-powerpoint" -> PPT
                m == "application/msword" -> DOC
                m.startsWith("image/") -> IMAGE
                else -> UNKNOWN
            }
        }

        private fun starts(head: ByteArray, vararg bytes: Int) =
            head.size >= bytes.size && bytes.indices.all { (head[it].toInt() and 0xFF) == bytes[it] }

        /**
         * [head] = the first bytes of the file (up to 1 KB); [zipHas] tells whether a zip file has an entry (only asked
         * for zips). A mislabelled file (".pdf" that is really a .docx) is classified by its content.
         */
        fun detect(name: String?, mime: String?, head: ByteArray, zipHas: (String) -> Boolean): FileKind {
            val guess = byName(name, mime)
            val ascii = String(head, Charsets.ISO_8859_1)
            // A stored PDF inside a ZIP can expose %PDF- in the first KB. Check the outer container first.
            if (starts(head, 0x50, 0x4B, 0x03, 0x04)) {
                return when {
                    zipHas("ppt/presentation.xml") -> PPTX
                    zipHas("word/document.xml") -> DOCX
                    guess == FLEXCIL || zipHas("pages.index") -> FLEXCIL
                    else -> UNKNOWN
                }
            }
            if (ascii.startsWith("%PDF") || ascii.take(1024).contains("%PDF-")) return PDF
            if (starts(head, 0xD0, 0xCF, 0x11, 0xE0)) return if (guess == PPT || guess == DOC) guess else UNKNOWN
            if (starts(head, 0xFF, 0xD8, 0xFF) || starts(head, 0x89, 0x50, 0x4E, 0x47) || ascii.startsWith("GIF8") ||
                (ascii.startsWith("RIFF") && ascii.startsWith("WEBP", 8)) || ascii.startsWith("BM") && guess == IMAGE ||
                ascii.startsWith("ftyp", 4)
            ) return IMAGE
            // Content not recognised: an Office/PDF name can't be trusted, an image name may be (decoder decides).
            return if (guess == IMAGE) IMAGE else UNKNOWN
        }
    }
}
