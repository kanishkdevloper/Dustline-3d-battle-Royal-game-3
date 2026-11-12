package com.dustline.game

import android.content.Context
import android.graphics.BitmapFactory
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LOOK ONLY: re-shapes the map PICTURE so the island looks like the map of India.
 * Game world, houses, hills, roads and gameplay are untouched. The picture is warped radially (island outline -> India outline),
 * and the same warp is used for the player arrow, zone circles, airdrops and the pin, so everything still points at the right place.
 * UV = position inside the map square, 0..1 (u = (x + half) / 2half, v = (z + half) / 2half).
 */
class IndiaWarp(ctx: Context, map: MapData) {
    private val NB = 360
    private val MS = 1024            // india_mask.png size
    private val LG = 256             // island land grid size
    private val mainI = BooleanArray(MS * MS)
    private val islI = BooleanArray(MS * MS)
    private val sdI = IntArray(MS * MS)
    private val landL = BooleanArray(LG * LG)
    private var cIu = 0.5f; private var cIv = 0.5f; private var cLu = 0.5f; private var cLv = 0.5f
    private val rI = FloatArray(NB); private val rL = FloatArray(NB)
    var ok = false; private set

    init {
        try {
            val bm = BitmapFactory.decodeStream(ctx.assets.open("ui/india_mask.png"))
            val px = IntArray(bm.width * bm.height); bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
            if (bm.width == MS && bm.height == MS) {
                var sx = 0.0; var sy = 0.0; var n = 0
                for (i in px.indices) {
                    val c = px[i]
                    mainI[i] = ((c shr 16) and 255) > 127; islI[i] = ((c shr 8) and 255) > 127; sdI[i] = (c and 255) - 128
                    if (mainI[i]) { sx += (i % MS) + 0.5; sy += (i / MS) + 0.5; n++ }
                }
                bm.recycle()
                cIu = (sx / n / MS).toFloat(); cIv = (sy / n / MS).toFloat()
                profile(rI, cIu, cIv, MS) { x, y -> mainI[y * MS + x] }
                // island land from the real terrain height
                val half = kotlin.math.abs(map.x0)
                var lx = 0.0; var ly = 0.0; var ln = 0
                for (j in 0 until LG) for (i in 0 until LG) {
                    val w = map.ground(-half + (i + 0.5f) / LG * 2f * half, -half + (j + 0.5f) / LG * 2f * half) > 0.05f
                    landL[j * LG + i] = w
                    if (w) { lx += i + 0.5; ly += j + 0.5; ln++ }
                }
                if (ln > 100) {
                    cLu = (lx / ln / LG).toFloat(); cLv = (ly / ln / LG).toFloat()
                    profile(rL, cLu, cLv, LG) { x, y -> landL[y * LG + x] }
                    ok = true
                }
            }
        } catch (e: Throwable) { ok = false }
    }

    /** outermost land radius (in UV) for each degree around the centre, smoothed */
    private inline fun profile(out: FloatArray, cu: Float, cv: Float, n: Int, land: (Int, Int) -> Boolean) {
        java.util.Arrays.fill(out, 0f)
        for (y in 0 until n) for (x in 0 until n) if (land(x, y)) {
            val du = (x + 0.5f) / n - cu; val dv = (y + 0.5f) / n - cv
            var a = Math.toDegrees(atan2(dv.toDouble(), du.toDouble())).toInt(); if (a < 0) a += 360; if (a >= NB) a -= NB
            val r = sqrt(du * du + dv * dv); if (r > out[a]) out[a] = r
        }
        val tmp = FloatArray(NB)
        for (pass in 0 until 2) {
            for (a in 0 until NB) { var m = 0f; for (d in -3..3) m = max(m, out[(a + d + NB) % NB]); tmp[a] = m }
            for (a in 0 until NB) { var s = 0f; for (d in -4..4) s += tmp[(a + d + NB) % NB]; out[a] = max(s / 9f, 0.02f) }
        }
    }

    private fun prof(arr: FloatArray, ang: Float): Float {
        var a = Math.toDegrees(ang.toDouble()).toFloat(); if (a < 0f) a += 360f
        val i0 = a.toInt() % NB; val f = a - a.toInt(); return arr[i0] * (1f - f) + arr[(i0 + 1) % NB] * f
    }

    /** world position -> picture UV (o[0], o[1]); o[2] = how much bigger the picture is drawn there than a plain picture */
    fun fwd(u: Float, v: Float, o: FloatArray) {
        if (!ok) { o[0] = u; o[1] = v; o[2] = 1f; return }
        val du = u - cLu; val dv = v - cLv; val r = sqrt(du * du + dv * dv)
        if (r < 1e-6f) { o[0] = cIu; o[1] = cIv; o[2] = 1f; return }
        val ang = atan2(dv, du); val k = prof(rI, ang) / prof(rL, ang)
        o[0] = cIu + cos(ang) * r * k; o[1] = cIv + sin(ang) * r * k; o[2] = k
    }

    /** picture UV -> world UV */
    fun inv(u: Float, v: Float, o: FloatArray) {
        if (!ok) { o[0] = u; o[1] = v; return }
        val du = u - cIu; val dv = v - cIv; val r = sqrt(du * du + dv * dv)
        if (r < 1e-6f) { o[0] = cLu; o[1] = cLv; return }
        val ang = atan2(dv, du); val k = prof(rL, ang) / prof(rI, ang)
        o[0] = cLu + cos(ang) * r * k; o[1] = cLv + sin(ang) * r * k
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (((a shr 16) and 255) * (1f - t) + ((b shr 16) and 255) * t).toInt()
        val g = (((a shr 8) and 255) * (1f - t) + ((b shr 8) and 255) * t).toInt()
        val bl = ((a and 255) * (1f - t) + (b and 255) * t).toInt()
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
    }

    /**
     * Returns the picture [src] (S x S, whole map square) re-drawn in the shape of India.
     * [sea] is a small square patch of plain sea (sw x sw) that is tiled for the water (null: flat colour [seaCol]).
     */
    fun apply(src: IntArray, S: Int, sea: IntArray?, sw: Int, seaCol: Int): IntArray {
        if (!ok) return src
        val out = IntArray(S * S)
        val SAND = 0xFFD6C496.toInt(); val CYAN = 0xFF40D0F2.toInt(); val GREEN = 0xFF5C8C3C.toInt()
        for (y in 0 until S) {
            val v = (y + 0.5f) / S; val mj = (v * MS).toInt().coerceIn(0, MS - 1)
            for (x in 0 until S) {
                val u = (x + 0.5f) / S; val mi = (u * MS).toInt().coerceIn(0, MS - 1)
                val mq = mj * MS + mi
                if (mainI[mq]) {
                    val du = u - cIu; val dv = v - cIv; val r = sqrt(du * du + dv * dv)
                    var c: Int
                    if (r < 1e-6f) c = src[(cLv * S).toInt().coerceIn(0, S - 1) * S + (cLu * S).toInt().coerceIn(0, S - 1)]
                    else {
                        val ang = atan2(dv, du); val k = prof(rL, ang) / prof(rI, ang)
                        var rr = r * k; val ca = cos(ang); val sa = sin(ang)
                        var tries = 0
                        while (tries < 50) {   // India land that falls into a bay of the island: walk inwards until real land
                            val su = cLu + ca * rr; val sv = cLv + sa * rr
                            val gi = (su * LG).toInt(); val gj = (sv * LG).toInt()
                            if (gi in 0 until LG && gj in 0 until LG && landL[gj * LG + gi]) break
                            rr *= 0.97f; tries++
                        }
                        val su = cLu + ca * rr; val sv = cLv + sa * rr
                        c = src[(sv * S).toInt().coerceIn(0, S - 1) * S + (su * S).toInt().coerceIn(0, S - 1)]
                    }
                    val din = -sdI[mq]   // distance (mask px) to the coast, inside the land
                    if (din in 0..3) c = mix(c, SAND, 1f - din / 4f)
                    out[y * S + x] = c
                } else if (islI[mq]) {
                    out[y * S + x] = GREEN
                } else {
                    var c = seaCol
                    if (sea != null) {
                        var tx = x % (2 * sw); if (tx >= sw) tx = 2 * sw - 1 - tx
                        var ty = y % (2 * sw); if (ty >= sw) ty = 2 * sw - 1 - ty
                        c = sea[ty * sw + tx]
                    }
                    val d = sdI[mq]
                    if (d in 1..24) { val g = 1f - d / 24f; c = mix(c, CYAN, g * g * 0.9f) }
                    out[y * S + x] = c
                }
            }
        }
        return out
    }
}
