package com.collage.maker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Interactive collage editor.
 *  - drag a photo to move it inside its cell, pinch to zoom, double-tap to reset
 *  - tap to select a cell
 *  - long-press and drag onto another cell to swap the two photos
 */
class CollageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var collage: PreparedLayout? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var images: List<Bitmap> = emptyList()
    var states: MutableList<CellState> = mutableListOf()
    var bgColor: Int = Color.WHITE
    var selected: Int = -1
    var onSelectionChanged: ((Int) -> Unit)? = null

    private val renderer = Renderer()
    private val accent = ContextCompat.getColor(context, R.color.brand)
    private val targetColor = 0xFFFFB300.toInt()
    private val density = resources.displayMetrics.density

    private var viewScale = 1f
    private var active = -1
    private var lastX = 0f
    private var lastY = 0f
    private var swapping = false
    private var swapFrom = -1
    private var swapTarget = -1
    private var ghostX = 0f
    private var ghostY = 0f

    private val ghostPath = Path()
    private val ghostRect = RectF()
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (swapping) return false
                val st = states.getOrNull(active) ?: return false
                st.scale = (st.scale * detector.scaleFactor).coerceIn(1f, 6f)
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val hit = hitTest(e.x, e.y)
            selected = if (hit == selected) -1 else hit
            onSelectionChanged?.invoke(selected)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val st = states.getOrNull(hitTest(e.x, e.y)) ?: return false
            st.scale = 1f
            st.dx = 0f
            st.dy = 0f
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaleDetector.isInProgress) return
            val hit = hitTest(e.x, e.y)
            if (hit >= 0 && (collage?.cells?.size ?: 0) > 1) {
                swapping = true
                swapFrom = hit
                swapTarget = -1
                ghostX = e.x
                ghostY = e.y
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val l = collage
        if (l == null || w == 0 || h == 0) {
            setMeasuredDimension(w, h)
            return
        }
        val ar = l.w / l.h
        var mw = w
        var mh = (w / ar).toInt()
        if (mh > h) {
            mh = h
            mw = (h * ar).toInt()
        }
        setMeasuredDimension(mw, mh)
    }

    override fun onDraw(canvas: Canvas) {
        val l = collage ?: return
        viewScale = width / l.w
        canvas.save()
        canvas.scale(viewScale, viewScale)
        canvas.clipRect(0f, 0f, l.w, l.h)
        renderer.draw(
            canvas, l, images, states, bgColor,
            if (swapping) swapFrom else selected,
            if (swapping) swapTarget else -1,
            3f * density / viewScale, accent, targetColor
        )
        canvas.restore()
        if (swapping) drawGhost(canvas)
    }

    private fun drawGhost(canvas: Canvas) {
        val st = states.getOrNull(swapFrom) ?: return
        if (images.isEmpty()) return
        val bmp = images[st.imageIndex.mod(images.size)]
        val r = 38f * density
        val cy = ghostY - r * 1.4f
        ghostPath.reset()
        ghostPath.addCircle(ghostX, cy, r, Path.Direction.CW)
        val s = max(2 * r / bmp.width, 2 * r / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        ghostRect.set(ghostX - w / 2, cy - h / 2, ghostX + w / 2, cy + h / 2)
        canvas.save()
        canvas.clipPath(ghostPath)
        canvas.drawBitmap(bmp, null, ghostRect, ghostPaint)
        canvas.restore()
        ghostStroke.strokeWidth = 3f * density
        canvas.drawCircle(ghostX, cy, r, ghostStroke)
    }

    /** Returns the index of the top-most cell under the given view coordinates, or -1. */
    fun hitTest(x: Float, y: Float): Int {
        val l = collage ?: return -1
        if (viewScale <= 0f) return -1
        val dx = x / viewScale
        val dy = y / viewScale
        for (i in l.cells.indices.reversed()) {
            val c = l.cells[i]
            var lx = dx
            var ly = dy
            if (c.transformed) {
                val px = dx - c.cx
                val py = dy - c.cy
                val r = Math.toRadians(-c.rot.toDouble())
                val cs = cos(r).toFloat()
                val sn = sin(r).toFloat()
                lx = px * cs - py * sn
                ly = px * sn + py * cs
            }
            if (Geo.contains(c.poly, lx, ly)) return i
            val fr = c.frame
            if (fr != null && fr.contains(lx, ly)) return i
        }
        return -1
    }

    private fun swap(a: Int, b: Int) {
        if (a !in states.indices || b !in states.indices) return
        val sa = states[a]
        val sb = states[b]
        states[a] = CellState(sb.imageIndex)
        states[b] = CellState(sa.imageIndex)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (collage == null) return false
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = hitTest(ev.x, ev.y)
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (swapping) {
                    ghostX = ev.x
                    ghostY = ev.y
                    val t = hitTest(ev.x, ev.y)
                    swapTarget = if (t == swapFrom) -1 else t
                    invalidate()
                } else if (ev.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val st = states.getOrNull(active)
                    val cell = collage?.cells?.getOrNull(active)
                    if (st != null && cell != null) {
                        var mx = (ev.x - lastX) / viewScale
                        var my = (ev.y - lastY) / viewScale
                        if (cell.rot != 0f) {
                            val r = Math.toRadians(-cell.rot.toDouble())
                            val cs = cos(r).toFloat()
                            val sn = sin(r).toFloat()
                            val nx = mx * cs - my * sn
                            val ny = mx * sn + my * cs
                            mx = nx
                            my = ny
                        }
                        st.dx += mx
                        st.dy += my
                        invalidate()
                    }
                    lastX = ev.x
                    lastY = ev.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (ev.actionIndex == 0) 1 else 0
                if (remaining < ev.pointerCount) {
                    lastX = ev.getX(remaining)
                    lastY = ev.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (swapping) {
                    val t = hitTest(ev.x, ev.y)
                    if (t >= 0 && t != swapFrom) swap(swapFrom, t)
                    swapping = false
                    swapTarget = -1
                    invalidate()
                }
                active = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                swapping = false
                swapTarget = -1
                active = -1
                invalidate()
            }
        }
        return true
    }
}
