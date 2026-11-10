package com.collage.maker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max

/** A ready-to-draw cell: polygon/path are in local coordinates (translate cx,cy then rotate rot). */
class Cell(
    val poly: Poly,
    val path: Path,
    val bounds: RectF,
    val cx: Float,
    val cy: Float,
    val rot: Float,
    val frame: RectF?
) {
    val transformed: Boolean get() = cx != 0f || cy != 0f || rot != 0f
}

class PreparedLayout(val cells: List<Cell>, val mask: Path?, val w: Float, val h: Float)

/** Per-cell photo assignment and the user's pan / zoom inside the cell. */
data class CellState(var imageIndex: Int, var scale: Float = 1f, var dx: Float = 0f, var dy: Float = 0f)

object LayoutEngine {

    /** Design space: the long side is always 1000 units. */
    fun designSize(rw: Int, rh: Int): Pair<Float, Float> =
        if (rw >= rh) Pair(1000f, 1000f * rh / rw) else Pair(1000f * rw / rh, 1000f)

    fun prepare(
        def: LayoutDef, n: Int, w: Float, h: Float,
        spacing: Float, corner: Float, seed: Long, text: String
    ): PreparedLayout {
        val half = spacing / 2f
        val content = RectF(half, half, w - half, h - half)
        val params = GenParams(max(1, n), w, h, content, seed, text)
        val raw = try {
            def.gen(params)
        } catch (e: Exception) {
            RawLayout(Layouts.gridRects(content, max(1, n), true).map { RawCell(Geo.rect(it)) })
        }
        val cells = ArrayList<Cell>()
        for (rc in raw.cells) {
            var poly = Geo.normalize(Geo.clean(rc.poly))
            if (poly.size < 3) continue
            if (rc.inset && half > 0f) poly = Geo.inset(poly, half)
            if (poly.size < 3) continue
            val b = Geo.bounds(poly)
            val path = when (rc.shape) {
                CellShape.POLY -> Geo.roundedPath(poly, corner)
                CellShape.OVAL -> Path().apply { addOval(b, Path.Direction.CW) }
                CellShape.HEART -> Shapes.heart(b)
            }
            cells.add(Cell(poly, path, b, rc.cx, rc.cy, rc.rot, rc.frame))
        }
        return PreparedLayout(cells, raw.mask, w, h)
    }
}

/** Draws a prepared layout. Used by the editor view, the thumbnails and the exporter. */
class Renderer {
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dst = RectF()
    private val tmp = RectF()
    private val placeholder = intArrayOf(0xFF9C8CF0.toInt(), 0xFF7C68E8.toInt(), 0xFFBDB2F6.toInt())

    fun draw(
        canvas: Canvas,
        layout: PreparedLayout,
        images: List<Bitmap>,
        states: List<CellState>?,
        bg: Int,
        selected: Int = -1,
        target: Int = -1,
        strokeW: Float = 4f,
        selectedColor: Int = Color.BLUE,
        targetColor: Int = Color.YELLOW
    ) {
        fill.style = Paint.Style.FILL
        fill.color = bg
        canvas.drawRect(0f, 0f, layout.w, layout.h, fill)
        val mask = layout.mask
        if (mask != null) {
            canvas.save()
            canvas.clipPath(mask)
        }
        for (i in layout.cells.indices) {
            val cell = layout.cells[i]
            canvas.save()
            if (cell.transformed) {
                canvas.translate(cell.cx, cell.cy)
                canvas.rotate(cell.rot)
            }
            val fr = cell.frame
            if (fr != null) drawFrame(canvas, fr)
            canvas.save()
            canvas.clipPath(cell.path)
            val st = states?.getOrNull(i)
            val bmp = if (st != null && images.isNotEmpty()) images[st.imageIndex.mod(images.size)] else null
            if (st != null && bmp != null && !bmp.isRecycled) {
                imageRect(cell, bmp.width, bmp.height, st, dst)
                canvas.drawBitmap(bmp, null, dst, bmpPaint)
            } else {
                fill.color = placeholder[i % placeholder.size]
                canvas.drawPath(cell.path, fill)
            }
            canvas.restore()
            canvas.restore()
        }
        if (mask != null) canvas.restore()
        outline(canvas, layout, selected, selectedColor, strokeW)
        outline(canvas, layout, target, targetColor, strokeW)
    }

    private fun drawFrame(canvas: Canvas, fr: RectF) {
        val o = fr.width() * 0.012f
        fill.color = 0x16000000
        tmp.set(fr.left + o, fr.top + o * 2, fr.right + o * 2, fr.bottom + o * 3)
        canvas.drawRect(tmp, fill)
        fill.color = 0x22000000
        tmp.set(fr.left, fr.top + o, fr.right + o, fr.bottom + o * 1.5f)
        canvas.drawRect(tmp, fill)
        fill.color = Color.WHITE
        canvas.drawRect(fr, fill)
    }

    private fun outline(canvas: Canvas, layout: PreparedLayout, idx: Int, color: Int, sw: Float) {
        if (idx < 0 || idx >= layout.cells.size) return
        val cell = layout.cells[idx]
        stroke.color = color
        stroke.strokeWidth = sw
        canvas.save()
        if (cell.transformed) {
            canvas.translate(cell.cx, cell.cy)
            canvas.rotate(cell.rot)
        }
        canvas.drawPath(cell.path, stroke)
        canvas.restore()
    }

    companion object {
        /** Where the photo is drawn inside a cell (centre-crop + user zoom / pan, clamped). */
        fun imageRect(cell: Cell, bw: Int, bh: Int, st: CellState, out: RectF) {
            val b = cell.bounds
            val cw = max(b.width(), 1f)
            val ch = max(b.height(), 1f)
            val base = max(cw / bw, ch / bh) * st.scale
            val dw = bw * base
            val dh = bh * base
            val maxX = max(0f, (dw - cw) / 2f)
            val maxY = max(0f, (dh - ch) / 2f)
            st.dx = st.dx.coerceIn(-maxX, maxX)
            st.dy = st.dy.coerceIn(-maxY, maxY)
            val left = b.centerX() - dw / 2f + st.dx
            val top = b.centerY() - dh / 2f + st.dy
            out.set(left, top, left + dw, top + dh)
        }
    }
}
