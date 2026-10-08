package com.schedulewidget.mobile.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class SpriteBoundsTest {
    @Test fun transparentFramesHaveNoPaintedBounds() {
        assertTrue(scanCells(IntArray(80), 10, 8, 2, 2).all { it == null })
    }

    @Test fun adjacentFramesAndUnusedSheetPixelsStaySeparate() {
        val pixels = IntArray(12 * 11)
        pixels[1 * 12 + 1] = -1
        pixels[8 * 12 + 10] = -1
        pixels[10 * 12 + 5] = -1 // incomplete bottom row is not a frame
        val cells = scanCells(pixels, 12, 11, 2, 2)
        assertNotNull(cells[0])
        assertNull(cells[1])
        assertNull(cells[2])
        assertNotNull(cells[3])
        assertEquals(1, cells[0]!!.left)
        assertEquals(2, cells[0]!!.right)
        assertEquals(4, cells[3]!!.left)
        assertEquals(5, cells[3]!!.right)
        assertEquals(3, cells[3]!!.top)
        assertEquals(4, cells[3]!!.bottom)
    }

    @Test fun silhouetteRunsPreserveNotchesAndInnerHoles() {
        val pixels = IntArray(8 * 7)
        for (y in 1..5) for (x in 1..6) pixels[y * 8 + x] = -1
        pixels[1 * 8 + 1] = 0
        pixels[3 * 8 + 3] = 0
        pixels[3 * 8 + 4] = 0
        val cell = checkNotNull(scanCells(pixels, 8, 7, 1, 1).single())
        assertEquals(1, cell.left)
        assertEquals(7, cell.right)
        assertEquals(1, cell.top)
        assertEquals(6, cell.bottom)
        for (y in 0 until 7) for (x in 0 until 8) {
            val covered = cell.runs.indices.step(4).any { i ->
                x >= cell.runs[i] && y >= cell.runs[i + 1] && x < cell.runs[i + 2] && y < cell.runs[i + 3]
            }
            assertEquals("Pixel $x,$y", pixels[y * 8 + x] != 0, covered)
        }
    }

    @Test fun faintSpecksDoNotExpandTheHitbox() {
        val cell = checkNotNull(scanCells(intArrayOf(1 shl 24, VISIBLE_ALPHA shl 24, 0), 3, 1, 1, 1).single())
        assertEquals(1, cell.left)
        assertEquals(2, cell.right)
    }

    @Test fun paintedPixelsReachAllWallsWhileTransparentMarginsCanExtendOutside() {
        val area = Size(300f, 400f)
        val box = Size(100f, 200f)
        val paint = Rect(.2f, .3f, .8f, .9f)
        val topLeft = clampToWalls(Offset(-1000f, -1000f), area, box, paint)
        val bottomRight = clampToWalls(Offset(1000f, 1000f), area, box, paint)
        assertEquals(0f, topLeft.x - box.width / 2 + paint.left * box.width, .001f)
        assertEquals(0f, topLeft.y - box.height / 2 + paint.top * box.height, .001f)
        assertEquals(area.width, bottomRight.x - box.width / 2 + paint.right * box.width, .001f)
        assertEquals(area.height, bottomRight.y - box.height / 2 + paint.bottom * box.height, .001f)
        assertTrue(topLeft.x - box.width / 2 < 0)
        assertTrue(bottomRight.y + box.height / 2 > area.height)
    }

    @Test fun loadingAndOversizedPetsClampWithoutThrowing() {
        assertEquals(Offset(50f, 50f), clampToWalls(Offset.Zero, Size(300f, 300f), Size(100f, 100f), null))
        val clamped = clampToWalls(Offset(999f, 999f), Size(10f, 10f), Size(100f, 100f), Rect(.25f, .25f, .75f, .75f))
        assertEquals(0f, clamped.x - 50f + 25f, .001f)
        assertEquals(0f, clamped.y - 50f + 25f, .001f)
    }
}
