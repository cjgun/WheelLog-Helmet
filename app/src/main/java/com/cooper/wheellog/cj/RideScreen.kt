package com.cooper.wheellog.cj

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooper.wheellog.AppConfig
import com.cooper.wheellog.WheelData
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Снимок данных колеса для экрана: опрашивается 10 раз в секунду. */
internal data class Ride(
    val speed: Double = 0.0, val pwm: Double = 0.0, val volt: Double = 0.0, val cur: Double = 0.0,
    val power: Double = 0.0, val temp: Int = 0, val bat: Int = 0, val trip: Double = 0.0, val total: Double = 0.0,
    val avg: Double = 0.0, val time: String = "0:00", val cells: List<List<Double>> = emptyList(),
)

internal fun readRide(): Ride {
    val wd = WheelData.getInstance() ?: return Ride()
    val n = runCatching { wd.adapter.cellsForWheel }.getOrDefault(0)
    // в массиве BMS бывает больше ячеек, чем у колеса, хвост - мусор; берём n и только правдоподобные
    fun cells(b: com.cooper.wheellog.utils.SmartBms?): List<Double> =
        runCatching { b!!.cells.toList().let { if (n > 0) it.take(n) else it }.filter { it in 2.5..4.5 } }.getOrDefault(emptyList())
    val c = listOf(cells(wd.bms1), cells(wd.bms2)).filter { it.isNotEmpty() }
    return Ride(
        speed = wd.speedDouble, pwm = wd.calculatedPwm, volt = wd.voltageDouble, cur = wd.currentDouble,
        power = wd.powerDouble, temp = wd.temperature, bat = wd.batteryLevel, trip = wd.distanceDouble,
        total = wd.totalDistanceDouble, avg = wd.averageRidingSpeedDouble, time = wd.ridingTimeString ?: "0:00", cells = c,
    )
}

internal fun fmt(v: Double, d: Int = 1) = String.format(Locale.US, "%.${d}f", v)

/** Экран «Езда»: «Прибор» или «Цифра», под ними PWM и плитки значений. */
@Composable
fun RideScreen(appConfig: AppConfig = koinInject()) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cj", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("ride_mode", "dial") ?: "dial") }
    var r by remember { mutableStateOf(readRide()) }
    LaunchedEffect(Unit) { while (true) { r = readRide(); delay(100) } }
    val mph = appConfig.useMph
    val k = if (mph) 0.621371 else 1.0
    val unit = if (mph) "МИЛЬ/Ч" else "КМ/Ч"
    val maxV = 120  // шкала до 120 км/ч, зона от 90 - красная (слово владельца 26.10)
    val redV = 90
    val marks = listOf(appConfig.warningPwm, appConfig.alarmFactor1, appConfig.alarmFactor2).filter { it in 1..99 }.ifEmpty { listOf(70, 80, 90) }
    val hot = r.pwm >= (marks.getOrNull(1) ?: 80)
    val toggle = { mode = if (mode == "dial") "num" else "dial"; prefs.edit().putString("ride_mode", mode).apply() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF161A20), Color(0xFF0D1014), Color(0xFF07080A)), center = Offset.Unspecified, radius = 1400f))) {
    val wide = maxWidth >= 600.dp
    val leftW = maxWidth * 0.46f
    Row(Modifier.fillMaxSize()) {
    Column(Modifier.then(if (wide) Modifier.width(leftW) else Modifier.fillMaxWidth()).fillMaxHeight().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            Row(Modifier.border(1.dp, Cj.hair, RoundedCornerShape(50)).padding(3.dp)) {
                listOf("dial" to "ПРИБОР", "num" to "ЦИФРА").forEach { (m, t) ->
                    val on = m == mode
                    Text(t, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) Cj.ink else Cj.mute,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(0x17FFFFFF) else Color.Transparent)
                            .clickable(role = Role.Tab) { if (!on) toggle() }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { toggle() },
            contentAlignment = Alignment.Center) {
            if (mode == "dial") Dial(r.speed * k, maxV, redV, if (hot || r.speed * k >= redV) Cj.red else Cj.acc, unit, fmt(r.total * k, 1))
            else NumberView(r, k, unit)
        }
        PwmRuler(r.pwm, marks)
        Spacer(Modifier.height(6.dp))
        Tiles(listOf(
            fmt(r.cur) to "А" to "Ток", fmt(r.power / 1000, 2) to "кВт" to "Мощность", "${r.temp}" to "°C" to "Темп.",
            fmt(r.trip * k) to (if (mph) "ми" else "км") to "Поездка", fmt(r.avg * k) to (if (mph) "ми/ч" else "км/ч") to "Средняя", r.time to "" to "В пути",
        ))
        Spacer(Modifier.height(10.dp))
    }
    if (wide) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(Cj.hair))
        AllParams(r, k, mph, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 18.dp))
    }
    }
    }
}

/** Все параметры сеткой - правая колонка на разложенном Fold. */
@Composable
private fun AllParams(r: Ride, k: Double, mph: Boolean, modifier: Modifier) {
    val wd = WheelData.getInstance()
    val du = if (mph) "ми" else "км"; val su = if (mph) "ми/ч" else "км/ч"
    val list = listOf(
        fmt(r.speed * k) to su to "Скорость", fmt(r.avg * k) to su to "Средняя", fmt((wd?.topSpeedDouble ?: 0.0) * k) to su to "Максимум",
        fmt(r.trip * k) to du to "Поездка", fmt(r.total * k) to du to "Общий", r.time to "" to "В пути",
        fmt(r.volt) to "В" to "Напряжение", "${r.bat}" to "%" to "Заряд", fmt(r.cur) to "А" to "Ток",
        fmt(wd?.phaseCurrentDouble ?: 0.0) to "А" to "Фазный ток", fmt(r.power, 0) to "Вт" to "Мощность", "${r.pwm.roundToInt()}" to "%" to "PWM",
        "${r.temp}" to "°C" to "Температура", fmt(wd?.angle ?: 0.0) to "°" to "Наклон", fmt(wd?.maxPwm ?: 0.0, 0) to "%" to "Макс. PWM",
    )
    Column(modifier.padding(top = 10.dp)) {
        Text("ВСЕ ПАРАМЕТРЫ", fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        Tiles(list)
    }
}

@Composable
private fun Dial(v: Double, maxV: Int, redV: Int, col: Color, unit: String, odo: String) {
    val tm = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val side = minOf(maxWidth, maxHeight * 1.18f)
    BoxWithConstraints(Modifier.width(side).aspectRatio(1.18f), contentAlignment = Alignment.Center) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height * 0.57f; val R = size.width * 0.435f; val dd = density
            val a0 = 0.72 * Math.PI; val a1 = 2.28 * Math.PI
            fun ang(x: Double) = a0 + (a1 - a0) * x / maxV
            // красная зона на шкале
            val rz = R + 4 * dd
            drawArc(Cj.red.copy(alpha = 0.55f), (ang(redV.toDouble()) * 180 / Math.PI).toFloat(), ((ang(maxV.toDouble()) - ang(redV.toDouble())) * 180 / Math.PI).toFloat(), false,
                Offset(cx - rz, cy - rz), Size(rz * 2, rz * 2), style = Stroke(2.5f * dd))
            for (kk in 0..maxV step 2) {
                val a = ang(kk.toDouble()); val maj = kk % 20 == 0; val mid = kk % 10 == 0
                val r1 = R + 4 * dd; val r0 = R - (if (maj) 10f else if (mid) 7f else 4f) * dd
                val lit = kk <= v
                val zone = kk >= redV
                drawLine(
                    if (zone) (if (lit) Cj.red else Cj.red.copy(alpha = if (maj) 0.85f else 0.45f))
                    else if (lit) (if (maj) Color.White else Color(0x8CFFFFFF)) else (if (maj) Color(0x8CFFFFFF) else Color(0x29FFFFFF)),
                    Offset((cx + cos(a) * r0).toFloat(), (cy + sin(a) * r0).toFloat()), Offset((cx + cos(a) * r1).toFloat(), (cy + sin(a) * r1).toFloat()),
                    strokeWidth = (if (maj) 1.6f else 0.9f) * dd,
                )
                if (maj) {
                    val lay = tm.measure("$kk", TextStyle(fontFamily = Manrope, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (zone) Cj.red else if (lit) Cj.ink else Color(0xFF5B626C)))
                    drawText(lay, topLeft = Offset((cx + cos(a) * (R - 22 * dd)).toFloat() - lay.size.width / 2f, (cy + sin(a) * (R - 22 * dd)).toFloat() - lay.size.height / 2f))
                }
            }
            val sweep = ((ang(min(v, maxV.toDouble())) - a0) * 180 / Math.PI).toFloat()
            val rr = R + 10 * dd
            drawArc(Color(0x0FFFFFFF), (a0 * 180 / Math.PI).toFloat(), ((a1 - a0) * 180 / Math.PI).toFloat(), false,
                Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(1.5f * dd, cap = StrokeCap.Round))
            if (v > 0.3) {
                drawArc(col, (a0 * 180 / Math.PI).toFloat(), sweep, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(2.5f * dd, cap = StrokeCap.Round))
                val a = ang(min(v, maxV.toDouble())); val e = Offset((cx + cos(a) * rr).toFloat(), (cy + sin(a) * rr).toFloat())
                drawCircle(Brush.radialGradient(listOf(col, Color.Transparent), e, 13 * dd), 13 * dd, e)
                drawCircle(Color.White, 2.5f * dd, e)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = w * 0.02f)) {
            val fs = with(LocalDensity.current) { (w * 0.365f).toSp() }
            Text("${v.roundToInt()}", fontSize = fs, fontWeight = FontWeight(200), letterSpacing = (-2).sp, lineHeight = fs, color = Cj.ink)
            Text(unit, fontSize = 11.sp, letterSpacing = 3.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold)
        }
        // одометр внизу, между «0» и концом шкалы
        Row(Modifier.align(Alignment.BottomCenter).offset(y = w * 0.02f).border(1.dp, Cj.hair, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(odo, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp); Text(" км", fontSize = 13.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
    }
}

@Composable
private fun NumberView(r: Ride, k: Double, unit: String) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fs = with(LocalDensity.current) { minOf(maxWidth * 0.5f, (maxHeight - (if (r.cells.isNotEmpty()) 150 else 110).dp) * 0.92f).coerceAtLeast(60.dp).toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(r.speed * k).roundToInt()}", fontSize = fs, lineHeight = fs, fontWeight = FontWeight(200), letterSpacing = (-3).sp, textAlign = TextAlign.Center)
            Text(unit, fontSize = 11.sp, letterSpacing = 3.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("БАТАРЕЯ · ${fmt(r.volt)} В", fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${r.bat}%", fontSize = 22.sp, fontWeight = FontWeight(300))
            }
            if (r.cells.isNotEmpty()) Cells(r.cells)
            else Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x14FFFFFF))) {
                Box(Modifier.fillMaxWidth(r.bat / 100f).height(6.dp).background(Cj.green))
            }
        }
    }
}

/** Ячейки батареи точками, по строке на блок: цвет - отклонение от средней. */
@Composable
private fun Cells(blocks: List<List<Double>>) {
    val all = blocks.flatten(); val avg = all.sorted()[all.size / 2]; val mn = all.min(); val mx = all.max()  // от медианы
    Canvas(Modifier.fillMaxWidth().height((18 * blocks.size + 8).dp).padding(top = 8.dp)) {
        blocks.forEachIndexed { b, row ->
            val y = 9.dp.toPx() + b * 18.dp.toPx()
            row.forEachIndexed { i, c ->
                val x = 6f + i * (size.width - 12f) / max(1, row.size - 1)
                val d = abs(c - avg)
                val col = if (d < 0.012) Cj.green else if (d < 0.03) Cj.warm else Cj.red
                val ext = c == mn || c == mx
                drawCircle(col.copy(alpha = if (ext) 1f else 0.85f), if (ext) 5.dp.toPx() * 0.6f else 3.dp.toPx() * 0.7f, Offset(x, y))
                if (ext) drawCircle(col, 4.5.dp.toPx(), Offset(x, y), style = Stroke(1.5f))
            }
        }
    }
}

@Composable
internal fun PwmRuler(pwm: Double, marks: List<Int>) {
    val tm = rememberTextMeasurer()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("PWM", fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${pwm.roundToInt()}%", fontSize = 22.sp, fontWeight = FontWeight(300), color = if (pwm >= (marks.getOrNull(1) ?: 80)) Cj.red else Cj.ink)
        }
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val y = 11.dp.toPx()
            for (kk in 0..100 step 2) {
                val x = 4f + (size.width - 8f) * kk / 100f; val maj = kk % 10 == 0
                val lit = kk <= pwm
                val col = if (!lit) Color(0x24FFFFFF) else if (kk >= (marks.getOrNull(1) ?: 80)) Cj.red else if (kk >= marks[0]) Cj.warm else Color(0xD9FFFFFF)
                drawLine(col, Offset(x, y - if (maj) 7.dp.toPx() else 4.dp.toPx()), Offset(x, y + if (maj) 7.dp.toPx() else 4.dp.toPx()), strokeWidth = (if (maj) 1.4f else 0.8f) * density)
            }
            marks.forEachIndexed { i, m ->
                val x = 4f + (size.width - 8f) * m / 100f
                val lay = tm.measure("$m", TextStyle(fontFamily = Manrope, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = if (i == 0) Cj.warm else Cj.red))
                drawText(lay, topLeft = Offset(x - lay.size.width / 2f, size.height - lay.size.height))
            }
        }
    }
}

@Composable
private fun Tiles(items: List<Pair<Pair<String, String>, String>>) {
    Column(Modifier.fillMaxWidth()) {
        items.chunked(3).forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Cj.hair))
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                row.forEachIndexed { i, (vu, label) ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxSize().weight(0.001f).background(Cj.hair))
                    Column(Modifier.weight(1f).padding(start = if (i == 0) 2.dp else 10.dp, top = 6.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(vu.first, fontSize = if (vu.first.length > 6) 22.sp else 27.sp, fontWeight = FontWeight(300), letterSpacing = (-0.3).sp, maxLines = 1, softWrap = false)
                            if (vu.second.isNotEmpty()) Text(" " + vu.second, fontSize = 11.5.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Text(label.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.4.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
