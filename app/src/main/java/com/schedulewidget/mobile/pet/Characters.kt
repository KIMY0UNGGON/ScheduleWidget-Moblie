package com.schedulewidget.mobile.pet

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One animation row of a Codex spritesheet (8 columns, 192x208 cells at full size). */
data class SpriteAnim(val row: Int, val frames: Int, val durations: List<Int>) {
    constructor(row: Int, frames: Int, duration: Int, last: Int = duration) :
        this(row, frames, List(frames) { if (it == frames - 1) last else duration })
}

data class CharacterInfo(
    val id: String,
    val name: String,
    val image: String,
    val rows: Int,
    val extra: Map<String, SpriteAnim> = emptyMap(),
    /** Imported by the user (files/pets/<id>); [image] is then an absolute file path instead of an asset path. */
    val imported: Boolean = false,
) {
    val manifest: String get() = if (imported) Characters.PET_PREFIX + id else "builtin:$id"
}

/** Mirrors Player/character.html and Services/CharacterCatalog.cs of the desktop app. */
object Characters {
    const val COLUMNS = 8
    const val CELL_W = 192
    const val CELL_H = 208
    const val DEFAULT = "builtin:mochi-white"
    const val URI_PREFIX = "uri:"
    const val PET_PREFIX = "pet:"
    /** Supported spritesheet heights (v1–v5) at 1536 px wide. */
    val SHEET_HEIGHTS = setOf(1872, 2288, 2704, 3120, 3536)

    val animations: Map<String, SpriteAnim> = mapOf(
        "idle" to SpriteAnim(0, 6, listOf(280, 110, 110, 140, 140, 320).map { it * 6 }),
        "waving" to SpriteAnim(3, 4, 140, 280),
        "jumping" to SpriteAnim(4, 5, 140, 280),
        "running" to SpriteAnim(7, 6, 120, 220),
        "running-left" to SpriteAnim(2, 8, 120, 220),
        "running-right" to SpriteAnim(1, 8, 120, 220),
        "waiting" to SpriteAnim(6, 6, 150, 260),
        "review" to SpriteAnim(8, 6, 150, 280),
        "failed" to SpriteAnim(5, 8, 140, 240),
    )

    val labels: List<Pair<String, String>> = listOf(
        "idle" to "대기", "waving" to "인사", "jumping" to "점프", "running" to "활동", "running-left" to "왼쪽 걷기",
        "running-right" to "오른쪽 걷기", "waiting" to "기다리기", "review" to "집중", "failed" to "당황",
    )
    val extraLabels: List<Pair<String, String>> = listOf(
        "listening" to "음악 듣기", "grooving" to "신남", "disliking" to "싫어함", "immersed" to "심취",
        "keyboard1" to "키보드 1", "keyboard2" to "키보드 2",
    )

    fun options(character: CharacterInfo?): List<Pair<String, String>> =
        labels + extraLabels.filter { character?.extra?.containsKey(it.first) == true } +
            (if ((character?.rows ?: 0) > 0) listOf("music" to "음악 반복", "typing" to "타이핑 반응") else emptyList()) +
            ("random" to "랜덤 순회")

    fun spec(character: CharacterInfo?, name: String): SpriteAnim =
        character?.extra?.get(name) ?: animations[name] ?: animations.getValue("idle")

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var catalog: List<CharacterInfo>? = null
    /** Bumped by [invalidate]; a load that started before an invalidation is returned but never cached. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    /** Safe from any thread (the first call reads files: prefer calling it off the main thread). */
    fun list(context: Context): List<CharacterInfo> {
        catalog?.let { return it }
        return synchronized(this) {
            catalog ?: run {
                val gen = generation.get()
                val loaded = load(context.applicationContext ?: context)
                if (generation.get() == gen) catalog = loaded
                loaded
            }
        }
    }

    fun find(context: Context, manifest: String): CharacterInfo? =
        if (!manifest.startsWith("builtin:") && !manifest.startsWith(PET_PREFIX)) null
        else list(context).firstOrNull { it.manifest == manifest }

    /** Re-reads the list after a character was imported or deleted. */
    fun invalidate() {
        generation.incrementAndGet()
        catalog = null
        // A re-imported / re-downloaded character reuses "<id>/image.<ext>", so a cached bitmap would be the old one.
        cache.evictAll()
    }

    /** Desktop CharacterCatalog.IsPetId: ASCII letters/digits, '-' and '_', at most 64 \u2014 safe as a folder name. */
    fun isPetId(id: String?): Boolean =
        !id.isNullOrEmpty() && id.length <= 64 && id.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' || it == '_' }

    private fun load(context: Context): List<CharacterInfo> {
        val preferredOrder = listOf("mochi-white", "mochi-black", "mochi-blue", "mochi-red")
        val builtin = loadFrom(runCatching { context.assets.list("characters")?.toList() }.getOrNull().orEmpty(), imported = false) { id ->
            context.assets.open("characters/$id/pet.json").bufferedReader().use { it.readText() } to "characters/$id/"
        }.sortedWith(compareBy({ it.id !in preferredOrder }, { preferredOrder.indexOf(it.id) }, { it.name }))
        val userDir = PetImport.dir(context)
        // Only real character folders (skips "<id>.importing" and anything whose name is not a valid id).
        val userIds = userDir.listFiles().orEmpty().filter { it.isDirectory && isPetId(it.name) }.map { it.name }
        val user = loadFrom(userIds, imported = true) { id ->
            val file = java.io.File(userDir, "$id/pet.json")
            if (file.length() > 64 * 1024) error("pet.json too large")
            file.readText() to java.io.File(userDir, id).absolutePath + "/"
        }.sortedBy { it.name }
        return builtin + user
    }

    private fun loadFrom(ids: List<String>, imported: Boolean, read: (String) -> Pair<String, String>): List<CharacterInfo> =
        ids.mapNotNull { id ->
            runCatching {
                val (text, base) = read(id)
                val obj = json.parseToJsonElement(text.trimStart('\uFEFF')).jsonObject
                val sheetPath = obj["spritesheetPath"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                // Desktop picture characters (imagePath, no animation) are drawn as a still image: rows = 0.
                val sheet = sheetPath
                    ?: (obj["imagePath"]?.jsonPrimitive?.contentOrNull?.takeIf { imported && it.isNotBlank() } ?: return@runCatching null)
                val image = if (imported) {
                    // The image must stay inside the character's own folder.
                    val folder = java.io.File(base).canonicalFile
                    val file = java.io.File(folder, sheet.replace('\\', '/')).canonicalFile
                    if (!file.path.startsWith(folder.path + java.io.File.separator) || !file.isFile) return@runCatching null
                    file.path
                } else base + sheet
                val version = obj["spriteVersionNumber"]?.jsonPrimitive?.intOrNull ?: 1
                if (sheetPath != null && version !in 1..5) return@runCatching null
                val rows = if (sheetPath == null) 0 else 7 + 2 * version
                CharacterInfo(
                    id = id,
                    name = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: id,
                    image = image,
                    rows = rows,
                    extra = extraAnimations(obj["animations"] as? JsonObject, rows),
                    imported = imported,
                )
            }.getOrNull()
        }

    /** Desktop ReadExtraAnimations: known keys only, row must exist; frames 1~8, duration 40~2000 ms (default 150). */
    internal fun extraAnimations(obj: JsonObject?, rows: Int): Map<String, SpriteAnim> {
        if (obj == null || rows <= 0) return emptyMap()
        return extraLabels.mapNotNull { (key, _) ->
            val spec = obj[key] as? JsonObject ?: return@mapNotNull null
            // Numbers only, like the desktop (JTokenType.Integer): "9" as a string does not count.
            fun num(k: String) = (spec[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val row = num("row") ?: return@mapNotNull null
            if (row !in 0 until rows) return@mapNotNull null
            val frames = (num("frames") ?: 6).coerceIn(1, 8)
            val duration = (num("duration") ?: 150).coerceIn(40, 2000)
            key to SpriteAnim(row, frames, duration)
        }.toMap()
    }

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Decodes a spritesheet from assets. [sample] 1 = full size (1536 px wide), 2 = half, ... Call off the main thread. */
    fun sheet(context: Context, character: CharacterInfo, sample: Int): Bitmap? {
        val key = "${character.image}@$sample"
        cache.get(key)?.let { return it }
        return runCatching {
            (if (character.imported) java.io.File(character.image).inputStream() else context.assets.open(character.image)).use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        }.getOrNull()?.also { cache.put(key, it) }
    }

    /** A picture character's image file, downsampled to ~[maxPx] on its longest side. Call off the main thread. */
    fun stillImage(path: String, maxPx: Int = 1024): Bitmap? {
        val key = "$path@$maxPx"
        cache.get(key)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = if (longest > maxPx) Integer.highestOneBit(longest / maxPx) else 1
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { cache.put(key, it) }
    }

    /** Decodes a user-picked image (first frame for GIFs), limited to ~[maxPx] on its longest side. */
    fun userImage(context: Context, uri: Uri, maxPx: Int = 1024): Bitmap? {
        val key = "$uri@$maxPx"
        cache.get(key)?.let { return it }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > maxPx) decoder.setTargetSampleSize(Integer.highestOneBit(longest / maxPx).coerceAtLeast(1))
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                val sample = if (longest > maxPx) Integer.highestOneBit(longest / maxPx) else 1
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()?.also { cache.put(key, it) }
    }

    fun userUri(manifest: String): Uri? = if (manifest.startsWith(URI_PREFIX)) {
        runCatching { Uri.parse(manifest.removePrefix(URI_PREFIX)) }.getOrNull()?.takeIf {
            it.scheme?.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) == true && !it.authority.isNullOrBlank()
        }
    } else null

    /** True when the system "remove animations" setting is on. */
    fun reduceMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
