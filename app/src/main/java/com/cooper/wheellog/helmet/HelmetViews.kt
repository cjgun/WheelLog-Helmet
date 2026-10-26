package com.cooper.wheellog.helmet

import com.cooper.wheellog.R
import kotlin.math.hypot

/**
 * Шлем на картинках владельца (cj-helmet/kartinki): левый бок, правый бок, перед, зад.
 * Лента у обоих шлемов по нижнему краю. Её ход на каждой картинке снят по светящейся полосе
 * (lenta-tochki.json), x и y - в пикселях картинки, ломаная слева направо.
 * u - место на кольце ленты: 0 перед по центру, 0.25 правый бок, 0.5 зад, 0.75 левый бок;
 * ua и ub - u у левого и правого конца ломаной. Точка ленты с таким u рисуется на всех видах, где видна.
 */
object HelmetViews {
    class Line(val x: FloatArray, val y: FloatArray, val ua: Float, val ub: Float) {
        private val len = FloatArray(x.size).also { for (i in 1 until x.size) it[i] = it[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1]) }

        /** Точка на ломаной для u, или null - на этом виде её не видно. */
        fun at(u: Float): Pair<Float, Float>? {
            val lo = minOf(ua, ub); val hi = maxOf(ua, ub)
            if (u < lo || u > hi) return null
            val d = (u - ua) / (ub - ua) * len.last()
            var i = 1
            while (i < len.size - 1 && len[i] < d) i++
            val t = ((d - len[i - 1]) / (len[i] - len[i - 1]).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
            return Pair(x[i - 1] + (x[i] - x[i - 1]) * t, y[i - 1] + (y[i] - y[i - 1]) * t)
        }
    }

    class View(val res: Int, val w: Int, val h: Int, val lines: List<Line>)

    val FF_SIDE = View(R.drawable.shlem_ff_levyj, 420, 360, listOf(
            Line(floatArrayOf(33f,45f,57f,69f,81f,93f,105f,117f,129f,141f,153f,165f,177f,189f,201f,213f,225f,237f,249f,261f,273f,285f,297f,309f,321f,333f,345f,357f,369f,372f),
                floatArrayOf(286.1f,290.4f,293.2f,296.4f,299.4f,302f,304.4f,306.5f,308.5f,310.5f,312.5f,314.2f,316f,318f,320f,321.9f,323.9f,326f,328.3f,330.5f,332.9f,335.2f,337.7f,340f,342.5f,344.9f,347.2f,349.2f,351f,351.3f), 0.58f, 0.95f)))
    val FF_SIDE2 = View(R.drawable.shlem_ff_pravyj, 410, 360, listOf(
            Line(floatArrayOf(45f,57f,69f,81f,93f,105f,117f,129f,141f,153f,165f,177f,189f,201f,213f,225f,237f,249f,261f,273f,285f,297f,309f,321f,333f,345f,357f,369f,375f),
                floatArrayOf(351.2f,349.5f,347.2f,344.6f,342.2f,339.6f,337f,334.5f,331.9f,329.5f,327.2f,325f,322.8f,320.7f,318.7f,317f,315.2f,313.5f,311.8f,310.1f,308.3f,306.5f,304.6f,302.3f,300f,297.2f,294.6f,290f,287.2f), 0.05f, 0.42f)))
    val FF_FRONT = View(R.drawable.shlem_ff_pered, 320, 366, listOf(
            Line(floatArrayOf(33f,45f,57f,69f,81f,93f,105f,117f,129f,132f),
                floatArrayOf(279.1f,295.5f,308.9f,319.8f,329.2f,337.7f,345.2f,351.4f,356.3f,356.9f), 0.15f, 0.0f),
            Line(floatArrayOf(192f,204f,216f,228f,240f,252f,264f,276f,288f),
                floatArrayOf(355.9f,351.5f,345.4f,338.2f,330.1f,320.3f,309.1f,296.2f,280.6f), 1.0f, 0.85f)))
    val FF_BACK = View(R.drawable.shlem_ff_zad, 325, 380, listOf(
            Line(floatArrayOf(33f,50.8f,68.6f,86.4f,104.2f,122f,139.8f,157.6f,175.4f,193.2f,211f,228.8f,246.6f,264.4f,282.2f,300f),
                floatArrayOf(318.3f,325.2f,331.1f,335.9f,339.7f,342.4f,344.1f,344.7f,344.3f,342.8f,340.3f,336.8f,332.2f,326.5f,319.8f,312.1f), 0.65f, 0.35f)))
    // по отметкам владельца 26.10: лента наклеена выше кромки, сзади дугой
    val MX_SIDE = View(R.drawable.shlem_mx_levyj, 480, 375, listOf(
            Line(floatArrayOf(61f,110f,167f,240f,311f,389f,467f),
                floatArrayOf(274f,262f,252f,236f,224f,217f,219f), 0.95f, 0.58f)))
    // по отметкам владельца 26.10: лента наклеена выше кромки, сзади дугой
    val MX_SIDE2 = View(R.drawable.shlem_mx_pravyj, 380, 375, listOf(
            Line(floatArrayOf(2f,60f,118f,180f,235f,275f,299f,320f,335f),
                floatArrayOf(205f,206f,208f,212f,219f,230f,241f,270f,318f), 0.42f, 0.05f)))
    val MX_FRONT = View(R.drawable.shlem_mx_pered, 300, 395, listOf(
            Line(floatArrayOf(18f,30f,42f,54f,66f,78f,90f,102f,114f,126f),
                floatArrayOf(301.5f,309.7f,318.7f,326.1f,333.2f,340.4f,348f,356.7f,366.2f,372.1f), 0.15f, 0.0f),
            Line(floatArrayOf(177f,189f,201f,213f,225f,237f,249f,261f,273f,279f),
                floatArrayOf(372.2f,364.3f,354.3f,345.4f,337.7f,330.4f,322.6f,314.2f,304.6f,301.5f), 1.0f, 0.85f)))
    // по отметкам владельца 26.10: лента наклеена выше кромки, сзади дугой
    val MX_BACK = View(R.drawable.shlem_mx_zad, 290, 395, listOf(
            Line(floatArrayOf(8f,2f,6f,22f,57f,100f,140f,180f,216f,257f,274f,278f,271f),
                floatArrayOf(336f,290f,255f,221f,208f,196f,190f,193f,201f,225f,250f,290f,336f), 0.65f, 0.35f)))

    /** Виды по порядку на экране: левый бок, правый бок, перед, зад. */
    fun forHelmet(mx: Boolean) = if (mx) listOf(MX_SIDE, MX_SIDE2, MX_FRONT, MX_BACK) else listOf(FF_SIDE, FF_SIDE2, FF_FRONT, FF_BACK)
    val titles = listOf("левый бок", "правый бок", "спереди", "сзади")
}
