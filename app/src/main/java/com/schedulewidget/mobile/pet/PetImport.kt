package com.schedulewidget.mobile.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Imports Codex-style characters like the desktop app does: a `pet.json` plus the spritesheet it names, either
 * picked as files, as a folder, or as the `.zip` the desktop app's "캐릭터 내보내기" writes. Each character is copied
 * into files/pets/<id>/ (pet.json + image) and selected with the manifest "pet:<id>".
 */
object PetImport {
    private const val MAX_JSON = 64 * 1024
    private const val MAX_IMAGE = 25 * 1024 * 1024
    private const val MAX_ZIP = 30 * 1024 * 1024
    private const val MAX_PIXELS = 25_000_000L
    private const val BUILDING = ".importing"
    private val json = Json { ignoreUnknownKeys = true }
    private const val BOM = 0xFEFF.toChar()
    /** Same list as the desktop CharacterCatalog.Extensions. */
    private val extensions = setOf("png", "webp", "gif", "jpg", "jpeg", "bmp")

    sealed interface Result {
        data class Imported(val manifest: String, val name: String) : Result
        /** pet.json was read but its image was not among the picked files: ask the user for [fileName]. */
        data class NeedsImage(val petJson: String, val fileName: String) : Result
        data class Failed(val message: String) : Result
    }

    fun dir(context: Context): File = File(context.filesDir, "pets")

    /** Handles files picked together: a .zip, or a pet.json with (optionally) its image. */
    fun fromFiles(context: Context, uris: List<Uri>): Result = runCatching {
        val named = uris.map { it to displayName(context, it) }
        named.firstOrNull { it.second.endsWith(".zip", true) }?.let { return fromZip(context, it.first) }
        val (jsonUri, _) = named.firstOrNull { it.second.endsWith(".json", true) }
            ?: return Result.Failed("pet.json 또는 캐릭터 .zip 파일을 선택해 주세요.")
        val text = readText(context, jsonUri)
        val sheet = imagePath(text) ?: return Result.Failed("pet.json에 spritesheetPath(또는 imagePath)가 없습니다.")
        val fileName = sheet.substringAfterLast('/').substringAfterLast('\\')
        val image = named.firstOrNull { it.second.equals(fileName, true) }
            ?: return Result.NeedsImage(text, fileName)
        save(context, text, readBytes(context, image.first, MAX_IMAGE))
    }.getOrElse { Result.Failed(it.message ?: "가져오지 못했습니다.") }

    /** Second step after [Result.NeedsImage]: the user picked the spritesheet. */
    fun withImage(context: Context, petJson: String, image: Uri): Result = runCatching {
        save(context, petJson, readBytes(context, image, MAX_IMAGE))
    }.getOrElse { Result.Failed(it.message ?: "가져오지 못했습니다.") }

    /** A folder (e.g. copied from the PC's %LocalAppData%\ScheduleWidget\Pet\<id>) containing pet.json and its image. */
    fun fromFolder(context: Context, tree: Uri): Result = runCatching {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val petJson = child(context, tree, root, "pet.json") ?: return Result.Failed("폴더에 pet.json이 없습니다.")
        val text = readText(context, petJson)
        val sheet = imagePath(text) ?: return Result.Failed("pet.json에 spritesheetPath(또는 imagePath)가 없습니다.")
        if (sheet.startsWith("/") || sheet.startsWith("\\") || sheet.contains(':'))
            return Result.Failed("캐릭터 이미지 경로는 pet.json 폴더 안의 상대 경로여야 합니다.")
        var node: Uri = root
        for (part in sheet.split('/', '\\').filter { it.isNotBlank() && it != "." }) {
            if (part == "..") return Result.Failed("캐릭터 이미지 경로는 pet.json 폴더 안이어야 합니다.")
            node = child(context, tree, node, part) ?: return Result.Failed("이미지 ‘$sheet’을(를) 폴더에서 찾지 못했습니다.")
        }
        save(context, text, readBytes(context, node, MAX_IMAGE))
    }.getOrElse { Result.Failed(it.message ?: "가져오지 못했습니다.") }

    /** The desktop "캐릭터 내보내기" .zip: pet.json at the top or inside one folder, plus the image it names. */
    fun fromZip(context: Context, zip: Uri): Result = runCatching {
        withTempZip(context) { temp ->
            val input = context.contentResolver.openInputStream(zip) ?: error("파일을 열 수 없습니다.")
            input.use { src -> temp.outputStream().use { copyLimited(src, it, MAX_ZIP.toLong(), "30MB 이하의 캐릭터 파일(.zip)을 선택해 주세요.") } }
            importZipFile(context, temp, null)
        }
    }.getOrElse { Result.Failed(it.message ?: "가져오지 못했습니다.") }

    /** [forcedId]: keep this character id (a character synced from Google Drive keeps its id on every device). */
    fun fromZipBytes(context: Context, zip: ByteArray, forcedId: String? = null): Result = runCatching {
        if (zip.size > MAX_ZIP) return Result.Failed("30MB 이하의 캐릭터 파일(.zip)을 선택해 주세요.")
        withTempZip(context) { temp ->
            temp.writeBytes(zip)
            importZipFile(context, temp, forcedId)
        }
    }.getOrElse { Result.Failed(it.message ?: "가져오지 못했습니다.") }

    private inline fun <T> withTempZip(context: Context, block: (File) -> T): T {
        val temp = File(context.cacheDir, "pet-" + UUID.randomUUID().toString().replace("-", "") + ".zip")
        try { return block(temp) } finally { temp.delete() }
    }

    /**
     * Mirrors the desktop ImportZip: only pet.json (at the top or one folder down) and the image it names are read, each
     * with its own size limit, so a zip full of other or huge entries is never unpacked into memory. Entry names are only
     * matched, never used as paths, so a zip-slip entry cannot write anywhere.
     */
    private fun importZipFile(context: Context, file: File, forcedId: String?): Result {
        val zf = try { ZipFile(file) } catch (e: Exception) { return Result.Failed("캐릭터 파일(.zip)을 읽을 수 없습니다.") }
        return zf.use { z ->
            val entries = z.entries().asSequence().filter { !it.isDirectory }.toList()
            fun norm(e: ZipEntry) = e.name.replace('\\', '/')
            val manifest = entries
                .filter { norm(it).substringAfterLast('/').equals("pet.json", true) && norm(it).count { c -> c == '/' } <= 1 }
                .minByOrNull { it.name.length }
                ?: return Result.Failed("zip 안에 pet.json이 없습니다.")
            if (manifest.size > MAX_JSON) return Result.Failed("pet.json은 64KB 이하여야 합니다.")
            val text = z.getInputStream(manifest).use { readLimited(it, MAX_JSON) }.toString(Charsets.UTF_8)
            val sheet = imagePath(text)?.replace('\\', '/') ?: return Result.Failed("pet.json에 spritesheetPath(또는 imagePath)가 없습니다.")
            if (sheet.startsWith("/") || sheet.contains(':') || sheet.split('/').contains(".."))
                return Result.Failed("캐릭터 이미지 경로는 pet.json 폴더 안의 상대 경로여야 합니다.")
            val prefix = norm(manifest).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val imageEntry = entries.firstOrNull { norm(it).equals(prefix + sheet, true) }
                ?: return Result.Failed("zip 안에서 이미지 ‘$sheet’을(를) 찾지 못했습니다.")
            if (imageEntry.size > MAX_IMAGE) return Result.Failed("파일이 너무 큽니다 (25MB 이하).")
            val image = z.getInputStream(imageEntry).use { readLimited(it, MAX_IMAGE) }
            save(context, text, image, forcedId, extensionOf(sheet))
        }
    }

    /** Same layout as the desktop "캐릭터 내보내기": pet.json (displayName, spritesheetPath/imagePath, spriteVersionNumber, animations) + spritesheet.<ext> (or image.<ext>). */
    fun exportZip(context: Context, id: String): ByteArray {
        require(Characters.isPetId(id)) { "캐릭터 ID가 올바르지 않습니다." }
        val folder = File(dir(context), id)
        val obj = parse(File(folder, "pet.json").also { if (it.length() > MAX_JSON) error("pet.json은 64KB 이하여야 합니다.") }.readText())
        val sheet = obj.str("spritesheetPath")?.takeIf { it.isNotBlank() }
        val source = resolveInside(folder, sheet ?: obj.str("imagePath") ?: error("이미지가 없습니다."))
            ?: error("캐릭터 폴더 밖의 이미지는 내보낼 수 없습니다.")
        if (!source.isFile || source.length() > MAX_IMAGE) error("캐릭터 이미지를 찾을 수 없습니다.")
        val ext = source.extension.lowercase().takeIf { it in extensions } ?: error("지원하지 않는 이미지 형식입니다.")
        val rows = if (sheet == null) 0 else rowsFor(obj["spriteVersionNumber"]?.jsonPrimitive?.intOrNull ?: 1)
        val imageName = (if (rows > 0) "spritesheet." else "image.") + ext
        val name = obj.str("displayName") ?: id
        val manifest = buildManifest(name, imageName, rows, Characters.extraAnimations(obj["animations"] as? JsonObject, rows))
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("pet.json")); z.write(manifest.toString().toByteArray(Charsets.UTF_8)); z.closeEntry()
            z.putNextEntry(ZipEntry(imageName)); source.inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
        return out.toByteArray()
    }

    /** Imported characters on this phone: id → display name (only folders whose name is a valid pet id, like desktop ImportedIds). */
    fun importedNames(context: Context): Map<String, String> =
        dir(context).listFiles().orEmpty()
            .filter { it.isDirectory && Characters.isPetId(it.name) && File(it, "pet.json").isFile }
            .associate { folder ->
                folder.name to (runCatching { parse(File(folder, "pet.json").readText()).str("displayName") }.getOrNull() ?: folder.name)
            }

    fun delete(context: Context, manifest: String) {
        val id = manifest.removePrefix(Characters.PET_PREFIX)
        if (!Characters.isPetId(id)) return
        File(dir(context), id).deleteRecursively()
        Characters.invalidate()
    }

    private fun save(context: Context, text: String, image: ByteArray, forcedId: String? = null, pathExt: String? = null): Result {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_JSON) return Result.Failed("pet.json은 64KB 이하여야 합니다.")
        if (image.size > MAX_IMAGE) return Result.Failed("파일이 너무 큽니다 (25MB 이하).")
        if (forcedId != null && !Characters.isPetId(forcedId)) return Result.Failed("캐릭터 ID가 올바르지 않습니다.")
        val obj = parse(text)
        val isSheet = obj.str("spritesheetPath")?.isNotBlank() == true
        val path = imagePath(text) ?: return Result.Failed("pet.json에 spritesheetPath(또는 imagePath)가 없습니다.")
        // Bounds only: the full bitmap is never decoded here.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result.Failed("이미지를 읽을 수 없습니다. (PNG·WebP·JPG·GIF·BMP)")
        if (isSheet && (bounds.outWidth != Characters.COLUMNS * Characters.CELL_W || bounds.outHeight !in Characters.SHEET_HEIGHTS)) {
            return Result.Failed(
                "스프라이트시트 크기가 맞지 않습니다 (${bounds.outWidth}×${bounds.outHeight}). 1536×1872·1536×2288·1536×2704·1536×3120·1536×3536을 지원합니다."
            )
        }
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return Result.Failed("이미지가 너무 큽니다 (2,500만 픽셀 이하).")
        val ext = (pathExt ?: extensionOf(path)).takeIf { it in extensions } ?: when (bounds.outMimeType) {
            "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/jpeg" -> "jpg"; "image/bmp", "image/x-ms-bmp" -> "bmp"
            else -> return Result.Failed("25MB 이하의 PNG, WebP, GIF, JPG 또는 BMP 이미지를 선택해 주세요.")
        }
        val rows = if (isSheet) bounds.outHeight / Characters.CELL_H else 0
        val name = obj.str("displayName")?.takeIf { it.isNotBlank() } ?: "내 캐릭터"
        val root = dir(context).apply { mkdirs() }
        // Like the desktop app: a new character gets a random id (never derived from the name, so two phones or PCs never
        // pick the same id for different characters); a synced one keeps its id.
        val id = forcedId ?: generateSequence { UUID.randomUUID().toString().replace("-", "") }.first { !File(root, it).exists() }
        val folder = File(root, id)
        if (File(folder, "pet.json").exists()) return Result.Failed("같은 캐릭터가 이미 있습니다.")
        // Built in a side folder and renamed into place, so the list never sees half a character.
        val building = File(root, id + BUILDING)
        building.deleteRecursively()
        try {
            if (!building.mkdirs()) error("캐릭터를 저장할 수 없습니다.")
            val imageName = "image.$ext"
            File(building, imageName).writeBytes(image)
            val manifest = buildManifest(name, imageName, rows, Characters.extraAnimations(obj["animations"] as? JsonObject, rows))
            File(building, "pet.json").writeText(manifest.toString(), Charsets.UTF_8)
            folder.deleteRecursively() // leftovers of a broken earlier copy (no pet.json)
            if (!building.renameTo(folder)) error("캐릭터를 저장할 수 없습니다.")
        } finally {
            if (building.exists()) building.deleteRecursively()
        }
        Characters.invalidate()
        return Result.Imported(Characters.PET_PREFIX + id, name)
    }

    /** Desktop BuildManifest: name, image (spritesheet or single picture), format and extra actions. */
    private fun buildManifest(displayName: String, imageName: String, rows: Int, animations: Map<String, SpriteAnim>): JsonObject {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("displayName" to JsonPrimitive(displayName))
        fields[if (rows > 0) "spritesheetPath" else "imagePath"] = JsonPrimitive(imageName)
        if (rows > 0) fields["spriteVersionNumber"] = JsonPrimitive((rows - 7) / 2)
        if (animations.isNotEmpty()) {
            fields["animations"] = JsonObject(animations.mapValues { (_, a) ->
                JsonObject(mapOf("row" to JsonPrimitive(a.row), "frames" to JsonPrimitive(a.frames), "duration" to JsonPrimitive(a.durations.firstOrNull() ?: 150)))
            })
        }
        return JsonObject(fields)
    }

    private fun rowsFor(version: Int) = if (version in 1..5) 7 + 2 * version else 0

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text.trimStart(BOM)).jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun extensionOf(path: String) = path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()

    /** [relative] inside [folder], or null when it points outside it. */
    private fun resolveInside(folder: File, relative: String): File? {
        val base = folder.canonicalFile
        val file = File(base, relative.replace('\\', '/')).canonicalFile
        return file.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun imagePath(text: String): String? = runCatching {
        val o = parse(text)
        o.str("spritesheetPath")?.takeIf { it.isNotBlank() } ?: o.str("imagePath")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun child(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1)?.equals(name, true) == true) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun readText(context: Context, uri: Uri): String = readBytes(context, uri, MAX_JSON).toString(Charsets.UTF_8)

    private fun readBytes(context: Context, uri: Uri, limit: Int): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { readLimited(it, limit) } ?: error("파일을 열 수 없습니다.")

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(input, out, limit.toLong(), if (limit == MAX_JSON) "pet.json은 64KB 이하여야 합니다." else "파일이 너무 큽니다 (25MB 이하).")
        return out.toByteArray()
    }

    private fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream, limit: Long, message: String) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) error(message)
            out.write(buf, 0, n)
        }
    }
}
