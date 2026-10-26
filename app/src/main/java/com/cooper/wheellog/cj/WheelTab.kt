package com.cooper.wheellog.cj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooper.wheellog.AppConfig
import com.cooper.wheellog.WheelData
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

/** История последней минуты для мини-графиков: 2 точки в секунду. */
private class Hist { val v = mutableStateListOf<Float>(); fun add(x: Double) { v.add(x.toFloat()); while (v.size > 120) v.removeAt(0) } }

/** Вкладка «Колесо»: живая телеметрия Шмеля. Настройки колеса и предупреждения - по «Настройки ›». */
@Composable
fun WheelTelemetry(onSettings: () -> Unit, appConfig: AppConfig = koinInject()) {
    var r by remember { mutableStateOf(readRide()) }
    val hCur = remember { Hist() }; val hPow = remember { Hist() }; val hVolt = remember { Hist() }
    val hPwm = remember { Hist() }; val hPh = remember { Hist() }; val hSpd = remember { Hist() }; val hTmp = remember { Hist() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            r = readRide(); val wd = WheelData.getInstance()
            if (tick % 5 == 0) { hCur.add(r.cur); hPow.add(r.power); hVolt.add(r.volt); hPwm.add(r.pwm); hPh.add(wd?.phaseCurrentDouble ?: 0.0); hSpd.add(r.speed); hTmp.add(r.temp.toDouble()) }
            tick++; delay(100)
        }
    }
    val wd = WheelData.getInstance()
    var cellSel by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        CjHeaderInline("Колесо", "Настройки", onSettings)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (CjState.connected.value) Cj.green else Color(0xFF3A3F46)))
            Text("  " + listOf(CjState.wheelName.value.ifEmpty { "Колесо" }, wd?.model ?: "").filter { it.isNotEmpty() }.joinToString(" · "),
                fontSize = 12.5.sp, color = Cj.mute)
        }
        // батарея
        Sec("Батарея", if (!CjState.connected.value) null else if (r.bat <= 20) "низкий заряд" to Cj.red else "норма" to Cj.green)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${r.bat}", fontSize = 54.sp, fontWeight = FontWeight(200), letterSpacing = (-1.5).sp)
            Text(" %", fontSize = 16.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 10.dp))
            Spacer(Modifier.width(14.dp))
            Text(fmt(r.volt), fontSize = 22.sp, fontWeight = FontWeight(300), modifier = Modifier.padding(bottom = 8.dp))
            Text(" В", fontSize = 12.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 10.dp))
        }
        Vals(listOf(Triple(fmt(r.cur), "А", "Ток") to (hCur to Cj.pwm), Triple(fmt(r.power / 1000, 2), "кВт", "Мощность") to (hPow to Cj.acc),
            Triple(fmt(r.volt), "В", "Напряжение") to (hVolt to Cj.volt)))
        if (r.cells.isNotEmpty()) {
            val all = r.cells.flatten(); val med = all.sorted()[all.size / 2]; val mn = all.min(); val mx = all.max()
            Spacer(Modifier.height(10.dp))
            r.cells.forEachIndexed { b, row ->
                row.chunked(18).forEachIndexed { half, part ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (half == 0) (if (b == 0) "Л" else "П") else "", fontSize = 10.sp, color = Cj.mute, modifier = Modifier.width(12.dp))
                        part.forEachIndexed { i, c ->
                            val idx = b * 1000 + half * 18 + i
                            val d = abs(c - med)
                            val col = if (d < 0.012) Cj.green else if (d < 0.03) Cj.warm else Cj.red
                            Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(4.dp)).background(col)
                                .then(if (cellSel == idx) Modifier.background(Color.White.copy(alpha = 0.35f)) else Modifier)
                                .clickable { cellSel = if (cellSel == idx) -1 else idx })
                        }
                    }
                }
            }
            val sel = if (cellSel >= 0) r.cells.getOrNull(cellSel / 1000)?.getOrNull(cellSel % 1000) else null
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("мин ${fmt(mn, 3)} В", fontSize = 11.5.sp, color = Cj.mute)
                Text("макс ${fmt(mx, 3)} В", fontSize = 11.5.sp, color = Cj.mute)
                Text("разброс ${((mx - mn) * 1000).roundToInt()} мВ", fontSize = 11.5.sp,
                    color = if (mx - mn > 0.05) Cj.red else if (mx - mn > 0.03) Cj.warm else Cj.green)
                if (sel != null) Text("${if (cellSel / 1000 == 0) "Л" else "П"}·${cellSel % 1000 + 1}: ${fmt(sel, 3)} В", fontSize = 11.5.sp)
            }
        }
        // мотор
        Sec("Мотор и нагрузка")
        PwmRuler(r.pwm, listOf(appConfig.warningPwm, appConfig.alarmFactor1, appConfig.alarmFactor2).filter { it in 1..99 }.ifEmpty { listOf(70, 80, 90) })
        Vals(listOf(Triple("${r.pwm.roundToInt()}", "%", "PWM") to (hPwm to Cj.pwm), Triple(fmt(wd?.phaseCurrentDouble ?: 0.0, 0), "А", "Фазный ток") to (hPh to Cj.cur),
            Triple("${r.speed.roundToInt()}", "км/ч", "Скорость") to (hSpd to Cj.ink)))
        // наклон
        val ang = wd?.angle ?: 0.0
        Sec("Наклон", (if (ang > 0) "назад " else "вперёд ") + fmt(abs(ang)) + "°" to Cj.mute)
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val cx = size.width / 2; val cy = size.height * 0.45f; val k = (size.width / 2 - 16f) / 15f
            for (a in -15..15) { val x = cx + a * k; val maj = a % 5 == 0
                drawLine(if (maj) Color(0x80FFFFFF) else Color(0x26FFFFFF), Offset(x, cy - (if (maj) 11 else 6) * density), Offset(x, cy + (if (maj) 11 else 6) * density), if (maj) 2f else 1.2f) }
            val v = ang.coerceIn(-15.0, 15.0).toFloat(); val x = cx + v * k; val col = if (v > 2.5f) Cj.red else Cj.acc
            drawLine(col, Offset(cx, cy), Offset(x, cy), 3 * density, StrokeCap.Round)
            drawCircle(Brush.radialGradient(listOf(col, Color.Transparent), Offset(x, cy), 14 * density), 14 * density, Offset(x, cy))
            drawCircle(Color.White, 4 * density, Offset(x, cy))
        }
        Row { Text("ВПЕРЁД", fontSize = 10.sp, color = Cj.mute, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f)); Text("НАЗАД", fontSize = 10.sp, color = Cj.mute, letterSpacing = 1.4.sp) }
        // температура и пробег
        Sec("Температура и пробег")
        Vals(listOf(Triple("${r.temp}", "°C", "Контроллер") to (hTmp to Cj.red), Triple(fmt(r.trip), "км", "Поездка") to null, Triple(r.time, "", "В пути") to null))
        Vals(listOf(Triple(fmt(r.total, 0), "км", "Общий") to null, Triple(fmt(r.avg), "км/ч", "Средняя") to null,
            Triple(fmt(wd?.topSpeedDouble ?: 0.0, 0), "км/ч", "Максимум") to null))
        Sec("О колесе")
        Kv("Модель", wd?.model ?: "-"); Kv("Прошивка", wd?.version ?: "-"); Kv("MAC", wd?.mac ?: "-")
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CjHeaderInline(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight(300), modifier = Modifier.weight(1f))
        Text("${action.uppercase()} ›", color = Cj.acc, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onAction() }.padding(12.dp))
    }
}

@Composable
private fun Sec(title: String, right: Pair<String, Color>? = null) {
    Column(Modifier.padding(top = 14.dp)) {
        HorizontalDivider(color = Cj.hair)
        Row(Modifier.padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (right != null) Text(right.first, fontSize = 12.sp, color = right.second)
        }
    }
}

@Composable
private fun Vals(items: List<Pair<Triple<String, String, String>, Pair<Hist, Color>?>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        items.forEachIndexed { i, (t, h) ->
            if (i > 0) Box(Modifier.width(1.dp).height(64.dp).background(Cj.hair))
            Column(Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 10.dp, end = 6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(t.first, fontSize = if (t.first.length > 6) 20.sp else 24.sp, fontWeight = FontWeight(300), maxLines = 1, softWrap = false)
                    if (t.second.isNotEmpty()) Text(" " + t.second, fontSize = 11.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text(t.third.uppercase(), fontSize = 9.5.sp, letterSpacing = 1.3.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold)
                if (h != null) Spark(h.first, h.second)
            }
        }
    }
}

@Composable
private fun Spark(h: Hist, col: Color) {
    Canvas(Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp)) {
        val v = h.v.toList(); if (v.size < 2) return@Canvas
        val lo = v.min(); val hi = v.max(); val rg = (hi - lo).takeIf { it > 1e-3f } ?: 1f
        val p = Path(); v.forEachIndexed { i, x -> val px = i * size.width / (v.size - 1); val py = size.height - 2 - (x - lo) / rg * (size.height - 4); if (i == 0) p.moveTo(px, py) else p.lineTo(px, py) }
        val fill = Path().apply { addPath(p); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(col.copy(alpha = 0.3f), Color.Transparent)))
        drawPath(p, col, style = Stroke(1.6f * density))
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(k, fontSize = 13.5.sp, color = Cj.mute, modifier = Modifier.weight(1f)); Text(v, fontSize = 13.5.sp)
    }
    HorizontalDivider(color = Cj.hair)
}
