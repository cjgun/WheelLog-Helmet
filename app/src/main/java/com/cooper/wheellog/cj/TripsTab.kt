package com.cooper.wheellog.cj

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cooper.wheellog.BuildConfig
import com.cooper.wheellog.data.LogTick
import com.cooper.wheellog.data.TripParser
import com.cooper.wheellog.utils.FileUtil
import com.cooper.wheellog.views.TripModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

/** 2024_08_08_08_28_11.csv -> «8 августа 2024, 08:28»; чужое имя - как есть. */
internal fun tripTitle(name: String): String {
    val m = Regex("""(\d{4})_(\d{2})_(\d{2})_(\d{2})_(\d{2})""").find(name) ?: return name.removeSuffix(".csv")
    val (y, mo, d, h, mi) = m.destructured
    return "${d.toInt()} ${MONTHS[mo.toInt() - 1]} $y, $h:$mi"
}

private fun plural(n: Int, one: String, few: String, many: String) =
    if (n % 10 == 1 && n % 100 != 11) one else if (n % 10 in 2..4 && n % 100 !in 12..14) few else many

/** Выбранная поездка (пусто - список). */
val openTrip = mutableStateOf<TripModel?>(null)

@Composable
fun TripsList(onRecord: () -> Unit) {
    val ctx = LocalContext.current
    val trips = remember { FileUtil.fillTrips(ctx).sortedByDescending { it.fileName } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Поездки", fontSize = 30.sp, fontWeight = FontWeight(300), modifier = Modifier.weight(1f))
                Text("ЗАПИСЬ ›", color = Cj.acc, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onRecord() }.padding(12.dp))
            }
            Text("${trips.size} " + plural(trips.size, "ЖУРНАЛ", "ЖУРНАЛА", "ЖУРНАЛОВ"), fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp))
            HorizontalDivider(color = Cj.hair)
        }
        if (trips.isEmpty()) item { Text("Журналов пока нет. Включи автозапись в «Запись ›».", color = Cj.mute, fontSize = 13.sp, modifier = Modifier.padding(vertical = 20.dp)) }
        items(trips) { t ->
            Row(Modifier.fillMaxWidth().clickable { openTrip.value = t }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tripTitle(t.title), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(t.description, fontSize = 12.sp, color = Cj.mute)
                }
                Text("›", fontSize = 20.sp, color = Cj.dim)
            }
            HorizontalDivider(color = Cj.hair)
        }
    }
}

private data class TripCalc(val ticks: List<LogTick>, val km: Double, val minutes: Int, val avg: Double, val max: Double, val err: String)

@Composable
fun TripView(t: TripModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var data by remember(t) { mutableStateOf<TripCalc?>(null) }
    LaunchedEffect(t) {
        data = withContext(Dispatchers.IO) {
            val p = TripParser.parseFile(ctx, t.fileName, t.pathLegacyAndroid ?: "", t.uri)
            val l = p.first
            val km = if (l.size > 1) (l.last().distance - l.first().distance) / 1000.0 else 0.0
            // время в движении: стоянки (скорость < 2) и разрывы записи больше 5 с не считаем
            var sec = 0.0
            for (i in 1 until l.size) { val dt = (l[i].time - l[i - 1].time) / 10.0; if (dt in 0.0..5.0 && l[i].speed >= 2) sec += dt }
            TripCalc(l, km, (sec / 60).roundToInt(), if (sec > 0) km / (sec / 3600) else 0.0, l.maxOfOrNull { it.speed } ?: 0.0, TripParser.lastError)
        }
    }
    var tab by remember { mutableStateOf(0) }
    var cur by remember { mutableStateOf(-1) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 26.sp, color = Cj.mute, modifier = Modifier.clickable { onBack() }.padding(horizontal = 12.dp))
                Text(tripTitle(t.title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            val d = data
            if (d == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cj.acc) }; return@Column }
            if (d.ticks.isEmpty()) { Text(d.err.ifEmpty { "Журнал пустой" }, color = Cj.mute, modifier = Modifier.padding(16.dp)); return@Column }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                listOf(fmt(d.km) to "км" to "Путь", "${d.minutes}" to "мин" to "В движении", fmt(d.avg) to "км/ч" to "Средняя", fmt(d.max, 0) to "км/ч" to "Максимум")
                    .forEachIndexed { i, (vu, l) ->
                        if (i > 0) Box(Modifier.width(1.dp).height(50.dp).background(Cj.hair))
                        Column(Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 10.dp, top = 4.dp, bottom = 6.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) { Text(vu.first, fontSize = 22.sp, fontWeight = FontWeight(300)); Text(" " + vu.second, fontSize = 10.5.sp, color = Cj.mute, modifier = Modifier.padding(bottom = 3.dp)) }
                            Text(l.uppercase(), fontSize = 9.5.sp, letterSpacing = 1.3.sp, color = Cj.mute, fontWeight = FontWeight.SemiBold)
                        }
                    }
            }
            HorizontalDivider(color = Cj.hair, modifier = Modifier.padding(horizontal = 14.dp))
            if (wide) Row(Modifier.fillMaxSize().padding(14.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) { TrackMap(d.ticks, cur) }
                Spacer(Modifier.width(16.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) { val h = (maxHeight - 60.dp) / 5 - 22.dp; Lanes(d.ticks, cur, h) { cur = it } }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    listOf("КАРТА", "ГРАФИКИ").forEachIndexed { i, s ->
                        Column(Modifier.weight(1f).clickable { tab = i }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold, color = if (tab == i) Cj.ink else Cj.mute)
                            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(2.dp).background(if (tab == i) Cj.acc else Cj.hair))
                        }
                    }
                }
                Box(Modifier.fillMaxSize().padding(14.dp)) { if (tab == 0) TrackMap(d.ticks, cur) else Lanes(d.ticks, cur) { cur = it } }
            }
        }
    }
}

/** Цвет трека по скорости: жёлтый - оранжевый - красный. */
private fun speedColor(v: Double): Int {
    val f = (v / 45.0).coerceIn(0.0, 1.0)
    return android.graphics.Color.rgb(255, (220 - 140 * f).toInt(), (90 - 80 * f).toInt())
}

@Composable
private fun TrackMap(ticks: List<LogTick>, cur: Int) {
    val ctx = LocalContext.current
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
    val marker = remember { mutableStateOf<org.osmdroid.views.overlay.Marker?>(null) }
    AndroidView(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), factory = { c ->
        MapView(c).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            // тёмная карта: серая инверсия, трек на ней виден лучше
            overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
                -0.21f, -0.42f, -0.08f, 0f, 175f, -0.21f, -0.42f, -0.08f, 0f, 178f, -0.21f, -0.42f, -0.08f, 0f, 186f, 0f, 0f, 0f, 1f, 0f))))
            // трек кусками по цвету скорости
            pts.windowed(2).forEach { (a, b) ->
                overlays.add(Polyline(this).apply {
                    setPoints(listOf(GeoPoint(a.latitude, a.longitude), GeoPoint(b.latitude, b.longitude)))
                    outlinePaint.color = if (b.pwm > 80) android.graphics.Color.rgb(255, 75, 62) else speedColor(b.speed)
                    outlinePaint.strokeWidth = 9f; outlinePaint.strokeCap = Paint.Cap.ROUND
                })
            }
            fun dot(p: LogTick, col: Int) = overlays.add(org.osmdroid.views.overlay.Marker(this).apply {
                position = GeoPoint(p.latitude, p.longitude); setAnchor(0.5f, 0.5f)
                icon = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply { paint.color = col; intrinsicWidth = 34; intrinsicHeight = 34 }
                setInfoWindow(null)
            })
            dot(pts.first(), android.graphics.Color.rgb(79, 209, 139)); dot(pts.last(), android.graphics.Color.WHITE)
            marker.value = org.osmdroid.views.overlay.Marker(this).apply {
                icon = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply { paint.color = Cj.acc.toArgb(); intrinsicWidth = 44; intrinsicHeight = 44 }
                setAnchor(0.5f, 0.5f); setInfoWindow(null); isEnabled = false
            }.also { overlays.add(it) }
            addOnFirstLayoutListener { _, _, _, _, _ ->
                zoomToBoundingBox(BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.latitude, it.longitude) }).increaseByScale(1.25f), false)
            }
        }
    }, update = { mv ->
        val m = marker.value ?: return@AndroidView
        if (cur in ticks.indices && ticks[cur].latitude != 0.0) { m.position = GeoPoint(ticks[cur].latitude, ticks[cur].longitude); m.isEnabled = true }
        else m.isEnabled = false
        mv.invalidate()
    })
}

private class Lane(val name: String, val color: Color, val f: (LogTick) -> Float)

/** Полосы: скорость, PWM, ток, напряжение, температура - общая ось времени и курсор. */
@Composable
private fun Lanes(ticks: List<LogTick>, cur: Int, laneH: androidx.compose.ui.unit.Dp = 96.dp, onCur: (Int) -> Unit) {
    val n = ticks.size
    val lanes = listOf(
        Lane("Скорость, км/ч", Cj.acc) { it.speed.toFloat() },
        Lane("PWM, %", Cj.pwm) { it.pwm.toFloat() },
        Lane("Ток, А", Cj.cur) { it.current.toFloat() },
        Lane("Напряжение, В", Cj.volt) { it.voltage.toFloat() },
        Lane("Температура, °C", Cj.red) { it.temperature.toFloat() },
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val p = ticks.getOrNull(cur)
        Text(if (p == null) "Веди пальцем по графикам" else "${p.timeString} · ${fmt(p.speed, 0)} км/ч · PWM ${fmt(p.pwm, 0)}% · ${fmt(p.current)} А · ${fmt(p.voltage)} В · ${p.temperature}°C",
            fontSize = 12.sp, color = if (p == null) Cj.mute else Cj.ink, modifier = Modifier.padding(bottom = 8.dp))
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
            Canvas(Modifier.fillMaxWidth().height(max(laneH.value, 80f).dp).padding(top = 4.dp, bottom = 12.dp)
                .pointerInput(n) { detectTapGestures { o -> onCur(((o.x - 44.dp.toPx()) / (size.width - 44.dp.toPx()) * (n - 1)).roundToInt().coerceIn(0, n - 1)) } }
                .pointerInput(n) { detectDragGestures { ch, _ -> onCur(((ch.position.x - 44.dp.toPx()) / (size.width - 44.dp.toPx()) * (n - 1)).roundToInt().coerceIn(0, n - 1)) } }) {
                val L = 44.dp.toPx(); val W = size.width - L; val H = size.height
                fun Y(v: Float) = H - (v - lo) / (hi - lo) * H
                fun X(i: Int) = L + i * W / max(1, n - 1)
                if (li == 1) { drawRect(Color(0x1AFFD166), Offset(L, Y(80f)), androidx.compose.ui.geometry.Size(W, Y(70f) - Y(80f))); drawRect(Color(0x24FF4B3E), Offset(L, Y(100f)), androidx.compose.ui.geometry.Size(W, Y(80f) - Y(100f))) }
                val paint = Paint().apply { color = Cj.mute.toArgb(); textSize = 10.sp.toPx(); textAlign = Paint.Align.RIGHT; isAntiAlias = true }
                for (k in 0..2) { val v = lo + (hi - lo) * k / 2; drawLine(Cj.hair, Offset(L, Y(v)), Offset(size.width, Y(v)), 1f); drawContext.canvas.nativeCanvas.drawText(fmt(v.toDouble(), 0), L - 6.dp.toPx(), Y(v) + 4.dp.toPx(), paint) }
                val step = max(1, n / 700)
                val path = Path(); var first = true
                for (i in 0 until n step step) { if (first) { path.moveTo(X(i), Y(vals[i])); first = false } else path.lineTo(X(i), Y(vals[i])) }
                if (li == 0) { val fill = Path().apply { addPath(path); lineTo(X(n - 1), H); lineTo(L, H); close() }; drawPath(fill, Brush.verticalGradient(listOf(meta.color.copy(alpha = 0.25f), Color.Transparent))) }
                clipRect(L, 0f, size.width, H) { drawPath(path, meta.color, style = Stroke(1.6.dp.toPx())) }
                if (li == 1) for (i in 0 until n step step) if (vals[i] > 80) drawCircle(Cj.red, 1.8.dp.toPx(), Offset(X(i), Y(vals[i])))
                if (cur in 0 until n) drawLine(Color(0x99FFFFFF), Offset(X(cur), 0f), Offset(X(cur), H), 1.dp.toPx())
            }
        }
        // ось времени: метки на тех же местах, что и точки графиков
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val L = 44.dp.toPx(); val W = size.width - L
            val paint = Paint().apply { color = Cj.mute.toArgb(); textSize = 10.sp.toPx(); isAntiAlias = true }
            for (k in 0..4) {
                paint.textAlign = when (k) { 0 -> Paint.Align.LEFT; 4 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                drawContext.canvas.nativeCanvas.drawText(ticks[(n - 1) * k / 4].timeString.take(5), L + W * k / 4, 12.dp.toPx(), paint)
            }
        }
    }
}
