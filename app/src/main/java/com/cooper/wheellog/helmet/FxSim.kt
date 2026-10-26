package com.cooper.wheellog.helmet

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Эффекты ленты для картинки в приложении, когда кадра с платы нет. Та же математика, что fxColor() в прошивке
 * (fw/src/main.cpp): d - путь ото лба (0) к затылку (1) по своей стороне, phase набегает со скоростью колеса.
 */
class FxSim {
    private var phase = 0f
    private var last = 0L

    /** Вызвать раз на кадр: продвинуть фазу по скорости v, км/ч. */
    fun tick(now: Long, v: Float, speedPct: Int) {
        val dt = if (last == 0L || now - last > 200) 0.02f else (now - last) / 1000f
        last = now
        phase += dt * (0.12f + v / 25f) * speedPct / 100f
        if (phase > 1000f) phase -= 1000f
    }

    /** Цвет точки: r, g, b 0..1. i - номер точки (для искр и огня), t - секунды. */
    fun color(c: Cfg, i: Int, d: Float, t: Float, v: Float, acc: Float, battery: Int): FloatArray {
        if (c.fxFlags and Fx.F_FRONT_WHITE != 0 && d < 0.1f) return floatArrayOf(0.85f, 0.85f, 0.85f)
        if (c.fxFlags and Fx.F_BACK_RED != 0 && d > 0.86f) return floatArrayOf(0.5f, 0f, 0f)
        val c1 = c.fxRgb1.map { it / 255f }.toFloatArray()
        val c2 = c.fxRgb2.map { it / 255f }.toFloatArray()
        val ph = phase
        val k = c.fxBright / 255f
        return mul(when (c.fx) {
            Fx.BREATH -> if (v < 3) mul(c1, 0.25f + 0.75f * (0.5f - 0.5f * cos(t * 2 * PI.toFloat() / 4))) else comets(c1, c2, d, ph, false)
            Fx.COMET -> comets(c1, c2, d, ph, false)
            Fx.BATTERY -> if (v < 3) {
                val lvl = battery / 100f
                if (d < lvl) mix(floatArrayOf(0f, 1f, 0.1f), floatArrayOf(1f, 0.6f, 0f), d / max(lvl, 0.01f) * 0.3f) else floatArrayOf(0.04f, 0f, 0f)
            } else comets(c1, c2, d, ph, true)
            Fx.FLOW -> mix(c1, c2, 0.5f + 0.5f * sin(2 * PI.toFloat() * (d * 2 - ph * 2)))
            Fx.GAUGE -> {
                val f = v / 60f
                if (d > f) mul(c1, 0.05f) else mix(c1, c2, d).let { if (abs(d - f) < 0.04f) mul(it, 1.6f) else it }
            }
            Fx.SPARKLE -> {
                val rate = 1 + v / 4; val x = t * rate + hash(i) * 7
                val age = x - floor(x)
                if (hash(i * 7919 + x.toInt()) < 0.12f + v / 250) mul(mix(c2, c1, age), 1 - age * 0.7f) else mul(c1, 0.12f)
            }
            Fx.RAINBOW -> hsv(d * 1.2f - ph)
            Fx.SCANNER -> {
                val p = 0.5f - 0.5f * cos(ph * PI.toFloat() * 1.2f); val x = abs(d - p)
                mul(mix(c2, c1, min(1f, x * 6)), max(0.04f, exp(-x * 14)))
            }
            Fx.ROAD -> if (frac(d * 5 - ph * 2.5f) < 0.45f) c1 else mul(c1, 0.06f)
            Fx.FIRE -> {
                val fl = hash(i * 31 + (t * 14).toInt()) * 0.5f + hash(i * 57 + (t * 9).toInt() + 99991) * 0.5f
                val reach = 0.25f + min(0.75f, v / 50); val kk = max(0f, 1 - (1 - d) / reach); val h = kk * (0.55f + 0.45f * fl)
                mul(mix(floatArrayOf(0.05f, 0f, 0f), mix(c1, c2, 1 - h), min(1f, h * 1.4f)), min(1f, 0.15f + h * 1.2f))
            }
            Fx.METEOR -> {
                var b = 0f
                for (m in 0 until 5) {
                    val sp = 0.6f + hash(m * 9 + 1) * 0.9f; val len = 0.08f + hash(m * 3 + 2) * 0.2f
                    val x = frac(ph * sp + hash(m * 5 + 3)) - d
                    if (x >= 0 && x < len) b = max(b, 1 - x / len)
                }
                mul(mix(c1, c2, b), max(0.03f, b))
            }
            Fx.AURORA -> {
                val w = 0.5f + 0.5f * sin(2 * PI.toFloat() * (d * 1.3f + t * 0.07f + ph * 0.2f)) * sin(2 * PI.toFloat() * (d * 0.7f - t * 0.05f))
                mul(mix(c1, c2, w), 0.45f + 0.55f * w)
            }
            Fx.ACCEL -> { val a = (acc / 8).coerceIn(0f, 1f); if (d < a) mul(c2, 0.6f + 0.4f * a) else mul(c1, 0.35f) }
            else -> c1
        }, k)
    }

    private fun comets(c1: FloatArray, c2: FloatArray, d: Float, ph: Float, mono: Boolean): FloatArray {
        var best = 0f; var bx = 0f
        for (k in 0 until 3) {
            var x = frac(ph + k / 3f) - d; if (x < 0) x += 1
            val b = exp(-x * 9); if (b > best) { best = b; bx = x }
        }
        return mul(if (mono) c1 else mix(c2, c1, exp(-bx * 6)), max(0.04f, best))
    }

    private fun frac(x: Float) = x - floor(x)
    private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * k }
    private fun mul(a: FloatArray, k: Float) = FloatArray(3) { min(1f, a[it] * k) }
    private fun hash(n0: Int): Float {  // как hash1 в прошивке
        var n = n0.toLong() and 0xffffffffL
        n = ((n shl 13) xor n) and 0xffffffffL
        n = (n * ((n * n * 15731L + 789221L) and 0xffffffffL) + 1376312589L) and 0xffffffffL
        return (n and 0x7fffffffL) / 2147483648f
    }
    private fun hsv(h0: Float): FloatArray {
        val h = frac(h0) * 6; val i = h.toInt(); val f = h - i; val v = 0.9f
        val q = v * (1 - f); val t = v * f
        return when (i % 6) { 0 -> floatArrayOf(v, t, 0f); 1 -> floatArrayOf(q, v, 0f); 2 -> floatArrayOf(0f, v, t)
            3 -> floatArrayOf(0f, q, v); 4 -> floatArrayOf(t, 0f, v); else -> floatArrayOf(v, 0f, q) }
    }
}
