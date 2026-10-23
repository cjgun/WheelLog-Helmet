package com.cooper.wheellog.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cooper.wheellog.helmet.HelmetLink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Config прошивки шлема v3, 34 байта little-endian (fw/src/protokol.h). */
private class HelmetConfig(src: ByteArray) {
    val b: ByteArray = src.copyOf(34)
    private val bb get() = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(o: Int) = b[o].toInt() and 0xff
    fun set8(o: Int, v: Int) { b[o] = v.coerceIn(0, 255).toByte() }
    fun u16(o: Int) = bb.getShort(o).toInt() and 0xffff
    fun set16(o: Int, v: Int) { bb.putShort(o, v.coerceIn(0, 65535).toShort()) }
    fun flag(m: Int) = u8(3) and m != 0
    fun setFlag(m: Int, on: Boolean) = set8(3, if (on) u8(3) or m else u8(3) and m.inv())
    val ok get() = u8(0) == 3
}

// Правка держится вне экрана: сложили или разложили Fold - активность пересоздаётся, правка остаётся
private val edit = mutableStateOf<ByteArray?>(null)
private val editRev = mutableIntStateOf(0)

private val MODES = mapOf(-1 to ("нет соединения" to Color.Gray), 0 to ("нет данных колеса" to Color(0xFF9E9E9E)),
    1 to ("норма" to Color(0xFFB71C1C)), 2 to ("торможение" to Color(0xFFFF1744)), 3 to ("разгон" to Color(0xFF00C8A0)))
private val COLORS = listOf("бирюзовый" to intArrayOf(0, 200, 160), "зелёный" to intArrayOf(0, 220, 0),
    "белый" to intArrayOf(200, 200, 200), "синий" to intArrayOf(0, 80, 255), "жёлтый" to intArrayOf(255, 160, 0),
    "фиолетовый" to intArrayOf(160, 0, 255))
private val STRIPS = listOf("WS2812 (GRB)", "RGB", "BRG", "SK6812 RGBW (GRBW)", "RGBW")

@Composable
fun helmetScreen() {
    val loaded by HelmetLink.config
    LaunchedEffect(loaded) {
        if (loaded != null) { edit.value = loaded!!.copyOf(); editRev.intValue++ }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            // разложенный Fold: связь слева, настройки справа
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) { connectionPart() }
                Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()).padding(8.dp)) { settingsPart() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                connectionPart()
                settingsPart()
            }
        }
    }
}

@Composable
private fun card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun connectionPart() {
    var enabled by remember { mutableStateOf(HelmetLink.enabled) }
    var address by remember { mutableStateOf(HelmetLink.address) }
    val found = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    val status by HelmetLink.status
    val mode by HelmetLink.mode
    val sent by HelmetLink.sent

    card("Шлем CJ-Helmet") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Передавать данные на шлем", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it; HelmetLink.enabled = it })
        }
        Text(if (address.isEmpty()) "Шлем не выбран" else "Шлем: $address", style = MaterialTheme.typography.bodyMedium)
        Text("Связь: $status", style = MaterialTheme.typography.bodyMedium)
        val (mName, mColor) = MODES[mode] ?: ("?" to Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Box(Modifier.size(14.dp).background(mColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Режим: $mName", fontWeight = FontWeight.Bold)
        }
        Text("Отправлено пакетов: $sent", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            found.clear()
            scanning = HelmetLink.startScan { n, a -> found[a] = n }
        }) { Text(if (scanning) "Ищу..." else "Найти шлем") }
        found.forEach { (a, n) ->
            Text("$n  $a", Modifier.fillMaxWidth().clickable {
                HelmetLink.stopScan(); scanning = false
                address = a; HelmetLink.address = a
                if (!enabled) { enabled = true; HelmetLink.enabled = true }
                found.clear()
            }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
        }
        Text("Колесу модуль ничего не пишет. Чтобы связь не рвалась в кармане, отключите для приложения экономию батареи.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun settingsPart() {
    val raw = edit.value
    if (raw == null) {
        card("Настройки шлема") {
            Text("Появятся, когда шлем будет на связи")
            // только отладочная сборка: посмотреть экран без шлема (эмулятор без Bluetooth)
            if (com.cooper.wheellog.BuildConfig.DEBUG) TextButton(onClick = {
                edit.value = "0328ff00fa0032000000280 01e0087000f0037005000780000c8a00fd007fa000800"
                    .replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                editRev.intValue++
            }) { Text("Пример настроек (отладка)") }
        }
        return
    }
    val c = HelmetConfig(raw)
    if (!c.ok) {
        card("Настройки шлема") { Text("Другая версия прошивки шлема (${c.u8(0)}), нужна 3") }
        return
    }
    // каждая правка - новая копия, чтобы Compose перерисовал
    val rev = editRev.intValue
    fun upd(f: HelmetConfig.() -> Unit) { val n = HelmetConfig(raw); n.f(); edit.value = n.b; editRev.intValue++ }
    key(rev) {
        card("Торможение") {
            slider("Замедление, км/ч в секунду", c.u16(6) / 10f, 0f, 15f, 0.5f, "%.1f", "0 - не учитывать") { v -> upd { set16(6, (v * 10).roundToInt()) } }
            slider("Наклон назад, градусы", c.u16(30) / 100f, 0f, 6f, 0.25f, "%.2f", "сверх среднего за ${c.u8(32)} с; 0 - не учитывать") { v -> upd { set16(30, (v * 100).roundToInt()) } }
            slider("Сглаживание, мс", c.u16(4).toFloat(), 50f, 1000f, 50f, "%.0f") { v -> upd { set16(4, v.roundToInt()) } }
        }
        card("Разгон") {
            slider("Ускорение, км/ч в секунду", c.u16(10) / 10f, 0f, 15f, 0.5f, "%.1f", "0 - режим выключен") { v -> upd { set16(10, (v * 10).roundToInt()) } }
            slider("Не ниже скорости, км/ч", c.u16(12) / 10f, 0f, 20f, 1f, "%.0f") { v -> upd { set16(12, (v * 10).roundToInt()) } }
            Text("Цвет разгона", Modifier.padding(top = 6.dp))
            chips(COLORS.map { it.first }, COLORS.indexOfFirst { it.second.contentEquals(intArrayOf(c.u8(24), c.u8(25), c.u8(26))) }) { i ->
                upd { COLORS[i].second.forEachIndexed { k, v -> set8(24 + k, v) } }
            }
        }
        card("Яркость и питание") {
            slider("Габарит", c.u8(1).toFloat(), 0f, 255f, 5f, "%.0f") { v -> upd { set8(1, v.roundToInt()) } }
            slider("Стоп и поворот", c.u8(2).toFloat(), 0f, 255f, 5f, "%.0f") { v -> upd { set8(2, v.roundToInt()) } }
            slider("Предел тока ленты, мА", c.u16(28).toFloat(), 100f, 6000f, 100f, "%.0f", "от USB платы - не больше 400") { v -> upd { set16(28, v.roundToInt()) } }
        }
        card("Лента") {
            slider("Точек в ленте", c.u16(14).toFloat(), 1f, 300f, 1f, "%.0f") { v -> upd { set16(14, v.roundToInt()) } }
            Text("Тип ленты", Modifier.padding(top = 6.dp))
            chips(STRIPS, c.u8(33)) { i -> upd { set8(33, i) } }
            Text("Зоны по номерам точек; 0 - справа спереди у подбородка", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
            val n = c.u16(14).toFloat()
            slider("Правый бок с точки", c.u16(16).toFloat(), 0f, n, 1f, "%.0f") { v -> upd { set16(16, v.roundToInt()) } }
            slider("Зад с точки", c.u16(18).toFloat(), 0f, n, 1f, "%.0f") { v -> upd { set16(18, v.roundToInt()) } }
            slider("Левый бок с точки", c.u16(20).toFloat(), 0f, n, 1f, "%.0f") { v -> upd { set16(20, v.roundToInt()) } }
            slider("Перед слева с точки", c.u16(22).toFloat(), 0f, n, 1f, "%.0f") { v -> upd { set16(22, v.roundToInt()) } }
            switchRow("Белый спереди", c.flag(0x02)) { on -> upd { setFlag(0x02, on) } }
            switchRow("Режим разметки (каждая 10-я точка)", c.flag(0x04)) { on -> upd { setFlag(0x04, on) } }
            slider("Поворот гаснет через, с", c.u8(27).toFloat(), 1f, 60f, 1f, "%.0f") { v -> upd { set8(27, v.roundToInt()) } }
        }
        val zonesOk = c.u16(16) <= c.u16(18) && c.u16(18) <= c.u16(20) && c.u16(20) <= c.u16(22) && c.u16(22) <= c.u16(14)
        Row(Modifier.padding(vertical = 10.dp)) {
            Button(onClick = { HelmetLink.writeConfig(c.b) }, enabled = zonesOk) { Text("Записать в шлем") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { HelmetLink.config.value?.let { edit.value = it.copyOf(); editRev.intValue++ } }) { Text("Отменить") }
        }
        if (!zonesOk) Text("Зоны должны идти по порядку и не дальше числа точек", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun slider(name: String, value: Float, min: Float, max: Float, step: Float, fmt: String,
                   hint: String = "", onDone: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(value.coerceIn(min, max)) }
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(name, Modifier.weight(1f))
            Text(fmt.format(v), fontWeight = FontWeight.Bold)
        }
        val steps = if (max > min) (((max - min) / step).roundToInt() - 1).coerceIn(0, 300) else 0
        Slider(value = v, onValueChange = { v = (Math.round(it / step) * step).coerceIn(min, max) },
            onValueChangeFinished = { onDone(v) }, valueRange = min..maxOf(max, min + step),
            steps = if (steps <= 300) steps else 0)
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun switchRow(name: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(name, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun chips(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, s ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(s) })
        }
    }
}
