package com.collage.maker

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Region
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class CellShape { POLY, OVAL, HEART }

/** A cell produced by a layout generator, in design coordinates. */
class RawCell(
    val poly: Poly,
    val shape: CellShape = CellShape.POLY,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val rot: Float = 0f,
    val frame: RectF? = null,
    val inset: Boolean = true
)

class RawLayout(val cells: List<RawCell>, val mask: Path? = null)

class GenParams(
    val n: Int,
    val w: Float,
    val h: Float,
    val content: RectF,
    val seed: Long,
    val text: String
)

class LayoutDef(
    val id: String,
    val name: String,
    val category: String,
    val gen: (GenParams) -> RawLayout
)

/** Every collage style. Each generator works for any number of photos. */
object Layouts {

    const val CAT_ALL = "All"
    val categories = listOf(CAT_ALL, "Grid", "Mosaic", "Angled", "Pattern", "Scatter", "Shape")

    enum class Lattice { DIAMOND, DIAMOND_HERO, HEX, CIRCLE, TRIANGLE, HEART }

    val all: List<LayoutDef> = listOf(
        LayoutDef("grid", "Grid", "Grid") { p -> rects(gridRects(p.content, p.n, true)) },
        LayoutDef("columns", "Columns", "Grid") { p -> rects(gridRects(p.content, p.n, false)) },
        LayoutDef("strips", "Strips", "Grid") { p -> rects(strips(p.content, p.n)) },
        LayoutDef("hero", "Center hero", "Mosaic") { p -> rects(centerHero(p.content, p.n)) },
        LayoutDef("feature_l", "Big left", "Mosaic") { p -> rects(feature(p.content, p.n, true)) },
        LayoutDef("feature_t", "Big top", "Mosaic") { p -> rects(feature(p.content, p.n, false)) },
        LayoutDef("mosaic", "Mosaic", "Mosaic") { p -> rects(mosaic(p, false)) },
        LayoutDef("mixed", "Mixed sizes", "Mosaic") { p -> rects(mosaic(p, true)) },
        LayoutDef("shatter", "Shattered", "Angled") { p -> polys(shatter(p)) },
        LayoutDef("diagonal", "Diagonal", "Angled") { p -> polys(diagonal(p)) },
        LayoutDef("pinwheel", "Pinwheel", "Angled") { p -> polys(pinwheel(p)) },
        LayoutDef("diamonds", "Diamonds", "Pattern") { p -> lattice(p, Lattice.DIAMOND) },
        LayoutDef("diamond_hero", "Diamond hero", "Pattern") { p -> lattice(p, Lattice.DIAMOND_HERO) },
        LayoutDef("honeycomb", "Honeycomb", "Pattern") { p -> lattice(p, Lattice.HEX) },
        LayoutDef("circles", "Circles", "Pattern") { p -> lattice(p, Lattice.CIRCLE) },
        LayoutDef("triangles", "Triangles", "Pattern") { p -> lattice(p, Lattice.TRIANGLE) },
        LayoutDef("hearts", "Hearts", "Pattern") { p -> lattice(p, Lattice.HEART) },
        LayoutDef("polaroid", "Polaroids", "Scatter") { p -> scatter(p, true) },
        LayoutDef("scatter", "Scattered", "Scatter") { p -> scatter(p, false) },
        LayoutDef("shape_heart", "Heart", "Shape") { p -> maskLayout(p, Shapes.heart(RectF(0f, 0f, 100f, 90f))) },
        LayoutDef("shape_circle", "Circle", "Shape") { p ->
            maskLayout(p, Path().apply { addCircle(50f, 50f, 50f, Path.Direction.CW) })
        },
        LayoutDef("shape_star", "Star", "Shape") { p -> maskLayout(p, Shapes.star(50f, 50f, 50f, 0.5f)) },
        LayoutDef("shape_text", "Text / Number", "Shape") { p -> maskLayout(p, Shapes.text(p.text)) }
    )

    private fun rects(list: List<RectF>) = RawLayout(list.map { RawCell(Geo.rect(it)) })
    private fun polys(list: List<Poly>) = RawLayout(list.map { RawCell(it) })

    // ---------------------------------------------------------------- grids

    fun gridRects(r: RectF, k: Int, byRows: Boolean): List<RectF> {
        val out = ArrayList<RectF>()
        if (k <= 0) return out
        val w = max(r.width(), 1e-3f)
        val h = max(r.height(), 1e-3f)
        if (byRows) {
            val rows = sqrt(k * h / w).roundToInt().coerceIn(1, k)
            val base = k / rows
            val extra = k % rows
            val rh = h / rows
            for (i in 0 until rows) {
                val cnt = base + if (i >= rows - extra) 1 else 0
                val cw = w / cnt
                for (j in 0 until cnt) {
                    out.add(RectF(r.left + j * cw, r.top + i * rh, r.left + (j + 1) * cw, r.top + (i + 1) * rh))
                }
            }
        } else {
            val cols = sqrt(k * w / h).roundToInt().coerceIn(1, k)
            val base = k / cols
            val extra = k % cols
            val cw = w / cols
            for (i in 0 until cols) {
                val cnt = base + if (i >= cols - extra) 1 else 0
                val ch = h / cnt
                for (j in 0 until cnt) {
                    out.add(RectF(r.left + i * cw, r.top + j * ch, r.left + (i + 1) * cw, r.top + (j + 1) * ch))
                }
            }
        }
        return out
    }

    fun strips(r: RectF, k: Int): List<RectF> =
        if (r.width() >= r.height()) {
            (0 until k).map { RectF(r.left + r.width() * it / k, r.top, r.left + r.width() * (it + 1) / k, r.bottom) }
        } else {
            (0 until k).map { RectF(r.left, r.top + r.height() * it / k, r.right, r.top + r.height() * (it + 1) / k) }
        }

    // --------------------------------------------------------------- mosaic

    fun feature(c: RectF, n: Int, left: Boolean): List<RectF> {
        if (n <= 1) return listOf(RectF(c))
        val f = when {
            n == 2 -> 0.5f
            n <= 4 -> 0.6f
            n <= 9 -> 0.55f
            else -> 0.5f
        }
        val out = ArrayList<RectF>()
        if (left) {
            val x = c.left + c.width() * f
            out.add(RectF(c.left, c.top, x, c.bottom))
            out.addAll(gridRects(RectF(x, c.top, c.right, c.bottom), n - 1, true))
        } else {
            val y = c.top + c.height() * f
            out.add(RectF(c.left, c.top, c.right, y))
            out.addAll(gridRects(RectF(c.left, y, c.right, c.bottom), n - 1, true))
        }
        return out
    }

    fun centerHero(c: RectF, n: Int): List<RectF> {
        if (n < 5) return feature(c, n, c.width() >= c.height())
        val f = when {
            n <= 9 -> 0.5f
            n <= 20 -> 0.56f
            n <= 40 -> 0.6f
            else -> 0.64f
        }
        val hw = c.width() * f
        val hh = c.height() * f
        val hero = RectF(c.centerX() - hw / 2, c.centerY() - hh / 2, c.centerX() + hw / 2, c.centerY() + hh / 2)
        val regions = listOf(
            RectF(c.left, c.top, c.right, hero.top),
            RectF(hero.right, hero.top, c.right, hero.bottom),
            RectF(c.left, hero.bottom, c.right, c.bottom),
            RectF(c.left, hero.top, hero.left, hero.bottom)
        )
        val counts = distribute(n - 1, regions.map { it.width() * it.height() })
        val out = arrayListOf(hero)
        regions.forEachIndexed { i, r -> out.addAll(gridRects(r, counts[i], true)) }
        return out
    }

    private fun distribute(total: Int, weights: List<Float>): IntArray {
        val k = weights.size
        val res = IntArray(k) { 1 }
        val left = total - k
        if (left <= 0) return res
        val sum = weights.sum()
        val exact = weights.map { it / sum * left }
        for (i in 0 until k) res[i] += floor(exact[i]).toInt()
        var rem = total - res.sum()
        val order = (0 until k).sortedByDescending { exact[it] - floor(exact[it]) }
        var idx = 0
        while (rem > 0) {
            res[order[idx % k]]++
            rem--
            idx++
        }
        return res
    }

    fun mosaic(p: GenParams, mixed: Boolean): List<RectF> {
        val rnd = Random(p.seed)
        val weights = List(p.n) { if (mixed) 0.4f + rnd.nextFloat() * 1.8f else 1f }
        val out = ArrayList<RectF>()
        guillotine(p.content, weights, rnd, out)
        return out
    }

    private fun guillotine(r: RectF, w: List<Float>, rnd: Random, out: MutableList<RectF>) {
        val k = w.size
        if (k <= 1) {
            out.add(r)
            return
        }
        val lo = max(1, (k * 0.3f).toInt())
        val hi = min(k - 1, ceil(k * 0.7f).toInt())
        val m = if (hi <= lo) lo else rnd.nextInt(lo, hi + 1)
        val total = w.sum()
        val frac = (w.subList(0, m).sum() / total).coerceIn(0.15f, 0.85f)
        val vertical = when {
            r.width() > r.height() * 1.15f -> true
            r.height() > r.width() * 1.15f -> false
            else -> rnd.nextBoolean()
        }
        if (vertical) {
            val x = r.left + r.width() * frac
            guillotine(RectF(r.left, r.top, x, r.bottom), w.subList(0, m), rnd, out)
            guillotine(RectF(x, r.top, r.right, r.bottom), w.subList(m, k), rnd, out)
        } else {
            val y = r.top + r.height() * frac
            guillotine(RectF(r.left, r.top, r.right, y), w.subList(0, m), rnd, out)
            guillotine(RectF(r.left, y, r.right, r.bottom), w.subList(m, k), rnd, out)
        }
    }

    // --------------------------------------------------------------- angled

    fun shatter(p: GenParams): List<Poly> {
        val rnd = Random(p.seed)
        val list = ArrayList<Poly>()
        list.add(Geo.rect(p.content))
        var guard = 0
        while (list.size < p.n && guard++ < p.n * 20 + 20) {
            var idx = 0
            var best = -1f
            list.forEachIndexed { i, poly ->
                val a = Geo.area(poly)
                if (a > best) {
                    best = a
                    idx = i
                }
            }
            val poly = list[idx]
            val b = Geo.bounds(poly)
            val c = Geo.centroid(poly)
            val base = if (b.width() >= b.height()) 90.0 else 0.0
            val jitter = if (guard > p.n * 10) 0.0 else rnd.nextDouble() * 70.0 - 35.0
            val ang = Math.toRadians(base + jitter)
            val dx = cos(ang).toFloat()
            val dy = sin(ang).toFloat()
            val off = (rnd.nextFloat() - 0.5f) * 0.3f * min(b.width(), b.height())
            val px = c.x - dy * off
            val py = c.y + dx * off
            val parts = Geo.split(poly, px, py, dx, dy)
            val a1 = parts.first
            val a2 = parts.second
            if (a1.size >= 3 && a2.size >= 3 && Geo.area(a1) > 4f && Geo.area(a2) > 4f) {
                list[idx] = Geo.normalize(a1)
                list.add(Geo.normalize(a2))
            }
        }
        return list
    }

    fun diagonal(p: GenParams): List<Poly> {
        val c = p.content
        val n = p.n
        val full = Geo.rect(c)
        if (n <= 1) return listOf(full)
        val tilt = Math.toRadians(16.0)
        val dx: Float
        val dy: Float
        if (c.width() >= c.height()) {
            dx = sin(tilt).toFloat()
            dy = cos(tilt).toFloat()
        } else {
            dx = cos(tilt).toFloat()
            dy = -sin(tilt).toFloat()
        }
        val nx = dy
        val ny = -dx
        val projs = full.map { it.x * nx + it.y * ny }
        val lo = projs.min()
        val hi = projs.max()
        val total = Geo.area(full)
        val out = ArrayList<Poly>()
        var rest: Poly = full
        for (i in 1 until n) {
            val target = total * i / n
            var a = lo
            var b = hi
            repeat(40) {
                val mid = (a + b) / 2f
                val part = Geo.split(full, nx * mid, ny * mid, dx, dy).first
                if (Geo.area(part) < target) a = mid else b = mid
            }
            val v = (a + b) / 2f
            val parts = Geo.split(rest, nx * v, ny * v, dx, dy)
            if (parts.first.size >= 3) out.add(Geo.normalize(parts.first))
            rest = parts.second
        }
        if (rest.size >= 3) out.add(Geo.normalize(rest))
        return out
    }

    fun pinwheel(p: GenParams): List<Poly> {
        val c = p.content
        val n = p.n
        if (n <= 1) return listOf(Geo.rect(c))
        val rnd = Random(p.seed)
        val cx = c.centerX() + (rnd.nextFloat() - 0.5f) * 0.24f * c.width()
        val cy = c.centerY() + (rnd.nextFloat() - 0.5f) * 0.24f * c.height()
        val step = 2.0 * PI / n
        val start = rnd.nextDouble() * 2.0 * PI
        val jit = if (n == 2) 0.0 else 0.2
        val angles = DoubleArray(n + 1)
        for (i in 0 until n) angles[i] = start + i * step + (rnd.nextDouble() - 0.5) * 2.0 * jit * step
        angles[n] = angles[0] + 2.0 * PI
        val corners = Geo.rect(c)
        val out = ArrayList<Poly>()
        for (i in 0 until n) {
            val a0 = angles[i]
            val a1 = angles[i + 1]
            val pts = ArrayList<PointF>()
            pts.add(PointF(cx, cy))
            pts.add(rayHit(c, cx, cy, a0))
            val between = corners
                .map { pt -> Pair(pt, normAngle(atan2((pt.y - cy).toDouble(), (pt.x - cx).toDouble()) - a0)) }
                .filter { it.second > 1e-6 && it.second < a1 - a0 - 1e-6 }
                .sortedBy { it.second }
            for (b in between) pts.add(PointF(b.first.x, b.first.y))
            pts.add(rayHit(c, cx, cy, a1))
            out.add(Geo.normalize(Geo.clean(pts)))
        }
        return out
    }

    private fun normAngle(a: Double): Double {
        var x = a % (2.0 * PI)
        if (x < 0) x += 2.0 * PI
        return x
    }

    private fun rayHit(r: RectF, cx: Float, cy: Float, a: Double): PointF {
        val dx = cos(a)
        val dy = sin(a)
        var t = Double.MAX_VALUE
        if (dx > 1e-9) t = min(t, (r.right - cx) / dx)
        if (dx < -1e-9) t = min(t, (r.left - cx) / dx)
        if (dy > 1e-9) t = min(t, (r.bottom - cy) / dy)
        if (dy < -1e-9) t = min(t, (r.top - cy) / dy)
        return PointF((cx + dx * t).toFloat(), (cy + dy * t).toFloat())
    }

    // -------------------------------------------------------------- lattice

    private class Cand(val poly: Poly, val shape: CellShape, val cx: Float, val cy: Float)

    private fun diamond(cx: Float, cy: Float, h: Float) = Cand(
        listOf(PointF(cx, cy - h), PointF(cx + h, cy), PointF(cx, cy + h), PointF(cx - h, cy)),
        CellShape.POLY, cx, cy
    )

    /**
     * Builds an endless tiling, keeps the [GenParams.n] cells closest to the centre
     * (stretched to the canvas aspect) and scales that cluster to fill the canvas.
     */
    fun lattice(p: GenParams, type: Lattice): RawLayout {
        val n = p.n
        val ar = max(p.content.width(), 1f) / max(p.content.height(), 1f)
        val range = ceil(sqrt(n * 2.0) * max(ar, 1f / ar)).toInt() + 4
        val cands = ArrayList<Cand>()
        var hero: Cand? = null
        val ox = if (type == Lattice.DIAMOND_HERO) 1f else 0f
        val oy = 0f
        when (type) {
            Lattice.DIAMOND, Lattice.DIAMOND_HERO -> {
                val heroSet = setOf(Pair(0, 0), Pair(2, 0), Pair(1, 1), Pair(1, -1))
                for (j in -range..range) for (i in -range..range) {
                    if ((i + j) % 2 != 0) continue
                    if (type == Lattice.DIAMOND_HERO && Pair(i, j) in heroSet) continue
                    cands.add(diamond(i.toFloat(), j.toFloat(), 1f))
                }
                if (type == Lattice.DIAMOND_HERO) hero = diamond(1f, 0f, 2f)
            }
            Lattice.HEX, Lattice.CIRCLE -> {
                val s3 = sqrt(3f)
                for (j in -range..range) for (i in -range..range) {
                    val cx = s3 * (i + 0.5f * (j and 1))
                    val cy = 1.5f * j
                    if (type == Lattice.HEX) {
                        val pts = (0 until 6).map { k ->
                            val a = Math.toRadians(-90.0 + 60.0 * k)
                            PointF(cx + cos(a).toFloat(), cy + sin(a).toFloat())
                        }
                        cands.add(Cand(pts, CellShape.POLY, cx, cy))
                    } else {
                        val r = s3 / 2f
                        cands.add(Cand(Geo.rect(cx - r, cy - r, cx + r, cy + r), CellShape.OVAL, cx, cy))
                    }
                }
            }
            Lattice.TRIANGLE -> {
                val th = sqrt(3f) / 2f
                for (j in -range..range) for (k in -2 * range..2 * range) {
                    val y0 = j * th
                    val x0 = k * 0.5f
                    val poly = if (((k + j) and 1) == 0) {
                        listOf(PointF(x0, y0 + th), PointF(x0 + 0.5f, y0), PointF(x0 + 1f, y0 + th))
                    } else {
                        listOf(PointF(x0, y0), PointF(x0 + 1f, y0), PointF(x0 + 0.5f, y0 + th))
                    }
                    val c = Geo.centroid(poly)
                    cands.add(Cand(poly, CellShape.POLY, c.x, c.y))
                }
            }
            Lattice.HEART -> {
                for (j in -range..range) for (i in -range..range) {
                    val cx = i + 0.5f * (j and 1)
                    val cy = j * 0.92f
                    cands.add(Cand(Geo.rect(cx - 0.5f, cy - 0.5f, cx + 0.5f, cy + 0.5f), CellShape.HEART, cx, cy))
                }
            }
        }

        val ard = ar.toDouble()
        val sorted = cands.sortedWith(
            compareBy<Cand>(
                { c -> Math.round((((c.cx - ox) / ard).pow(2) + (c.cy - oy).toDouble().pow(2)) * 1000.0) },
                { c -> atan2((c.cy - oy).toDouble(), (c.cx - ox).toDouble()) }
            )
        )
        val chosen = ArrayList<Cand>()
        hero?.let { chosen.add(it) }
        chosen.addAll(sorted.take(max(0, n - chosen.size)))

        val b = Geo.bounds(chosen.flatMap { it.poly })
        val c = p.content
        val s = min(c.width() / max(b.width(), 1e-3f), c.height() / max(b.height(), 1e-3f))
        val tx = c.centerX() - b.centerX() * s
        val ty = c.centerY() - b.centerY() * s
        return RawLayout(chosen.map { cand ->
            RawCell(cand.poly.map { PointF(it.x * s + tx, it.y * s + ty) }, cand.shape)
        })
    }

    // -------------------------------------------------------------- scatter

    fun scatter(p: GenParams, polaroid: Boolean): RawLayout {
        val rnd = Random(p.seed)
        val c = p.content
        val n = p.n
        val rows0 = sqrt(n * c.height() / c.width()).roundToInt().coerceIn(1, n)
        val cols = ceil(n / rows0.toFloat()).toInt()
        val rows = ceil(n / cols.toFloat()).toInt()
        val cw = c.width() / cols
        val ch = c.height() / rows
        val base = if (n == 1) min(c.width(), c.height()) * 0.78f else min(cw, ch) * 1.3f
        val cells = ArrayList<RawCell>()
        for (k in 0 until n) {
            val row = k / cols
            val inRow = if (row == rows - 1) n - cols * (rows - 1) else cols
            val col = k % cols
            val rowW = c.width() / inRow
            var x = c.left + (col + 0.5f) * rowW
            var y = c.top + (row + 0.5f) * ch
            if (n > 1) {
                x += (rnd.nextFloat() - 0.5f) * 0.3f * cw
                y += (rnd.nextFloat() - 0.5f) * 0.3f * ch
            }
            val rot = if (n == 1) -4f + rnd.nextFloat() * 8f else -15f + rnd.nextFloat() * 30f
            if (polaroid) {
                val s = base * 0.8f
                val bd = s * 0.06f
                val bottom = s * 0.25f
                val fw = s + 2 * bd
                val fh = s + bd + bottom
                val frame = RectF(-fw / 2, -fh / 2, fw / 2, fh / 2)
                val top = -fh / 2 + bd
                cells.add(RawCell(Geo.rect(-s / 2, top, s / 2, top + s), CellShape.POLY, x, y, rot, frame, false))
            } else {
                val landscape = rnd.nextBoolean()
                val pw = if (landscape) base * 0.95f else base * 0.72f
                val ph = if (landscape) base * 0.72f else base * 0.95f
                val bd = base * 0.025f
                val frame = RectF(-pw / 2 - bd, -ph / 2 - bd, pw / 2 + bd, ph / 2 + bd)
                cells.add(RawCell(Geo.rect(-pw / 2, -ph / 2, pw / 2, ph / 2), CellShape.POLY, x, y, rot, frame, false))
            }
        }
        cells.shuffle(rnd)
        return RawLayout(cells)
    }

    // ---------------------------------------------------------- shape masks

    /** Fills a shape (heart, star, text...) with a grid of photos. */
    fun maskLayout(p: GenParams, shape: Path): RawLayout {
        val c = p.content
        val sb = RectF()
        shape.computeBounds(sb, true)
        if (sb.width() <= 0f || sb.height() <= 0f) return rects(gridRects(c, p.n, true))
        val s = min(c.width() / sb.width(), c.height() / sb.height())
        val m = Matrix()
        m.postTranslate(-sb.centerX(), -sb.centerY())
        m.postScale(s, s)
        m.postTranslate(c.centerX(), c.centerY())
        val mask = Path(shape)
        mask.transform(m)
        val mb = RectF()
        mask.computeBounds(mb, true)
        val region = Region()
        region.setPath(
            mask,
            Region(
                floor(mb.left).toInt() - 2, floor(mb.top).toInt() - 2,
                ceil(mb.right).toInt() + 2, ceil(mb.bottom).toInt() + 2
            )
        )
        var size = sqrt(mb.width() * mb.height() / p.n)
        var cells: List<RectF> = emptyList()
        for (iter in 0 until 80) {
            cells = cellsInMask(region, mb, size)
            if (cells.size >= p.n) break
            size *= 0.93f
        }
        if (cells.isEmpty()) return rects(gridRects(c, p.n, true))
        return RawLayout(cells.map { RawCell(Geo.rect(it)) }, mask)
    }

    private fun cellsInMask(region: Region, mb: RectF, size: Float): List<RectF> {
        val cols = max(1, ceil(mb.width() / size).toInt())
        val rows = max(1, ceil(mb.height() / size).toInt())
        val sx = mb.centerX() - cols * size / 2
        val sy = mb.centerY() - rows * size / 2
        val out = ArrayList<RectF>()
        for (r in 0 until rows) for (q in 0 until cols) {
            val l = sx + q * size
            val t = sy + r * size
            var inside = 0
            for (a in 0 until 5) for (b in 0 until 5) {
                val px = (l + (a + 0.5f) * size / 5f).toInt()
                val py = (t + (b + 0.5f) * size / 5f).toInt()
                if (region.contains(px, py)) inside++
            }
            if (inside >= 7) out.add(RectF(l, t, l + size, t + size))
        }
        return out
    }
}
