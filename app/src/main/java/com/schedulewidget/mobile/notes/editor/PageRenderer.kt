package com.schedulewidget.mobile.notes.editor

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.render.PdfThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import com.schedulewidget.mobile.notes.NoteStore
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bitmaps of PDF pages for the editor. Whole pages are rendered at (about) fit-width resolution and kept in an LRU
 * cache bounded by memory; when zoomed in further, a "detail tile" covering just the visible part of each page is
 * rendered at screen resolution once the zoom settles. All rendering goes through one queue on [PdfThread], newest
 * request first, and requests nobody wants any more (page scrolled away) are dropped before they render.
 *
 * Pages may come from several PDFs in the notebook folder ([PageInfo.src], base.pdf by default) and may be rotated
 * ([PageInfo.rot]); bitmaps are keyed by uid and rotation, so a rotated page renders afresh.
 */
class PageRenderer(pdf: File?, private val scope: CoroutineScope, dir: File? = pdf?.parentFile) {
    /** Bumped (on the main thread) whenever a bitmap arrives: the page layer redraws. */
    var version by mutableIntStateOf(0)
        private set

    private val maxBytes = min(Runtime.getRuntime().maxMemory() / 4, 160L * 1024 * 1024).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Cached widths per page key (any cached level stands in while the wanted one renders). */
    private val levels = HashMap<String, MutableSet<Int>>()

    class Tile(val uid: String, val rect: RectF, val pxPerPt: Float, val bitmap: Bitmap, val rot: Int = 0, val w: Float = 0f)
    @Volatile private var tiles: Map<String, Tile> = emptyMap()
    @Volatile private var tileGeneration = 0

    private class Job(val key: String, val wanted: () -> Boolean, val run: (PdfPool) -> Unit)
    private val pending = ArrayList<Job>()
    private val queued = HashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pool = PdfPool(dir)
    private val enabled = dir != null
    private var closed = false

    init {
        if (enabled) scope.launch(PdfThread.dispatcher) { workLoop() }
    }

    private suspend fun workLoop() {
        while (!closed) {
            val job = synchronized(pending) {
                var picked: Job? = null
                while (pending.isNotEmpty()) {
                    val j = pending.removeAt(pending.size - 1)
                    queued.remove(j.key)
                    if (j.wanted()) { picked = j; break }
                }
                picked
            }
            if (job == null) { wake.receive(); continue }
            runCatching { job.run(pool) }
        }
    }

    private fun enqueue(key: String, wanted: () -> Boolean, run: (PdfPool) -> Unit) {
        if (!enabled || closed) return
        synchronized(pending) {
            if (!queued.add(key)) {
                // Already queued: move it to the front (it is wanted again now).
                val i = pending.indexOfFirst { it.key == key }
                if (i >= 0) pending.add(pending.removeAt(i))
                return
            }
            pending.add(Job(key, wanted, run))
        }
        wake.trySend(Unit)
    }

    private fun bumpOnMain() {
        scope.launch(Dispatchers.Main.immediate) { version++ }
    }

    /** Width in pixels to render [page] at for a page shown [shownPx] wide (bucketed so zooming doesn't re-render). */
    fun baseWidthFor(page: PageInfo, fitPx: Float, shownPx: Float): Int {
        val level = if (shownPx < fitPx * 0.6f) fitPx * 0.5f else fitPx
        // At most ~4 MP per page and 1600 px wide; sharper views come from detail tiles.
        val maxW = sqrt(4_000_000f * page.w / page.h)
        return min(level, min(1600f, maxW)).roundToInt().coerceAtLeast(64)
    }

    /** Cache identity of a page's look: the same uid rotated is another bitmap. */
    private fun pageKey(page: PageInfo): String = "${page.uid}/${PageOps.normRot(page.rot)}"

    /**
     * The best cached bitmap of [page] (closest to [widthPx]), requesting [widthPx] when it isn't cached yet.
     * [wanted] is asked again right before rendering. Call from the main thread.
     */
    fun page(page: PageInfo, widthPx: Int, wanted: () -> Boolean): Bitmap? {
        if (!page.isPdf) return null
        val pk = pageKey(page)
        val key = "$pk@$widthPx"
        val exact = cache.get(key)
        if (exact != null) return exact
        enqueue(key, wanted) { pool ->
            val d = pool.doc(page) ?: return@enqueue
            if (cache.get(key) != null || page.pdf >= d.pageCount) return@enqueue
            val h = (widthPx * page.h / page.w).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            d.render(page.pdf, bmp, PageSources.pdfMatrix(page, widthPx / page.w, h / page.h))
            cache.put(key, bmp)
            synchronized(levels) { levels.getOrPut(pk) { HashSet() }.add(widthPx) }
            bumpOnMain()
        }
        // Stand-in: the cached level closest in width.
        val widths = synchronized(levels) { levels[pk]?.toList() }.orEmpty()
        return widths.sortedBy { abs(it - widthPx) }.firstNotNullOfOrNull { cache.get("$pk@$it") }
    }

    fun tile(uid: String): Tile? = tiles[uid]

    /** A wanted detail tile: [rect] of page [page] (in points) at [pxPerPt]. */
    class TileRequest(val page: PageInfo, val rect: RectF, val pxPerPt: Float)

    /** Replaces the detail tiles by [requests] (empty = none needed at this zoom). */
    fun updateTiles(requests: List<TileRequest>) {
        if (!enabled) return
        val gen = ++tileGeneration
        val rot = requests.associate { it.page.uid to PageOps.normRot(it.page.rot) }
        for (r in requests) {
            val old = tiles[r.page.uid]
            if (old != null && old.rot == rot[r.page.uid] && old.w == r.page.w &&
                abs(old.pxPerPt - r.pxPerPt) < r.pxPerPt * 0.03f && old.rect.contains(r.rect)
            ) {
                // Still sharp enough and covers the view: keep it.
                continue
            }
            enqueue("tile:${r.page.uid}:$gen", { tileGeneration == gen }) { pool ->
                val d = pool.doc(r.page) ?: return@enqueue
                val w = (r.rect.width() * r.pxPerPt).roundToInt()
                val h = (r.rect.height() * r.pxPerPt).roundToInt()
                if (w < 1 || h < 1 || r.page.pdf >= d.pageCount) return@enqueue
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val m = PageSources.pdfMatrix(r.page, r.pxPerPt, r.pxPerPt).apply {
                    postTranslate(-r.rect.left * r.pxPerPt, -r.rect.top * r.pxPerPt)
                }
                d.render(r.page.pdf, bmp, m)
                if (tileGeneration != gen) return@enqueue
                tiles = tiles + (r.page.uid to Tile(r.page.uid, RectF(r.rect), r.pxPerPt, bmp, PageOps.normRot(r.page.rot), r.page.w))
                bumpOnMain()
            }
        }
        // Tiles of pages no longer wanted, or of a page that was rotated since, go away now.
        val dropped = tiles.filter { (uid, t) -> rot[uid] != t.rot || requests.none { it.page.uid == uid && it.page.w == t.w } }.keys
        if (dropped.isNotEmpty()) {
            tiles = tiles - dropped
            version++
        }
    }

    fun close() {
        closed = true
        wake.trySend(Unit)
        // The editor scope is being cancelled: close on the store scope, still on the PDF thread.
        NoteStore.scope.launch(PdfThread.dispatcher) { pool.close() }
        tiles = emptyMap()
        cache.evictAll()
    }
}
