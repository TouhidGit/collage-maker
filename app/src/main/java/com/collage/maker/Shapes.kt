package com.collage.maker

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

object Shapes {

    fun heart(r: RectF): Path {
        val w = r.width()
        val h = r.height()
        val x = r.left
        val y = r.top
        return Path().apply {
            moveTo(x + w * 0.5f, y + h * 0.25f)
            cubicTo(x + w * 0.5f, y + h * 0.02f, x + w * 0.02f, y + h * 0.0f, x + w * 0.02f, y + h * 0.32f)
            cubicTo(x + w * 0.02f, y + h * 0.6f, x + w * 0.38f, y + h * 0.76f, x + w * 0.5f, y + h * 0.98f)
            cubicTo(x + w * 0.62f, y + h * 0.76f, x + w * 0.98f, y + h * 0.6f, x + w * 0.98f, y + h * 0.32f)
            cubicTo(x + w * 0.98f, y + h * 0.0f, x + w * 0.5f, y + h * 0.02f, x + w * 0.5f, y + h * 0.25f)
            close()
        }
    }

    fun star(cx: Float, cy: Float, radius: Float, innerRatio: Float): Path {
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) radius else radius * innerRatio
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + r * cos(a).toFloat()
            val y = cy + r * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** Outline of the given text (each line centred). Used as a collage mask. */
    fun text(raw: String): Path {
        val txt = raw.trim().ifEmpty { "LOVE" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = 200f
        }
        val out = Path()
        val lines = txt.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        lines.forEachIndexed { i, line ->
            val lp = Path()
            val w = paint.measureText(line)
            paint.getTextPath(line, 0, line.length, -w / 2f, i * 205f, lp)
            out.addPath(lp)
        }
        return out
    }
}
