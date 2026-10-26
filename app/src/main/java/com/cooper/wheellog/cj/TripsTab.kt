package com.cooper.wheellog.cj

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cooper.wheellog.BuildConfig
import com.cooper.wheellog.data.LogTick
import com.cooper.wheellog.data.TripParser
import com.cooper.wheellog.utils.FileUtil
import com.cooper.wheellog.views.TripModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MONTHS = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
private val NAME_RE = Regex("""(\d{4})_(\d{2})_(\d{2})_(\d{2})_(\d{2})""")
private val thisYear get() = Calendar.getInstance().get(Calendar.YEAR)

/** 2024_08_08_08_28_11.csv -> «08.08.24 · 08:28», в этом году - «23.10 · 08:59». */
internal fun tripTitle(name: String): String {
    val m = NAME_RE.find(name) ?: return name.removeSuffix(".csv")
    val (y, mo, d, h, mi) = m.destructured
    return "$d.$mo" + (if (y.toInt() != thisYear) "." + y.takeLast(2) else "") + " · $h:$mi"
}

/** Месяц поездки для заголовка группы: «ОКТЯБРЬ», в прошлые годы - «АВГУСТ 2024». */
private fun tripMonth(name: String): String {
    val m = NAME_RE.find(name) ?: return "ДРУГИЕ"
    val y = m.groupValues[1].toInt()
    return MONTHS[m.groupValues[2].toInt() - 1].uppercase() + if (y != thisYear) " $y" else ""
}

private fun tripMillis(name: String): Long {
    val m = NAME_RE.find(name) ?: return 0
    val (y, mo, d, h, mi) = m.destructured
    return Calendar.getInstance().apply { set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0) }.timeInMillis
}

/** Выбранная поездка (пусто - список). */
val openTrip = mutableStateOf<TripModel?>(null)

// --- итоги поездок для списка: считаются один раз и хранятся в filesDir/trip-sums.json ---

private class TripSum(val km: Double, val minutes: Int, val avg: Double, val vmax: Double, val total: Double, val pts: FloatArray) {
    fun toJson() = JSONObject().put("km", km).put("min", minutes).put("avg", avg).put("vmax", vmax).put("total", total)
        .put("pts", JSONArray().apply { pts.forEach { put(it.toDouble()) } })

    companion object {
        fun of(o: JSONObject) = TripSum(o.optDouble("km"), o.optInt("min"), o.optDouble("avg"), o.optDouble("vmax"), o.optDouble("total"),
            o.optJSONArray("pts")?.let { a -> FloatArray(a.length()) { a.optDouble(it).toFloat() } } ?: FloatArray(0))
    }
}

private object TripSums {
    val map = mutableStateMapOf<String, TripSum>()
    private var loaded = false
    fun key(t: TripModel) = t.title + "|" + t.description
    private fun file(ctx: android.content.Context) = File(ctx.filesDir, "trip-sums.json")

    fun load(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            o.keys().forEach { k -> map[k] = TripSum.of(o.getJSONObject(k)) }
        }
    }

    fun save(ctx: android.content.Context) {
        val o = JSONObject()
        map.toMap().forEach { (k, v) -> o.put(k, v.toJson()) }
        runCatching { file(ctx).writeText(o.toString()) }
    }

    /** Быстрый проход по CSV: путь, время в движении, максимум, пробег колеса и 48 точек трека для миниатюры. */
    fun compute(ctx: android.content.Context, t: TripModel): TripSum? = runCatching {
        ctx.contentResolver.openInputStream(t.uri)?.bufferedReader()?.use { r ->
            val head = r.readLine()?.split(',') ?: return null
            val iT = head.indexOf("time"); val iV = head.indexOf("speed"); val iD = head.indexOf("distance")
            val iTot = head.indexOf("totaldistance"); val iLat = head.indexOf("latitude"); val iLon = head.indexOf("longitude")
            var d0 = -1.0; var d1 = 0.0; var vmax = 0.0; var tot = 0.0; var sec = 0.0; var prevT = -1.0
            val lat = ArrayList<Float>(); val lon = ArrayList<Float>(); var n = 0
            r.forEachLine { line ->
                val c = line.split(',')
                val v = c.getOrNull(iV)?.toDoubleOrNull() ?: 0.0
                c.getOrNull(iD)?.toDoubleOrNull()?.let { if (d0 < 0) d0 = it; d1 = it }
                c.getOrNull(iTot)?.toDoubleOrNull()?.let { if (it > 0) tot = it }
                vmax = max(vmax, v)
                val tm = c.getOrNull(iT)?.let { s -> s.split(':').let { p -> if (p.size == 3) p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble() else null } }
                if (tm != null) { if (prevT >= 0) { val dt = tm - prevT; if (dt in 0.0..5.0 && v >= 2) sec += dt }; prevT = tm }
                if (n++ % 10 == 0) {
                    val la = c.getOrNull(iLat)?.toFloatOrNull() ?: 0f; val lo = c.getOrNull(iLon)?.toFloatOrNull() ?: 0f
                    if (la != 0f && lo != 0f) { lat.add(la); lon.add(lo) }
                }
            }
            val km = if (d0 >= 0) (d1 - d0) / 1000 else 0.0
            val pts = if (lat.size < 2) FloatArray(0) else {
                val k = 48.coerceAtMost(lat.size)
                FloatArray(k * 2) { j -> val i = (j / 2) * (lat.size - 1) / (k - 1); if (j % 2 == 0) lon[i] else lat[i] }
            }
            TripSum(km, (sec / 60).roundToInt(), if (sec > 0) km / (sec / 3600) else 0.0, vmax, tot / 1000, pts)
        }
    }.getOrNull()
}

@Composable
private fun Thumb(s: TripSum?) {
    Box(Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF14171B)), contentAlignment = Alignment.Center) {
        if (s == null) return@Box
        if (s.pts.size < 4) { Text("без GPS", fontSize = 9.sp, color = Cj.dim); return@Box }
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in s.pts.indices step 2) { x0 = min(x0, s.pts[i]); x1 = max(x1, s.pts[i]); y0 = min(y0, s.pts[i + 1]); y1 = max(y1, s.pts[i + 1]) }
            // широта сжимает долготу: поправка примерно на cos(45°)
            val sx = (x1 - x0) * 0.7f; val sy = y1 - y0
            val k = min(size.width / max(sx, 1e-6f), size.height / max(sy, 1e-6f))
            val ox = (size.width - sx * k) / 2; val oy = (size.height - sy * k) / 2
            val p = Path()
            for (i in s.pts.indices step 2) {
                val x = ox + (s.pts[i] - x0) * 0.7f * k; val y = size.height - oy - (s.pts[i + 1] - y0) * k
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, Cj.acc, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun TripsList(onRecord: () -> Unit) {
    val ctx = LocalContext.current
    val trips = remember { FileUtil.fillTrips(ctx).sortedByDescending { it.fileName } }
    LaunchedEffect(trips) {
        withContext(Dispatchers.IO) {
            TripSums.load(ctx)
            var changed = 0
            for (t in trips) {
                val k = TripSums.key(t)
                if (TripSums.map.containsKey(k)) continue
                TripSums.compute(ctx, t)?.let { TripSums.map[k] = it; changed++ }
                if (changed > 0 && changed % 10 == 0) TripSums.save(ctx)
            }
            if (changed > 0) TripSums.save(ctx)
        }
    }
    val sums = TripSums.map
    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
    val week = trips.filter { tripMillis(it.title) >= weekAgo }
    val weekKm = week.sumOf { sums[TripSums.key(it)]?.km ?: 0.0 }
    val total = trips.firstNotNullOfOrNull { t -> sums[TripSums.key(t)]?.total?.takeIf { it > 0 } }
    val up = uploaded(ctx)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Поездки", fontSize = 30.sp, fontWeight = FontWeight(300), modifier = Modifier.weight(1f))
                Text("ЗАПИСЬ ›", color = Cj.acc, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onRecord() }.padding(12.dp))
            }
            HorizontalDivider(color = Cj.hair)
            Totals(listOf(
                Triple(fmt(weekKm), "км", "за неделю"),
                Triple("${week.size}", "", "поездок"),
                Triple(total?.let { String.format(java.util.Locale("ru"), "%,.0f", it) } ?: "-", "км", CjState.wheelName.value.ifEmpty { "колесо" } + " всего"),
            ))
            HorizontalDivider(color = Cj.hair)
        }
        if (trips.isEmpty()) item { Text("Журналов пока нет. Включи автозапись в «Запись ›».", color = Cj.mute, fontSize = 13.sp, modifier = Modifier.padding(vertical = 20.dp)) }
        var lastMonth = ""
        trips.forEach { t ->
            val mon = tripMonth(t.title)
            if (mon != lastMonth) {
                lastMonth = mon
                item(key = "m$mon") { Text(mon, fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
            }
            item(key = t.title) {
                val s = sums[TripSums.key(t)]
                Row(Modifier.fillMaxWidth().clickable { openTrip.value = t }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(s)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(tripTitle(t.title), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (s == null) t.description else "${fmt(s.km)} км · ${s.minutes} мин · ${fmt(s.avg)} км/ч · макс ${fmt(s.vmax, 0)}") +
                                if (t.title in up) "  ·  на сайте" else "",
                            fontSize = 12.sp, color = Cj.mute,
                        )
                    }
                    Text("›", fontSize = 20.sp, color = Cj.dim)
                }
                HorizontalDivider(color = Cj.hair)
            }
        }
    }
}

/** Строка итогов: крупное число, единица, подпись; ячейки через тонкую линию. */
@Composable
private fun Totals(cells: List<Triple<String, String, String>>) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { i, (v, u, l) ->
            if (i > 0) Box(Modifier.width(1.dp).height(54.dp).background(Cj.hair))
            Column(Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 10.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(v, fontSize = 22.sp, fontWeight = FontWeight(300), maxLines = 1)
                    if (u.isNotEmpty()) Text(" $u", fontSize = 10.5.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text(l.uppercase(), fontSize = 9.5.sp, letterSpacing = 1.3.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// --- экран поездки ---

private class Brake(val at: Int, val from: Double, val to: Double)

private class TripCalc(
    val ticks: List<LogTick>, val km: Double, val minutes: Int, val avg: Double, val max: Double, val maxIdx: Int,
    val brakes: List<Brake>, val xsTime: FloatArray, val xsKm: FloatArray, val err: String,
)

private fun analyze(l: List<LogTick>, err: String): TripCalc {
    val n = l.size
    val km = if (n > 1) (l.last().distance - l.first().distance) / 1000.0 else 0.0
    // время в движении: стоянки (скорость < 2) и разрывы записи больше 5 с не считаем
    var sec = 0.0
    for (i in 1 until n) { val dt = (l[i].time - l[i - 1].time) / 10.0; if (dt in 0.0..5.0 && l[i].speed >= 2) sec += dt }
    var maxIdx = 0
    for (i in 0 until n) if (l[i].speed > l[maxIdx].speed) maxIdx = i
    // торможение: за 4 с скорость упала на 15 км/ч и больше; от пика к минимуму
    val brakes = ArrayList<Brake>()
    var i = 0
    while (i < n) {
        if (l[i].speed >= 20) {
            var p = i; var m = i; var j = i + 1
            while (j < n && l[j].time - l[p].time <= 40) {
                if (l[j].speed > l[p].speed) { p = j; m = j }
                if (l[j].speed < l[m].speed) m = j
                j++
            }
            if (l[p].speed - l[m].speed >= 15) { brakes.add(Brake(p, l[p].speed, l[m].speed)); i = m + 1; continue }
        }
        i++
    }
    // ось X: доля времени и доля пути, обе неубывающие (переход через полночь и сбросы не ломают курсор)
    // по времени разрыв записи больше 5 с считается за 1 с: стоянка между заездами не сжимает езду в край
    fun frac(cap: Double, f: (LogTick) -> Double): FloatArray {
        val a = FloatArray(n); var run = 0.0; var prev = if (n > 0) f(l[0]) else 0.0
        for (k in 0 until n) { val v = f(l[k]); var dv = v - prev; if (dv > cap) dv = cap / 5; if (dv > 0) run += dv; prev = v; a[k] = run.toFloat() }
        val last = if (n > 0) a[n - 1] else 0f
        if (last > 0) for (k in 0 until n) a[k] /= last else for (k in 0 until n) a[k] = if (n > 1) k.toFloat() / (n - 1) else 0f
        return a
    }
    return TripCalc(l, km, (sec / 60).roundToInt(), if (sec > 0) km / (sec / 3600) else 0.0, l.getOrNull(maxIdx)?.speed ?: 0.0, maxIdx,
        brakes, frac(50.0) { it.time.toDouble() }, frac(Double.MAX_VALUE) { it.distance.toDouble() }, err)
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).border(1.dp, if (on) Cj.acc else Color(0x26FFFFFF), RoundedCornerShape(50))
            .background(if (on) Cj.acc.copy(alpha = 0.14f) else Color.Transparent).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 12.5.sp, color = if (on) Cj.acc else Cj.ink) }
}

@Composable
private fun SquareBtn(text: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 6.dp).size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC15181C))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 16.sp, color = Cj.ink) }
}

@Composable
fun TripView(t: TripModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var data by remember(t) { mutableStateOf<TripCalc?>(null) }
    LaunchedEffect(t) {
        data = withContext(Dispatchers.IO) {
            val p = TripParser.parseFile(ctx, t.fileName, t.pathLegacyAndroid ?: "", t.uri)
            analyze(p.first, TripParser.lastError)
        }
    }
    var tab by remember { mutableStateOf(0) }
    var cur by remember { mutableStateOf(-1) }
    var byPwm by remember { mutableStateOf(false) }
    var byKm by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(0f to 1f) }
    var expanded by remember { mutableStateOf(0) }   // 0 - обычный вид, 1 - карта на весь экран, 2 - графики
    val zoomCtl = remember { mutableStateOf(0) }       // +1 / -1 - шаг масштаба карты
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 26.sp, color = Cj.mute, modifier = Modifier.clickable { if (expanded != 0) expanded = 0 else onBack() }.padding(horizontal = 12.dp))
                Text(tripTitle(t.title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                UploadButton(t)
            }
            val d = data
            if (d == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cj.acc) }; return@Column }
            if (d.ticks.isEmpty()) { Text(d.err.ifEmpty { "Журнал пустой" }, color = Cj.mute, modifier = Modifier.padding(16.dp)); return@Column }
            if (expanded == 0) {
                Box(Modifier.padding(horizontal = 14.dp)) {
                    Totals(listOf(Triple(fmt(d.km), "км", "путь"), Triple("${d.minutes}", "мин", "время"), Triple(fmt(d.avg), "км/ч", "средняя"), Triple(fmt(d.max, 0), "км/ч", "максимум")))
                }
                HorizontalDivider(color = Cj.hair, modifier = Modifier.padding(horizontal = 14.dp))
            }
            val mapPane: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Chip("Цвет: скорость", !byPwm) { byPwm = false }
                        Chip("Цвет: PWM", byPwm) { byPwm = true }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        TrackMap(d, cur, byPwm, zoomCtl)
                        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                            SquareBtn("+") { zoomCtl.value = zoomCtl.value + 1 }
                            Spacer(Modifier.height(6.dp))
                            SquareBtn("-") { zoomCtl.value = zoomCtl.value - 1 }
                            Spacer(Modifier.height(6.dp))
                            SquareBtn(if (expanded == 1) "✕" else "⤢") { expanded = if (expanded == 1) 0 else 1 }
                        }
                    }
                    if (expanded != 1) {
                        Legend()
                        Brakes(d) { cur = it }
                    }
                }
            }
            val chartPane: @Composable (Modifier, Dp) -> Unit = { m, laneH ->
                Column(m) {
                    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip("Время", !byKm) { byKm = false }
                        Chip("Километры", byKm) { byKm = true }
                        Spacer(Modifier.weight(1f))
                        SquareBtn("+") { view = zoomView(view, d, cur, byKm, 0.5f) }
                        SquareBtn("-") { view = zoomView(view, d, cur, byKm, 2f) }
                        SquareBtn(if (expanded == 2) "✕" else "⤢") { expanded = if (expanded == 2) 0 else 2 }
                    }
                    Readout(d, cur)
                    Lanes(d, cur, if (byKm) d.xsKm else d.xsTime, byKm, view, laneH) { cur = it }
                }
            }
            when {
                expanded == 1 -> mapPane(Modifier.fillMaxSize().padding(14.dp))
                expanded == 2 -> BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp)) { chartPane(Modifier.fillMaxSize(), (maxHeight - 110.dp) / 5 - 22.dp) }
                wide -> Row(Modifier.fillMaxSize().padding(14.dp)) {
                    mapPane(Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(16.dp))
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) { chartPane(Modifier.fillMaxSize(), (maxHeight - 110.dp) / 5 - 22.dp) }
                }
                else -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                        listOf("КАРТА", "ГРАФИКИ").forEachIndexed { i, s ->
                            Column(Modifier.weight(1f).clickable { tab = i }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold, color = if (tab == i) Cj.ink else Cj.mute)
                                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(2.dp).background(if (tab == i) Cj.acc else Cj.hair))
                            }
                        }
                    }
                    if (tab == 0) mapPane(Modifier.fillMaxSize().padding(14.dp))
                    else chartPane(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), 96.dp)
                }
            }
        }
    }
}

/** Масштаб графиков: окно сужается или расширяется вокруг курсора (без курсора - вокруг середины окна). */
private fun zoomView(v: Pair<Float, Float>, d: TripCalc, cur: Int, byKm: Boolean, k: Float): Pair<Float, Float> {
    val w = ((v.second - v.first) * k).coerceIn(0.02f, 1f)
    val xs = if (byKm) d.xsKm else d.xsTime
    val c = if (cur in xs.indices && xs[cur] in v.first..v.second) xs[cur] else (v.first + v.second) / 2
    var a = c - w / 2; var b = c + w / 2
    if (a < 0) { b -= a; a = 0f }
    if (b > 1) { a -= b - 1; b = 1f }
    return a.coerceAtLeast(0f) to b
}

@Composable
private fun Legend() {
    Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun dot(c: Color) = Box(Modifier.padding(end = 5.dp).size(8.dp).clip(CircleShape).background(c))
        dot(Cj.green); Text("старт   ", fontSize = 11.5.sp, color = Cj.mute)
        dot(Color.White); Text("финиш   ", fontSize = 11.5.sp, color = Cj.mute)
        Text("★ максимум   ", fontSize = 11.5.sp, color = Cj.acc)
        dot(Cj.red); Text("торможение · PWM>80%", fontSize = 11.5.sp, color = Cj.mute)
    }
}

@Composable
private fun Brakes(d: TripCalc, onPick: (Int) -> Unit) {
    if (d.brakes.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 168.dp)) {
        items(d.brakes) { b ->
            Row(Modifier.fillMaxWidth().clickable { onPick(b.at) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 10.dp).size(8.dp).clip(CircleShape).background(Cj.red))
                Text("Торможение ${fmt(b.from, 0)} → ${fmt(b.to, 0)} км/ч", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Text(d.ticks[b.at].timeString.take(5).trimStart('0'), fontSize = 12.sp, color = Cj.mute)
            }
            HorizontalDivider(color = Cj.hair)
        }
    }
}

/** Строка курсора: время, от старта, путь и значения цветом своих графиков. */
@Composable
private fun Readout(d: TripCalc, cur: Int) {
    val p = d.ticks.getOrNull(cur)
    if (p == null) { Text("Веди пальцем по графикам", fontSize = 12.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 8.dp)); return }
    val s0 = d.ticks.first()
    val since = ((p.time - s0.time) / 10).toInt().coerceAtLeast(0)
    val txt = buildAnnotatedString {
        withStyle(SpanStyle(color = Cj.ink, fontWeight = FontWeight.SemiBold)) { append(p.timeString.substringBefore('.')) }
        withStyle(SpanStyle(color = Cj.mute)) { append(" · ${if (since >= 3600) "${since / 3600}:%02d:%02d".format(since / 60 % 60, since % 60) else "${since / 60}:%02d".format(since % 60)} от старта · ${fmt((p.distance - s0.distance) / 1000.0, 2)} км\n") }
        fun v(label: String, value: String, c: Color) {
            withStyle(SpanStyle(color = c)) { append(label) }
            withStyle(SpanStyle(color = Cj.ink, fontWeight = FontWeight.SemiBold)) { append(" $value   ") }
        }
        v("Скорость", "${fmt(p.speed, 0)} км/ч", Cj.acc); v("PWM", "${fmt(p.pwm, 0)}%", Cj.pwm); v("Ток", "${fmt(p.current, 0)} А", Cj.cur)
        v("Напряжение", "${fmt(p.voltage)} В", Cj.volt); v("Температура", "${p.temperature} °C", Cj.red); v("Заряд", "${p.batteryLevel}%", Cj.mute)
    }
    Text(txt, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
}

/** Цвет трека по скорости: жёлтый - оранжевый - красный. */
private fun speedColor(v: Double): Int {
    val f = (v / 45.0).coerceIn(0.0, 1.0)
    return android.graphics.Color.rgb(255, (220 - 140 * f).toInt(), (90 - 80 * f).toInt())
}

/** Цвет трека по PWM: зелёный до 50, жёлтый к 70, красный от 80. */
private fun pwmColor(p: Double): Int = when {
    p >= 80 -> android.graphics.Color.rgb(255, 75, 62)
    p >= 50 -> { val f = ((p - 50) / 30).coerceIn(0.0, 1.0); android.graphics.Color.rgb((79 + 176 * f).toInt(), (209 - 20 * f).toInt(), (139 - 100 * f).toInt()) }
    else -> android.graphics.Color.rgb(79, 209, 139)
}

private fun dotIcon(col: Int, px: Int) = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
    paint.color = col; intrinsicWidth = px; intrinsicHeight = px
}

private fun starIcon(res: android.content.res.Resources, px: Int): android.graphics.drawable.Drawable {
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * 0.95f; textAlign = Paint.Align.CENTER }
    p.style = Paint.Style.STROKE; p.strokeWidth = px * 0.08f; p.color = 0xFF0B0D10.toInt()
    c.drawText("★", px / 2f, px * 0.82f, p)
    p.style = Paint.Style.FILL; p.color = Cj.acc.toArgb()
    c.drawText("★", px / 2f, px * 0.82f, p)
    return android.graphics.drawable.BitmapDrawable(res, bmp)
}

/** Трек кусками одного цвета: соседние точки с тем же оттенком склеиваются в одну линию. */
private fun buildTrack(mv: MapView, pts: List<LogTick>, byPwm: Boolean): List<Polyline> {
    val out = ArrayList<Polyline>()
    var run = ArrayList<GeoPoint>(); var runCol = 0
    fun flush() {
        if (run.size >= 2) out.add(Polyline(mv).apply {
            setPoints(run); outlinePaint.color = runCol; outlinePaint.strokeWidth = 9f; outlinePaint.strokeCap = Paint.Cap.ROUND
        })
    }
    for (i in 1 until pts.size) {
        val b = pts[i]
        val col = if (byPwm) pwmColor(b.pwm) else if (b.pwm > 80) android.graphics.Color.rgb(255, 75, 62) else speedColor((b.speed / 5).roundToInt() * 5.0)
        if (col != runCol || run.isEmpty()) {
            val last = run.lastOrNull()
            flush()
            run = ArrayList(); runCol = col
            run.add(last ?: GeoPoint(pts[i - 1].latitude, pts[i - 1].longitude))
        }
        run.add(GeoPoint(b.latitude, b.longitude))
    }
    flush()
    return out
}

@Composable
private fun TrackMap(d: TripCalc, cur: Int, byPwm: Boolean, zoomCtl: androidx.compose.runtime.MutableState<Int>) {
    val ctx = LocalContext.current
    val ticks = d.ticks
    val pts = remember(ticks) { ticks.filter { it.latitude != 0.0 && it.longitude != 0.0 } }
    if (pts.size < 2) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0A0C0F)), contentAlignment = Alignment.Center) {
            Text("GPS в этой поездке не записан", color = Cj.mute, fontSize = 13.sp)
        }
        return
    }
    remember {
        Configuration.getInstance().apply {
            load(ctx, androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
            userAgentValue = BuildConfig.APPLICATION_ID
            osmdroidBasePath = File(ctx.filesDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "titles").apply { mkdirs() }
        }
    }
    class Holder { var track: List<Polyline> = emptyList(); var byPwm: Boolean? = null; var marker: Marker? = null; var zoom = 0 }
    val h = remember { Holder() }
    AndroidView(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), factory = { c ->
        MapView(c).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // тёмная карта: серая инверсия, трек на ней виден лучше
            overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
                -0.21f, -0.42f, -0.08f, 0f, 175f, -0.21f, -0.42f, -0.08f, 0f, 178f, -0.21f, -0.42f, -0.08f, 0f, 186f, 0f, 0f, 0f, 1f, 0f))))
            fun mark(p: LogTick, icon: android.graphics.drawable.Drawable) = Marker(this).apply {
                position = GeoPoint(p.latitude, p.longitude); setAnchor(0.5f, 0.5f); this.icon = icon; setInfoWindow(null)
            }
            d.brakes.forEach { b -> ticks[b.at].takeIf { it.latitude != 0.0 }?.let { overlays.add(mark(it, dotIcon(android.graphics.Color.rgb(255, 75, 62), 22))) } }
            overlays.add(mark(pts.first(), dotIcon(android.graphics.Color.rgb(79, 209, 139), 34)))
            overlays.add(mark(pts.last(), dotIcon(android.graphics.Color.WHITE, 34)))
            ticks[d.maxIdx].takeIf { it.latitude != 0.0 }?.let { overlays.add(mark(it, starIcon(c.resources, 96))) }
            h.marker = Marker(this).apply { icon = dotIcon(Cj.acc.toArgb(), 44); setAnchor(0.5f, 0.5f); setInfoWindow(null); isEnabled = false }.also { overlays.add(it) }
            addOnFirstLayoutListener { _, _, _, _, _ ->
                zoomToBoundingBox(BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.latitude, it.longitude) }).increaseByScale(1.25f), false)
            }
        }
    }, update = { mv ->
        if (h.byPwm != byPwm) {
            mv.overlays.removeAll(h.track.toSet())
            h.track = buildTrack(mv, pts, byPwm)
            mv.overlays.addAll(0, h.track)
            h.byPwm = byPwm
        }
        if (zoomCtl.value != h.zoom) { if (zoomCtl.value > h.zoom) mv.controller.zoomIn() else mv.controller.zoomOut(); h.zoom = zoomCtl.value }
        val m = h.marker
        if (m != null) {
            if (cur in ticks.indices && ticks[cur].latitude != 0.0) { m.position = GeoPoint(ticks[cur].latitude, ticks[cur].longitude); m.isEnabled = true }
            else m.isEnabled = false
        }
        mv.invalidate()
    })
}

private class Lane(val name: String, val color: Color, val f: (LogTick) -> Float)

/** Полосы: скорость, PWM, ток, напряжение, температура - общая ось (время или путь), окно масштаба и курсор. */
@Composable
private fun Lanes(d: TripCalc, cur: Int, xs: FloatArray, byKm: Boolean, view: Pair<Float, Float>, laneH: Dp, onCur: (Int) -> Unit) {
    val ticks = d.ticks
    val n = ticks.size
    val (v0, v1) = view
    val lanes = listOf(
        Lane("Скорость, км/ч", Cj.acc) { it.speed.toFloat() },
        Lane("PWM, %", Cj.pwm) { it.pwm.toFloat() },
        Lane("Ток, А", Cj.cur) { it.current.toFloat() },
        Lane("Напряжение, В", Cj.volt) { it.voltage.toFloat() },
        Lane("Температура, °C", Cj.red) { it.temperature.toFloat() },
    )
    // индекс по доле оси: xs неубывающий - двоичный поиск
    fun indexAt(f: Float): Int {
        var lo = 0; var hi = n - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (xs[mid] < f) lo = mid + 1 else hi = mid }
        return lo
    }
    val i0 = indexAt(v0).coerceAtLeast(1) - 1
    val i1 = min(n - 1, indexAt(v1) + 1)
    val brakeAt = remember(d) { d.brakes.map { it.at } }
    Column(Modifier.fillMaxWidth()) {
        lanes.forEachIndexed { li, meta ->
            val vals = remember(ticks) { FloatArray(n) { meta.f(ticks[it]) } }
            val sorted = remember(vals) { vals.sorted() }
            val p1 = sorted[(n - 1) / 100]; val p99 = sorted[(n - 1) * 99 / 100]
            val lo = if (li <= 1) 0f else if (li == 2) min(0f, sorted.first()) else p1 - (p99 - p1) * 0.1f
            val hi = if (li == 1) 100f else if (li <= 2) sorted.last() * 1.08f + 0.01f else p99 + (p99 - p1) * 0.1f + 0.5f
            Row(verticalAlignment = Alignment.Bottom) {
                Text(meta.name, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = meta.color, modifier = Modifier.weight(1f))
                Text("мин ${fmt((if (li >= 3) p1 else sorted.first()).toDouble(), 0)} · сред ${fmt(vals.average(), 0)} · макс ${fmt(vals.max().toDouble(), 0)}", fontSize = 10.5.sp, color = Cj.mute)
            }
            Canvas(Modifier.fillMaxWidth().height(max(laneH.value, 70f).dp).padding(top = 4.dp, bottom = 12.dp)
                .pointerInput(n, view, xs) { detectTapGestures { o -> val L = 44.dp.toPx(); onCur(indexAt(v0 + (o.x - L) / (size.width - L) * (v1 - v0))) } }
                .pointerInput(n, view, xs) { detectDragGestures { ch, _ -> val L = 44.dp.toPx(); onCur(indexAt(v0 + (ch.position.x - L) / (size.width - L) * (v1 - v0))) } }) {
                val L = 44.dp.toPx(); val W = size.width - L; val H = size.height
                fun Y(v: Float) = H - (v - lo) / (hi - lo) * H
                fun X(i: Int) = L + (xs[i] - v0) / (v1 - v0) * W
                if (li == 1) {
                    drawRect(Color(0x1AFFD166), Offset(L, Y(80f)), androidx.compose.ui.geometry.Size(W, Y(70f) - Y(80f)))
                    drawRect(Color(0x24FF4B3E), Offset(L, Y(100f)), androidx.compose.ui.geometry.Size(W, Y(80f) - Y(100f)))
                }
                val paint = Paint().apply { color = Cj.mute.toArgb(); textSize = 10.sp.toPx(); textAlign = Paint.Align.RIGHT; isAntiAlias = true }
                for (k in 0..2) { val v = lo + (hi - lo) * k / 2; drawLine(Cj.hair, Offset(L, Y(v)), Offset(size.width, Y(v)), 1f); drawContext.canvas.nativeCanvas.drawText(fmt(v.toDouble(), 0), L - 6.dp.toPx(), Y(v) + 4.dp.toPx(), paint) }
                val step = max(1, (i1 - i0) / 700)
                val path = Path(); var first = true
                for (i in i0..i1 step step) { if (first) { path.moveTo(X(i), Y(vals[i])); first = false } else path.lineTo(X(i), Y(vals[i])) }
                clipRect(L, 0f, size.width, H) {
                    if (li == 0) { val fill = Path().apply { addPath(path); lineTo(X(i1), H); lineTo(X(i0), H); close() }; drawPath(fill, Brush.verticalGradient(listOf(meta.color.copy(alpha = 0.25f), Color.Transparent))) }
                    drawPath(path, meta.color, style = Stroke(1.6.dp.toPx()))
                    if (li == 0) brakeAt.forEach { i -> if (i in i0..i1) drawCircle(Cj.red, 2.6.dp.toPx(), Offset(X(i), Y(vals[i]))) }
                    if (li == 1) for (i in i0..i1 step step) if (vals[i] > 80) drawCircle(Cj.red, 1.8.dp.toPx(), Offset(X(i), Y(vals[i])))
                    if (cur in i0..i1) drawLine(Color(0x99FFFFFF), Offset(X(cur), 0f), Offset(X(cur), H), 1.dp.toPx())
                }
            }
        }
        // ось: время или километры на тех же местах, что и точки графиков
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val L = 44.dp.toPx(); val W = size.width - L
            val paint = Paint().apply { color = Cj.mute.toArgb(); textSize = 10.sp.toPx(); isAntiAlias = true }
            val d0 = ticks.first().distance
            for (k in 0..4) {
                val i = indexAt(v0 + (v1 - v0) * k / 4)
                paint.textAlign = when (k) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                val s = if (byKm) fmt((ticks[i].distance - d0) / 1000.0) + " км" else ticks[i].timeString.take(5)
                drawContext.canvas.nativeCanvas.drawText(s, L + W * k / 4, 12.dp.toPx(), paint)
            }
        }
    }
}

// --- выгрузка на cjgun.ru ---

private const val UPLOAD_URL = "https://cjgun.ru/poezdki/priem.php"

private fun cjPrefs(ctx: android.content.Context) = ctx.getSharedPreferences("cj", android.content.Context.MODE_PRIVATE)

/** Имена журналов, уже выгруженных на сайт. */
private fun uploaded(ctx: android.content.Context): Set<String> = cjPrefs(ctx).getStringSet("uploaded", emptySet()) ?: emptySet()

/** Журнал сжимается gzip и уходит телом POST, ключ - в заголовке X-Key. Ответ: (успех, текст). */
private fun uploadTrip(ctx: android.content.Context, t: TripModel, key: String): Pair<Boolean, String> = try {
    val buf = java.io.ByteArrayOutputStream()
    ctx.contentResolver.openInputStream(t.uri).use { inp ->
        if (inp == null) return false to "Журнал не открывается"
        java.util.zip.GZIPOutputStream(buf).use { inp.copyTo(it) }
    }
    val c = java.net.URL(UPLOAD_URL + "?name=" + java.net.URLEncoder.encode(t.fileName, "UTF-8")).openConnection() as java.net.HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 60000
    c.setRequestProperty("X-Key", key); c.setRequestProperty("Content-Type", "application/gzip")
    c.setFixedLengthStreamingMode(buf.size())
    c.outputStream.use { it.write(buf.toByteArray()) }
    val code = c.responseCode
    val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
    val js = runCatching { org.json.JSONObject(body) }.getOrNull()
    if (code == 200 && js?.optBoolean("ok") == true) true to (if (js.optBoolean("dup")) "Уже на сайте" else "Выгружено")
    else false to (js?.optString("error")?.ifEmpty { null } ?: "Ошибка $code")
} catch (e: Exception) { false to "Нет связи с cjgun.ru" }

@Composable
private fun UploadButton(t: TripModel) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var askKey by remember { mutableStateOf(false) }
    var state by remember(t) { mutableStateOf(if (t.title in uploaded(ctx)) "НА САЙТЕ ✓" else "НА CJGUN.RU ↑") }
    var busy by remember { mutableStateOf(false) }
    fun send(key: String) {
        busy = true; state = "ВЫГРУЖАЮ…"
        scope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { uploadTrip(ctx, t, key) }
            busy = false
            if (ok) {
                cjPrefs(ctx).edit().putStringSet("uploaded", uploaded(ctx) + t.title).apply(); state = "НА САЙТЕ ✓"
            } else state = "ПОВТОРИТЬ ›"
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            if (msg == "Неверный ключ") askKey = true
        }
    }
    Text(state, color = if (state.startsWith("НА САЙТЕ")) Cj.green else Cj.acc, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(enabled = !busy) {
            val key = cjPrefs(ctx).getString("cjgun_key", "") ?: ""
            if (key.isEmpty()) askKey = true else send(key)
        }.padding(12.dp))
    if (askKey) {
        var v by remember { mutableStateOf(cjPrefs(ctx).getString("cjgun_key", "") ?: "") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { askKey = false },
            title = { Text("Ключ cjgun.ru") },
            text = { Column {
                Text("Ключ выдаёт сайт. Хранится только в этом телефоне.", fontSize = 13.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 10.dp))
                androidx.compose.material3.OutlinedTextField(v, { v = it.trim() }, singleLine = true, label = { Text("Ключ") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            } },
            confirmButton = { androidx.compose.material3.TextButton(enabled = v.isNotEmpty(), onClick = {
                cjPrefs(ctx).edit().putString("cjgun_key", v).apply(); askKey = false; send(v)
            }) { Text("Выгрузить", color = Cj.acc) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askKey = false }) { Text("Отмена", color = Cj.mute) } },
            containerColor = Color(0xFF15181C))
    }
}
