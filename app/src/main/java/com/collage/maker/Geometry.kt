package com.collage.maker

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

typealias Poly = List<PointF>

/**
 * Small polygon toolkit. All polygons are convex and, after [normalize], have a
 * positive signed area in screen coordinates (y pointing down = visually clockwise).
 */
object Geo {

    fun rect(l: Float, t: Float, r: Float, b: Float): Poly =
        listOf(PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b))

    fun rect(r: RectF): Poly = rect(r.left, r.top, r.right, r.bottom)

    fun area(p: Poly): Float {
        if (p.size < 3) return 0f
        var a = 0f
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i].x * q.y - q.x * p[i].y
        }
        return a / 2f
    }

    fun normalize(p: Poly): Poly = if (area(p) < 0f) p.reversed() else p

    fun centroid(p: Poly): PointF {
        var cx = 0f
        var cy = 0f
        var a = 0f
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            val c = p[i].x * q.y - q.x * p[i].y
            a += c
            cx += (p[i].x + q.x) * c
            cy += (p[i].y + q.y) * c
        }
        if (abs(a) < 1e-6f) {
            var sx = 0f
            var sy = 0f
            for (pt in p) {
                sx += pt.x
                sy += pt.y
            }
            val n = max(1, p.size)
            return PointF(sx / n, sy / n)
        }
        return PointF(cx / (3f * a), cy / (3f * a))
    }

    fun bounds(p: Poly): RectF {
        if (p.isEmpty()) return RectF()
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (pt in p) {
            l = min(l, pt.x)
            t = min(t, pt.y)
            r = max(r, pt.x)
            b = max(b, pt.y)
        }
        return RectF(l, t, r, b)
    }

    fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float): Float = ax * by - ay * bx

    /** Removes duplicate and collinear vertices. */
    fun clean(p: Poly): Poly {
        val out = ArrayList<PointF>()
        for (pt in p) {
            val last = out.lastOrNull()
            if (last == null || abs(last.x - pt.x) > 0.01f || abs(last.y - pt.y) > 0.01f) out.add(pt)
        }
        if (out.size > 1) {
            val f = out.first()
            val l = out.last()
            if (abs(f.x - l.x) <= 0.01f && abs(f.y - l.y) <= 0.01f) out.removeAt(out.size - 1)
        }
        var changed = true
        while (changed && out.size > 3) {
            changed = false
            for (i in out.indices) {
                val a = out[(i - 1 + out.size) % out.size]
                val b = out[i]
                val c = out[(i + 1) % out.size]
                val cr = cross(b.x - a.x, b.y - a.y, c.x - b.x, c.y - b.y)
                val len = dist(a, b) * dist(b, c)
                if (abs(cr) <= 1e-4f * len) {
                    out.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        return out
    }

    fun shrink(p: Poly, f: Float): Poly {
        val c = centroid(p)
        return p.map { PointF(c.x + (it.x - c.x) * f, c.y + (it.y - c.y) * f) }
    }

    /** Insets a convex, positively oriented polygon by [d]. */
    fun inset(p: Poly, d: Float): Poly {
        if (d <= 0f || p.size < 3) return p
        val lines = ArrayList<FloatArray>()
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            var ex = b.x - a.x
            var ey = b.y - a.y
            val len = sqrt(ex * ex + ey * ey)
            if (len < 1e-4f) continue
            ex /= len
            ey /= len
            // inward normal is (-ey, ex)
            lines.add(floatArrayOf(a.x - ey * d, a.y + ex * d, ex, ey))
        }
        var guard = 0
        while (lines.size >= 3 && guard++ < 64) {
            val n = lines.size
            val pts = ArrayList<PointF>(n)
            for (i in 0 until n) {
                val l1 = lines[(i - 1 + n) % n]
                val l2 = lines[i]
                val den = cross(l1[2], l1[3], l2[2], l2[3])
                if (abs(den) < 1e-6f) {
                    pts.add(PointF(l2[0], l2[1]))
                } else {
                    val t = cross(l2[0] - l1[0], l2[1] - l1[1], l2[2], l2[3]) / den
                    pts.add(PointF(l1[0] + l1[2] * t, l1[1] + l1[3] * t))
                }
            }
            var bad = -1
            for (i in 0 until n) {
                val a = pts[i]
                val b = pts[(i + 1) % n]
                val l = lines[i]
                if ((b.x - a.x) * l[2] + (b.y - a.y) * l[3] < -1e-3f) {
                    bad = i
                    break
                }
            }
            if (bad < 0) {
                return if (area(pts) > 1f) pts else shrink(p, 0.05f)
            }
            lines.removeAt(bad)
        }
        return shrink(p, 0.05f)
    }

    /**
     * Splits a convex polygon with the line through (px,py) with direction (dx,dy).
     * first = side where cross(d, v - p) >= 0, second = the other side.
     */
    fun split(p: Poly, px: Float, py: Float, dx: Float, dy: Float): Pair<Poly, Poly> {
        val a = ArrayList<PointF>()
        val b = ArrayList<PointF>()
        val n = p.size
        for (i in 0 until n) {
            val c = p[i]
            val nx = p[(i + 1) % n]
            val sc = cross(dx, dy, c.x - px, c.y - py)
            val sn = cross(dx, dy, nx.x - px, nx.y - py)
            if (sc >= 0f) a.add(PointF(c.x, c.y))
            if (sc <= 0f) b.add(PointF(c.x, c.y))
            if ((sc > 0f && sn < 0f) || (sc < 0f && sn > 0f)) {
                val t = sc / (sc - sn)
                val ix = c.x + (nx.x - c.x) * t
                val iy = c.y + (nx.y - c.y) * t
                a.add(PointF(ix, iy))
                b.add(PointF(ix, iy))
            }
        }
        return Pair(clean(a), clean(b))
    }

    fun contains(p: Poly, x: Float, y: Float): Boolean {
        if (p.size < 3) return false
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            if (cross(b.x - a.x, b.y - a.y, x - a.x, y - a.y) < 0f) return false
        }
        return true
    }

    /** Builds a path for the polygon with rounded corners. */
    fun roundedPath(p: Poly, radius: Float): Path {
        val path = Path()
        val n = p.size
        if (n < 3) return path
        if (radius < 0.5f) {
            path.moveTo(p[0].x, p[0].y)
            for (i in 1 until n) path.lineTo(p[i].x, p[i].y)
            path.close()
            return path
        }
        for (i in 0 until n) {
            val prev = p[(i - 1 + n) % n]
            val cur = p[i]
            val next = p[(i + 1) % n]
            val d1 = max(dist(prev, cur), 1e-4f)
            val d2 = max(dist(cur, next), 1e-4f)
            val r1 = min(radius, d1 / 2f)
            val r2 = min(radius, d2 / 2f)
            val ax = cur.x + (prev.x - cur.x) / d1 * r1
            val ay = cur.y + (prev.y - cur.y) / d1 * r1
            val bx = cur.x + (next.x - cur.x) / d2 * r2
            val by = cur.y + (next.y - cur.y) / d2 * r2
            if (i == 0) path.moveTo(ax, ay) else path.lineTo(ax, ay)
            path.quadTo(cur.x, cur.y, bx, by)
        }
        path.close()
        return path
    }
}
