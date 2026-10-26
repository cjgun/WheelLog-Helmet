package com.cooper.wheellog.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import com.cooper.wheellog.helmet.*
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

// Правки держатся вне экрана: сложили или разложили Fold - активность пересоздаётся, правка остаётся
private val edits = mutableStateMapOf<String, ByteArray>()
private val selSeg = mutableIntStateOf(-1)

private val MODE_COLOR = mapOf(-1 to Color.Gray, Mode.NOSIGNAL to Color(0xFF9E9E9E), Mode.NORM to Color(0xFFB71C1C),
    Mode.BRAKE to Color(0xFFFF1744), Mode.ACCEL to Color(0xFF00C8A0), Mode.WARN to Color(0xFFFFA000), Mode.PARK to Color(0xFF43A047))
private val COLORS = listOf("бирюзовый" to intArrayOf(0, 200, 160), "зелёный" to intArrayOf(0, 220, 0),
    "белый" to intArrayOf(200, 200, 200), "синий" to intArrayOf(0, 80, 255), "жёлтый" to intArrayOf(255, 160, 0),
    "оранжевый" to intArrayOf(255, 90, 0), "фиолетовый" to intArrayOf(160, 0, 255), "красный" to intArrayOf(255, 0, 0))
private val TESTS = listOf(1 to "Габарит", 2 to "Стоп", 3 to "Разгон", 4 to "Поворот ←", 5 to "Поворот →", 6 to "Аварийка",
    7 to "PWM", 8 to "Стоянка", 9 to "Разметка")

// ======================= общий список =======================

/** Раздел «Подсветка»: шлемы и колесо. На разложенном Fold справа сразу открыто выбранное устройство. */
@Composable
fun lightingScreen(onOpen: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            if (Lighting.selected.value.isEmpty()) Lighting.selected.value = Lighting.devices.firstOrNull()?.id ?: ""
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    hubPart { Lighting.selected.value = it; devSection.value = "" }
                }
                Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    if (Lighting.device(Lighting.selected.value) != null) devicePart(Lighting.selected.value, wide = true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                hubPart { Lighting.selected.value = it; devSection.value = ""; onOpen(it) }
            }
        }
    }
}

/** Экран одного устройства (сложенный телефон). */
@Composable
fun lightDeviceScreen() {
    val id = Lighting.selected.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        if (Lighting.device(id) != null) devicePart(id, wide = false) else Text("Устройство удалено", Modifier.padding(16.dp))
    }
}

@Composable
private fun hubPart(open: (String) -> Unit) {
    val enabled by Lighting.enabled
    val mode by Lighting.mode
    val turn by Lighting.turn
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Text("Свет", fontSize = 30.sp, fontWeight = FontWeight(300), modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { Lighting.setEnabled(it) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        val col = MODE_COLOR[mode] ?: Color.Gray
        Box(Modifier.size(10.dp).background(col, CircleShape))
        Text("  " + (Mode.names[mode] ?: "нет данных"), fontSize = 22.sp, fontWeight = FontWeight(300))
        Text(when (turn) { 1 -> "  · поворот налево"; 2 -> "  · поворот направо"; 3 -> "  · аварийка"; else -> "" }, fontSize = 13.sp, color = MUTE)
    }
    HorizontalDivider(color = HAIR)
    Lighting.devices.forEach { d -> deviceRow(d, open) }
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("+ Шлем", color = ACC, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { open(Lighting.add(DevKind.HELMET_FF)) }.padding(vertical = 12.dp, horizontal = 4.dp))
        if (Lighting.devices.none { it.kind == DevKind.WHEEL }) Text("+ Колесо", color = ACC, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { open(Lighting.add(DevKind.WHEEL)) }.padding(vertical = 12.dp, horizontal = 12.dp))
    }
    testCard()
}

@Composable
private fun deviceRow(d: LightDevice, open: (String) -> Unit) {
    val linkOn = (Lighting.esp[d.id]?.linked == true) || (Lighting.zg[d.id]?.linked == true)
    Lighting.frameTick.intValue  // перерисовать при смене связи
    val c = d.cfgObj()
    val what = when {
        !d.espOn && !d.zgOn -> "без оборудования"
        d.isHelmet && d.espOn && c.hasFx -> Fx.names.getOrElse(c.fx) { "" } + if (c.hasStopFx) " · стоп " + Stop.names.getOrElse(c.stopMode) { "" }.lowercase() else ""
        d.isHelmet -> if (d.zgOn && !d.espOn) "ZENGGE" else "плата CJ"
        else -> "ходовые, габариты, педали, обод"
    }
    val sel = Lighting.selected.value == d.id
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
        .background(if (sel) Color(0x0DFFFFFF) else Color.Transparent).clickable { open(d.id) }.padding(vertical = 10.dp)) {
        if (d.isHelmet) Image(painterResource(HelmetViews.forHelmet(d.kind == DevKind.HELMET_MX)[0].res), null,
            Modifier.size(96.dp, 66.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentScale = ContentScale.Crop)
        else Image(painterResource(com.cooper.wheellog.R.drawable.shmel_bok), null,
            Modifier.size(96.dp, 66.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(what, fontSize = 12.5.sp, color = MUTE, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Box(Modifier.size(6.dp).background(if (linkOn) Color(0xFF4FD18B) else Color(0xFF3A3F46), CircleShape))
                Text(if (linkOn) "  НА СВЯЗИ" else if (d.espOn || d.zgOn) "  НЕТ СВЯЗИ" else "  НЕ НАСТРОЕНО", fontSize = 10.5.sp,
                    letterSpacing = 1.4.sp, color = MUTE, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("›", fontSize = 20.sp, color = Color(0xFF4A5059))
    }
    HorizontalDivider(color = HAIR)
}

private val MaterialTheme_onSurface = Color(0xFFCFD8E6)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun testCard() {
    card("Проверка на всех сразу") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            TESTS.forEach { (w, n) ->
                Text(n, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.border(1.dp, HAIR, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50)).clickable { Lighting.test(w) }.padding(horizontal = 13.dp, vertical = 9.dp))
            }
            Text("Стоп проверки", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = ACC,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { Lighting.test(0) }.padding(horizontal = 13.dp, vertical = 9.dp))
        }
        Text("5 секунд на всех платах и ZENGGE сразу. Плата не включает проверку, если колесо едет быстрее 3 км/ч.", fontSize = 12.sp, color = MUTE)
    }
}

// ======================= одно устройство =======================

@Composable
private fun devicePart(id: String, wide: Boolean) {
    val d = Lighting.device(id) ?: return
    val link = Lighting.esp[id]
    val zl = Lighting.zg[id]
    // кадры ленты нужны, пока экран открыт
    DisposableEffect(link) { link?.setFrame(true); onDispose { link?.setFrame(false) } }
    // настройки платы: правка начинается с копии с платы или из памяти приложения
    val boardCfg = link?.config?.value
    LaunchedEffect(boardCfg) {
        if (Cfg.isBoardCfg(boardCfg)) edits[id] = boardCfg!!.copyOf()
    }
    if (edits[id] == null) edits[id] = d.cfgObj().b

    val sec = devSection.value
    if (sec.isNotEmpty()) androidx.activity.compose.BackHandler { devSection.value = "" }
    Column(Modifier.fillMaxWidth()) {
        if (sec.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("‹", fontSize = 26.sp, color = MUTE, modifier = Modifier.clickable { devSection.value = "" }.padding(end = 14.dp))
            Text(SECTIONS[sec] ?: "", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(d.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val on = link?.linked == true || zl?.linked == true
            Box(Modifier.size(7.dp).background(if (on) Color(0xFF4FD18B) else Color(0xFF3A3F46), CircleShape))
            Text(if (on) "  НА СВЯЗИ" else "  НЕТ СВЯЗИ", fontSize = 10.5.sp, letterSpacing = 1.4.sp, color = MUTE, fontWeight = FontWeight.SemiBold)
        }
        picture(d, link, zl, small = sec.isNotEmpty(), quad = wide)
        val m = link?.mode?.intValue ?: if (zl != null) Lighting.mode.intValue else -1
        if (sec.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Box(Modifier.size(9.dp).background(MODE_COLOR[m] ?: Color.Gray, CircleShape))
                Text("  " + (Mode.names[m] ?: "?"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (link != null) Text("лента ${link.ma.intValue} мА", fontSize = 12.sp, color = MUTE)
            }
            quickTests()
            sectionList(d)
        }
    }
    when (sec) {
        "fx" -> fxCard(d, link)
        "tape" -> segCard(d, link)
        "stop" -> stopSection(d)
        "bright" -> brightSection(d)
        "pedals" -> pedalSection(d)
        "rim" -> rimSection(d)
        "conn" -> connSection(d)
        "board" -> { nameAndKind(d); equipCard(d); boardCard(d, link) }
        "zg" -> zenggeCard(d, zl)
        "local" -> thresholdsLocal(d)
    }
    if (d.espOn && sec.isNotEmpty() && sec !in listOf("board", "zg", "local")) writeBar(d, link)
    Spacer(Modifier.height(24.dp))
}

/** Вид шлема на экране устройства (бок, бок справа, перед, зад). */
private val heroView = mutableIntStateOf(0)

/** Какой раздел устройства открыт (пусто - список разделов). */
val devSection = mutableStateOf("")

private val SECTIONS = mapOf("fx" to "Эффект", "tape" to "Лента и цвета", "stop" to "Стоп и сигналы", "bright" to "Яркость и ток",
    "pedals" to "Педали", "rim" to "Обод", "conn" to "Связь", "board" to "Плата", "zg" to "ZENGGE", "local" to "Когда стоп")

@Composable
private fun sectionList(d: LightDevice) {
    val c = Cfg(edits[d.id] ?: d.cfgObj().b)
    val items = buildList {
        if (d.espOn && d.isHelmet) add("fx" to (if (c.hasFx) Fx.names.getOrElse(c.fx) { "" } else "после обновления прошивки"))
        if (d.espOn) add("tape" to "${c.segs.size} сегм. · ${c.segs.sumOf { it.count }} точек")
        if (d.espOn) add("stop" to (if (c.hasStopFx) Stop.names.getOrElse(c.stopMode) { "" }.lowercase() else "пороги стопа"))
        if (d.espOn) add("bright" to "габарит ${c.brightTail} · ${c.limitMa} мА")
        if (d.espOn && !d.isHelmet) add("pedals" to "цвет · заряд на стоянке")
        if (d.espOn && !d.isHelmet) add("rim" to "мигание, гасить на стоянке")
        if (d.espOn) add("conn" to if (d.isHelmet) "ESP-NOW с колесом" else "по проводу, от шлема")
        if (d.zgOn) add("zg" to "вся лента целиком")
        if (!d.espOn && d.zgOn) add("local" to "считает телефон")
        add("board" to (if (d.espOn) "имя, прошивка, оборудование" else "оборудование"))
    }
    Column(Modifier.padding(top = 4.dp)) {
        HorizontalDivider(color = HAIR)
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { k, (id, sub) ->
                    if (k == 1) Box(Modifier.width(1.dp).fillMaxHeight().background(HAIR))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).clickable { devSection.value = id }
                        .padding(start = if (k == 0) 2.dp else 12.dp, end = 6.dp, top = 14.dp, bottom = 14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(SECTIONS[id] ?: id, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(sub, fontSize = 11.5.sp, color = MUTE, maxLines = 1)
                        }
                        Text("›", fontSize = 18.sp, color = Color(0xFF4A5059))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            HorizontalDivider(color = HAIR)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun quickTests() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(2 to "Стоп", 4 to "← Поворот", 5 to "Поворот →", 6 to "Аварийка").forEach { (w, n) ->
            Text(n, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).border(1.dp, HAIR, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                    .clickable { Lighting.test(w) }.padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun nameAndKind(d: LightDevice) {
    card("Устройство") {
        var name by remember(d.id) { mutableStateOf(d.name) }
        OutlinedTextField(name, { name = it.take(30); Lighting.rename(d.id, name) },
            label = { Text("Название (впишите свою модель)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        if (d.isHelmet) {
            Text("Вид шлема", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            chips(listOf(DevKind.HELMET_FF.title, DevKind.HELMET_MX.title), if (d.kind == DevKind.HELMET_MX) 1 else 0) { i ->
                val k = if (i == 1) DevKind.HELMET_MX else DevKind.HELMET_FF
                Lighting.update(d.id) { it.copy(kind = k) }
                // сменили вид - правка сегментов начинается с шаблона этого вида (на плату уйдёт по «Записать»)
                edits[d.id] = Cfg(edits[d.id] ?: d.cfgObj().b).copy { segs = Cfg.forKind(k).segs }.b
            }
        }
    }
}

@Composable
private fun equipCard(d: LightDevice) {
    val id = d.id
    card("Оборудование") {
        checkRow("Плата CJ", if (d.isHelmet) "ESP32 с лентой по сегментам: стоп, повороты, габарит, проверка" else "ESP32 в корпусе: ходовые, габариты, педали, обод", d.espOn) { on ->
            Lighting.update(id) { it.copy(espOn = on) }
        }
        if (d.isHelmet) checkRow("Контроллер ZENGGE", "вся лента целиком: свой эффект, вспышка стопа, повороты", d.zgOn) { on ->
            Lighting.update(id) { it.copy(zgOn = on) }
        }
        var ask by remember(id) { mutableStateOf(false) }
        actionRow("Удалить устройство", "настройки на самой плате останутся", danger = true) { ask = true }
        if (ask) AlertDialog(onDismissRequest = { ask = false },
            title = { Text("Удалить «${d.name}»?") },
            text = { Text("Устройство пропадёт из списка вместе с его настройками в приложении. Настройки на самой плате останутся.") },
            confirmButton = { TextButton(onClick = { ask = false; devSection.value = ""; Lighting.remove(id); Lighting.selected.value = "" }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Отмена") } })
    }
}

/** Одна кнопка записи: видна, когда правки отличаются от того, что в плате. */
@Composable
private fun writeBar(d: LightDevice, link: EspLink?) {
    val raw = edits[d.id] ?: return
    val c = Cfg(raw)
    val board = link?.config?.value?.takeIf { Cfg.isBoardCfg(it) }
    val dirty = board == null || !Cfg(board).b.contentEquals(c.b)
    val okSegs = c.segs.isNotEmpty() && c.segs.sumOf { it.count } in 1..300
    if (!dirty) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
        .background(ACC, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (d.isHelmet) "Записать в шлем" else "Записать в колесо", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A0D00))
            Text(if (link?.linked == true) "есть изменения" else "плата не на связи", fontSize = 12.sp, color = Color(0xB31A0D00))
        }
        TextButton(onClick = { edits[d.id] = (board ?: d.cfgObj().b).copyOf() }) { Text("Отменить", color = Color(0xFF1A0D00)) }
        Button(onClick = { link?.writeConfig(c.forBoard()) }, enabled = okSegs && link?.linked == true,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A0D00), contentColor = ACC)) { Text("Записать") }
    }
}

@Composable
private fun boardCard(d: LightDevice, link: EspLink?) {
    val found = remember { mutableStateMapOf<String, String>() }
    val scanning by Lighting.scanning
    card(if (d.isHelmet) "Плата шлема" else "Плата колеса") {
        Text(if (d.espAddr.isEmpty()) "Плата не выбрана" else "Адрес: ${d.espAddr}")
        Text("Связь: " + (link?.status?.value ?: "нет"))
        if (link != null) Text("Отправлено пакетов: ${link.sent.intValue}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { found.clear(); Lighting.startScan(false) { n, a -> found[a] = n } }, modifier = Modifier.padding(top = 6.dp)) {
            Text(if (scanning) "Ищу..." else "Найти плату")
        }
        found.forEach { (a, n) ->
            Text("$n  $a", Modifier.fillMaxWidth().clickable {
                Lighting.stopScan(); found.clear()
                Lighting.update(d.id) { it.copy(espAddr = a, espOn = true) }
            }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
    if (link != null && link.linked) netCard(link)
}

/** Сеть Wi-Fi, к которой подключён телефон; пусто - не знаем (нет разрешения на место или Wi-Fi выключен). */
@Suppress("DEPRECATION")
private fun phoneSsid(ctx: android.content.Context): String = try {
    val wm = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: ""
} catch (_: Exception) { "" }

/** Имя платы в Bluetooth, Wi-Fi и обновление прошивки с cjgun.ru (characteristic net). */
@Composable
private fun netCard(link: EspLink) {
    val net = link.net.value
    card("Имя, Wi-Fi и обновление") {
        if (!link.hasNet) {
            Text("На плате старая прошивка: имя и обновление по воздуху появятся после одной прошивки по кабелю.",
                style = MaterialTheme.typography.bodySmall)
            return@card
        }
        val upd = net["upd"] ?: ""
        val wifi = net["wifi"] ?: "off"
        val busy = upd.startsWith("подключаюсь") || upd.startsWith("проверяю") || upd.startsWith("загрузка") || wifi == "подключение"
        // пока идёт обновление или подключение - читать ход раз в секунду, иначе раз в 10 с
        LaunchedEffect(link, busy) { while (true) { delay(if (busy) 1000 else 10000); link.readNet() } }

        Text("Имя в Bluetooth: ${net["name"] ?: "?"}", fontWeight = FontWeight.Bold)
        var name by remember(link) { mutableStateOf("") }
        OutlinedTextField(name, { v -> var r = v; while (r.toByteArray().size > 20) r = r.dropLast(1); name = r },
            label = { Text("Своё имя (до 20 байт)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { link.netCmd("name=" + name.trim()) }) { Text(if (name.isBlank()) "Имя по умолчанию" else "Сохранить имя") }
            OutlinedButton(onClick = { link.netCmd("reboot") }) { Text("Перезапустить") }
        }
        Text("Новое имя появится после перезапуска платы. Пустое - имя по умолчанию с концом MAC.",
            style = MaterialTheme.typography.bodySmall)

        Text("Wi-Fi: " + when (wifi) { "off" -> "выключен"; "подключение" -> "подключается"; else -> "подключён, $wifi" } +
            (net["ssid"]?.takeIf { it.isNotEmpty() }?.let { ", сеть $it" } ?: ", сеть не задана"),
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // сеть, к которой подключён телефон, - подставить; пароль Android приложениям не отдаёт
        var ssid by remember(link) { mutableStateOf(net["ssid"]?.takeIf { it.isNotEmpty() } ?: phoneSsid(ctx)) }
        var pass by remember(link) { mutableStateOf("") }
        OutlinedTextField(ssid, { ssid = it }, label = { Text("Сеть Wi-Fi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { link.netCmd("wifi=" + ssid.trim() + "\t" + pass); pass = "" }, enabled = ssid.isNotBlank()) { Text("Сохранить сеть") }
            OutlinedButton(onClick = { link.netCmd(if (wifi == "off") "wifi on" else "wifi off") }) { Text(if (wifi == "off") "Включить Wi-Fi" else "Выключить Wi-Fi") }
        }
        Text("Пароль хранится только в плате и обратно не читается. Пока Wi-Fi включён, связь шлема с колесом не работает.",
            style = MaterialTheme.typography.bodySmall)

        Text("Прошивка: ${net["ver"] ?: "?"} · ${net["board"] ?: "?"}", modifier = Modifier.padding(top = 10.dp))
        val ota = link.ota.value
        val otaBusy = ota.startsWith("проверяю") || ota.startsWith("скачиваю") || ota.startsWith("отправка") || ota.startsWith("плата проверяет")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (link.hasOta) Button(onClick = { link.otaFromSite() }, enabled = !busy && !otaBusy) { Text("Обновить по Bluetooth") }
            OutlinedButton(onClick = { link.netCmd("upd") }, enabled = !busy && !otaBusy) { Text(if (link.hasOta) "Через Wi-Fi" else "Обновить прошивку") }
        }
        if (ota.isNotEmpty()) Text("Bluetooth: $ota", color = if (ota.startsWith("ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (upd.isNotEmpty() && upd != "нет") Text("Плата: $upd",
            color = if (upd.startsWith("ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (link.hasOta) "По Bluetooth телефон сам скачает прошивку с cjgun.ru через свой интернет и передаст плате, сеть Wi-Fi не нужна. Около минуты, телефон держать рядом. Через Wi-Fi плата качает сама по сохранённой сети."
            else "Плата сама скачает прошивку с cjgun.ru по сохранённой сети и проверит её. Около полутора минут, потом перезапуск.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ---------- сегменты ----------
@Composable
private fun segCard(d: LightDevice, link: EspLink?) {
    val raw = edits[d.id] ?: return
    val c = Cfg(raw)
    if (!c.ok) { card("Сегменты") { Text("Плата со старой прошивкой (${c.u8(0)}), нужна 4 или новее. Обновите прошивку.") }; return }
    val segs = c.segs
    val roles = if (d.isHelmet) Role.forHelmet else Role.forWheel
    fun put(list: List<Seg>) { edits[d.id] = c.copy { this.segs = list }.b }
    fun put2(f: Cfg.() -> Unit) { edits[d.id] = c.copy(f).b }
    var sel by selSeg
    var colorSeg by remember(d.id) { mutableIntStateOf(-1) }
    card("Сегменты") {
        Text("Вся лента - одна цепочка от платы. Нажмите сегмент - он замигает на ${if (d.isHelmet) "шлеме" else "колесе"}.",
            style = MaterialTheme.typography.bodySmall)
        segs.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                .background(if (sel == i) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable { sel = if (sel == i) -1 else i; link?.cmd(2, if (sel == i) i else 255) }.padding(6.dp)) {
                val own = if (c.hasFx && i < Cfg.MAX_SEG) c.segRgb(i).takeIf { rgb -> rgb.any { it > 0 } } else null
                Box(Modifier.size(22.dp)
                    .background(own?.let { Color(it[0], it[1], it[2]) } ?: Color(Role.colors[s.role]), RoundedCornerShape(5.dp))
                    .then(if (own != null && own.contentEquals(SEG_DARK)) Modifier.border(1.dp, Color.Gray, RoundedCornerShape(5.dp)) else Modifier)
                    .clickable(enabled = c.hasFx) { colorSeg = if (colorSeg == i) -1 else i })
                Spacer(Modifier.width(8.dp))
                Text("${i + 1}. ${Role.names[s.role]}" + if (s.rev) " ↺" else "", Modifier.weight(1f))
                stepBtn("-10") { put(segs.toMutableList().also { it[i] = s.copy(count = max(0, s.count - 10)) }) }
                stepBtn("-") { put(segs.toMutableList().also { it[i] = s.copy(count = max(0, s.count - 1)) }) }
                Text("${s.count}", Modifier.width(36.dp), fontWeight = FontWeight.Bold)
                stepBtn("+") { put(segs.toMutableList().also { it[i] = s.copy(count = minOf(300, s.count + 1)) }) }
                stepBtn("+10") { put(segs.toMutableList().also { it[i] = s.copy(count = minOf(300, s.count + 10)) }) }
            }
        }
        if (colorSeg in segs.indices && c.hasFx) {
            Text("Свой цвет сегмента ${colorSeg + 1} (горит в габарите; стоп и повороты - как всегда)", Modifier.padding(top = 6.dp))
            val cur = c.segRgb(colorSeg).let { if (it.contentEquals(SEG_DARK)) intArrayOf(0, 0, 0) else it }
            colorPicker(cur, allowNone = true, title = "Цвет сегмента ${colorSeg + 1}") { rgb -> put2 { setSegRgb(colorSeg, if (rgb.isEmpty()) intArrayOf(0, 0, 0) else if (rgb.all { it == 0 }) SEG_DARK else rgb) } }
        } else if (c.hasFx) Text("Нажми цветной квадратик сегмента, чтобы выбрать ему свой цвет.", style = MaterialTheme.typography.bodySmall)
        val s = segs.getOrNull(sel)
        if (s != null) {
            Text("Роль сегмента ${sel + 1}", Modifier.padding(top = 6.dp))
            chips(roles.map { Role.names[it] }, roles.indexOf(s.role)) { k -> put(segs.toMutableList().also { it[sel] = s.copy(role = roles[k]) }) }
            switchRow("Обратное направление (бег поворота)", s.rev) { on -> put(segs.toMutableList().also { it[sel] = s.copy(rev = on) }) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { if (sel > 0) { put(segs.toMutableList().also { java.util.Collections.swap(it, sel, sel - 1) }); sel-- } }) { Text("↑ выше") }
                OutlinedButton(onClick = { put(segs.toMutableList().also { it.removeAt(sel) }); sel = -1 }) { Text("Удалить") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (segs.size < Cfg.MAX_SEG) OutlinedButton(onClick = { put(segs + Seg(10, roles.first())); sel = segs.size }) { Text("+ Сегмент") }
        }
        templates(d, c) { nc -> edits[d.id] = nc.b; sel = -1 }
        Text("Всего ${segs.sumOf { it.count }} точек", style = MaterialTheme.typography.bodySmall)
        c.problem()?.let { Text(it, color = Color(0xFFFFB300), modifier = Modifier.padding(top = 4.dp)) }
    }
}

/**
 * Шаблоны ленты: готовые (интеграл, мотокросс, колесо) и свои. Свой хранит сегменты (точки, роли, направления)
 * и свои цвета сегментов; лежит в настройках приложения.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun templates(d: LightDevice, c: Cfg, apply: (Cfg) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cj", android.content.Context.MODE_PRIVATE) }
    val wheel = !d.isHelmet
    var own by remember { mutableStateOf(org.json.JSONObject(prefs.getString("templates", "{}")!!)) }
    var ask by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Text("ШАБЛОНЫ", fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = MUTE, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Сохранить как шаблон", color = ACC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { name = ""; ask = true }.padding(8.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val built = if (wheel) listOf("Колесо" to Cfg.TEMPLATE_WHEEL) else listOf("Интеграл" to Cfg.TEMPLATE_FF, "Мотокросс" to Cfg.TEMPLATE_MX)
        built.forEach { (n, segs) ->
            Text(n, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.border(1.dp, HAIR, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50)).clickable { apply(c.copy { this.segs = segs }) }.padding(horizontal = 13.dp, vertical = 9.dp))
        }
        own.keys().asSequence().toList().forEach { n ->
            val o = own.getJSONObject(n)
            if (o.optBoolean("wheel") != wheel) return@forEach
            var menu by remember { mutableStateOf(false) }
            Box {
                Text(n, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = ACC,
                    modifier = Modifier.border(1.dp, Color(0x73FF9A2E), RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                        .pointerInput(n) { detectTapGestures(onTap = {
                            val src = Cfg(hexToBytes(o.getString("cfg")))
                            apply(c.copy { this.segs = src.segs; for (k in 0 until Cfg.MAX_SEG) setSegRgb(k, src.segRgb(k)) })
                        }, onLongPress = { menu = true }) }.padding(horizontal = 13.dp, vertical = 9.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Удалить шаблон") }, onClick = {
                        menu = false; own.remove(n); prefs.edit().putString("templates", own.toString()).apply(); own = org.json.JSONObject(own.toString())
                    })
                }
            }
        }
    }
    Text("Свой шаблон хранит сегменты, точки, роли, направления и свои цвета. Удалить - долгое нажатие.", fontSize = 12.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text("Свой шаблон") },
        text = { OutlinedTextField(name, { name = it.take(24) }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            own.put(name.trim(), org.json.JSONObject().put("wheel", wheel).put("cfg", c.b.hex()))
            prefs.edit().putString("templates", own.toString()).apply(); own = org.json.JSONObject(own.toString()); ask = false
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Отмена") } })
}

private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

@Composable
private fun stepBtn(t: String, f: () -> Unit) {
    TextButton(onClick = f, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.defaultMinSize(minWidth = 32.dp)) { Text(t) }
}

@Composable
private fun writeRow(d: LightDevice, link: EspLink?, c: Cfg) {
    val okSegs = c.segs.isNotEmpty() && c.segs.sumOf { it.count } in 1..300
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { link?.writeConfig(c.forBoard()) }, enabled = okSegs && link?.linked == true) {
            Text(if (d.isHelmet) "Записать в шлем" else "Записать в колесо")
        }
        OutlinedButton(onClick = { edits[d.id] = (link?.config?.value?.takeIf { Cfg.isBoardCfg(it) } ?: d.cfgObj().b).copyOf() }) { Text("Отменить") }
    }
    if (link?.linked != true) Text("Плата не на связи - запись станет доступна, когда подключится", style = MaterialTheme.typography.bodySmall)
}

// ---------- эффект ----------
private val PALETTE = listOf(intArrayOf(255, 0, 0), intArrayOf(255, 90, 0), intArrayOf(255, 160, 0), intArrayOf(255, 230, 0),
    intArrayOf(120, 255, 0), intArrayOf(0, 220, 0), intArrayOf(0, 255, 156), intArrayOf(0, 200, 160), intArrayOf(0, 200, 255),
    intArrayOf(0, 80, 255), intArrayOf(60, 0, 255), intArrayOf(122, 0, 255), intArrayOf(200, 0, 255), intArrayOf(255, 0, 160),
    intArrayOf(255, 60, 90), intArrayOf(255, 255, 255), intArrayOf(0, 0, 0))

/** Свой цвет сегмента «чёрный» - сегмент в габарите не горит; 0,0,0 у сегмента занято под «цвет роли». */
private val SEG_DARK = intArrayOf(1, 1, 1)

/** Недавние цвета (общие для всего раздела «Свет»), хранятся в настройках приложения. */
private val recentColors = mutableStateListOf<Int>()

/**
 * Цвет: строка с образцом, по нажатию - окно снизу: свой цвет квадратом и полосой оттенка, недавние, «Убрать цвет».
 * Готовой палитры нет (решение владельца). allowNone - «Убрать цвет» отдаёт пустой массив (цвет роли).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun colorPicker(cur: IntArray, allowNone: Boolean = false, title: String = "Цвет", onPick: (IntArray) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cj", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        if (recentColors.isEmpty()) prefs.getString("recent_colors", "")!!.split(',').mapNotNull { it.toIntOrNull() }.forEach { recentColors.add(it) }
    }
    var open by remember { mutableStateOf(false) }
    val none = cur.isEmpty() || (allowNone && cur.all { it == 0 })
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (none) Color(0xFF1A1F26) else Color(cur[0], cur[1], cur[2]))
            .border(1.dp, Color(0x26FFFFFF), CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (none) "как у роли" else "#%02X%02X%02X".format(cur[0], cur[1], cur[2]), fontSize = 11.5.sp, color = MUTE)
        }
        Text("›", fontSize = 20.sp, color = Color(0xFF4A5059))
    }
    HorizontalDivider(color = HAIR)
    if (!open) return
    val hsv = remember(cur.toList()) { FloatArray(3).also { if (!none) android.graphics.Color.RGBToHSV(cur[0], cur[1], cur[2], it) else { it[1] = 1f; it[2] = 1f } } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    fun rgbNow(): IntArray { val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)); return intArrayOf(c shr 16 and 255, c shr 8 and 255, c and 255) }
    fun done(rgb: IntArray?) {
        if (rgb != null && rgb.isNotEmpty()) {
            val c = (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
            recentColors.remove(c); recentColors.add(0, c); while (recentColors.size > 8) recentColors.removeAt(recentColors.lastIndex)
            prefs.edit().putString("recent_colors", recentColors.joinToString(",")).apply()
        }
        open = false
    }
    ModalBottomSheet(onDismissRequest = { done(null) }, containerColor = Color(0xFF111418)) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            val now = rgbNow()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("#%02X%02X%02X".format(now[0], now[1], now[2]), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (v > 0.6f && s < 0.5f) Color.Black else Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(now[0], now[1], now[2])).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            // квадрат: насыщенность по горизонтали, яркость по вертикали
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, Color.hsv(h, 1f, 1f))))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { o -> s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f); onPick(rgbNow()) },
                        onDrag = { ch, _ -> s = (ch.position.x / size.width).coerceIn(0f, 1f); v = 1f - (ch.position.y / size.height).coerceIn(0f, 1f) },
                        onDragEnd = { onPick(rgbNow()) })
                }
                .pointerInput(Unit) { detectTapGestures { o -> s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f); onPick(rgbNow()) } }) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = Offset(s * size.width, (1 - v) * size.height)
                    drawCircle(Color(now[0], now[1], now[2]), 9.dp.toPx(), p)
                    drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                }
            }
            // полоса оттенка
            Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(20.dp).clip(RoundedCornerShape(10.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { o -> h = (o.x / size.width).coerceIn(0f, 0.999f) * 360f },
                        onDrag = { ch, _ -> h = (ch.position.x / size.width).coerceIn(0f, 0.999f) * 360f }, onDragEnd = { onPick(rgbNow()) })
                }
                .pointerInput(Unit) { detectTapGestures { o -> h = (o.x / size.width).coerceIn(0f, 0.999f) * 360f; onPick(rgbNow()) } }) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = Offset(h / 360f * size.width, size.height / 2)
                    drawCircle(Color.hsv(h, 1f, 1f), 9.dp.toPx(), p); drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                }
            }
            if (recentColors.isNotEmpty()) {
                Text("НЕДАВНИЕ", fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = MUTE, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    recentColors.forEach { c ->
                        val rgb = intArrayOf(c shr 16 and 255, c shr 8 and 255, c and 255)
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(rgb[0], rgb[1], rgb[2])).border(1.5.dp, Color(0x40FFFFFF), CircleShape)
                            .clickable { android.graphics.Color.RGBToHSV(rgb[0], rgb[1], rgb[2], hsv); h = hsv[0]; s = hsv[1]; v = hsv[2]; onPick(rgb) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp)) {
                if (allowNone) OutlinedButton(onClick = { onPick(IntArray(0)); done(null) }) { Text("Убрать цвет") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { val r = rgbNow(); onPick(r); done(r) }, colors = ButtonDefaults.buttonColors(containerColor = ACC, contentColor = Color(0xFF1A0D00))) { Text("Готово") }
            }
        }
    }
}

@Composable
private fun fxCard(d: LightDevice, link: EspLink?) {
    val raw = edits[d.id] ?: return
    val c = Cfg(raw)
    if (!d.espOn) return
    if (!c.hasFx) {
        card("Эффект") { Text("Эффекты и свои цвета появятся после обновления прошивки платы.", style = MaterialTheme.typography.bodySmall) }
        return
    }
    fun upd(f: Cfg.() -> Unit) { edits[d.id] = c.copy(f).b }
    card("Эффект") {
        chips(Fx.names, c.fx) { i -> upd { fx = i; Fx.defaults[i]?.let { (a, b) -> fxRgb1 = a; fxRgb2 = b } } }
        Text(Fx.hints.getOrElse(c.fx) { "" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (c.fx != Fx.CLASSIC) {
            colorPicker(c.fxRgb1, title = "Цвет 1") { rgb -> if (rgb.isNotEmpty()) upd { fxRgb1 = rgb } }
            if (c.fx !in listOf(Fx.SOLID, Fx.RAINBOW, Fx.ROAD, Fx.BATTERY)) {
                colorPicker(c.fxRgb2, title = "Цвет 2") { rgb -> if (rgb.isNotEmpty()) upd { fxRgb2 = rgb } }
            }
            key(c.fx) {
                slider("Скорость бега", c.fxSpeed.toFloat(), 25f, 250f, 5f, "%.0f %%", "100 % - как задумано; меньше - спокойнее") { v -> upd { fxSpeed = v.roundToInt() } }
                slider("Яркость эффекта", c.fxBright.toFloat(), 10f, 255f, 5f, "%.0f") { v -> upd { fxBright = v.roundToInt() } }
            }
            switchRow("Перед белый", c.fxFlags and Fx.F_FRONT_WHITE != 0) { on -> upd { fxFlags = if (on) fxFlags or Fx.F_FRONT_WHITE else fxFlags and Fx.F_FRONT_WHITE.inv() } }
            switchRow("Зад всегда красный", c.fxFlags and Fx.F_BACK_RED != 0) { on -> upd { fxFlags = if (on) fxFlags or Fx.F_BACK_RED else fxFlags and Fx.F_BACK_RED.inv() } }
        }
        Text("Стоп, повороты и предупреждения колеса всегда поверх эффекта. На картинке эффект показан на 15 км/ч.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------- настройки платы: разделы ----------
@Composable
private fun cfgOf(d: LightDevice): Cfg? = edits[d.id]?.let { Cfg(it) }?.takeIf { it.ok }

@Composable
private fun stopSection(d: LightDevice) {
    val c = cfgOf(d) ?: return
    fun upd(f: Cfg.() -> Unit) { edits[d.id] = c.copy(f).b }
    key(c.b.hex()) {
        if (c.hasStopFx) card("Как моргает стоп") {
            chips(Stop.names, c.stopMode) { i -> upd { stopMode = i } }
            Text(Stop.hints.getOrElse(c.stopMode) { "" }, fontSize = 12.sp, color = MUTE)
            if (c.stopMode != Stop.STEADY) {
                Text("Что моргает", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                chips(Stop.zones, c.stopZone) { i -> upd { stopZone = i } }
                slider("Частота вспышек, Гц", c.stopHz2 / 2f, 1f, 10f, 0.5f, "%.1f") { v -> upd { stopHz2 = (v * 2).roundToInt() } }
            }
            if (c.stopMode == Stop.HARD) slider("Резкое торможение, км/ч в секунду", c.stopHard.toFloat(), 5f, 25f, 1f, "%.0f",
                "или наклон назад вдвое сильнее порога стопа") { v -> upd { stopHard = v.roundToInt() } }
        } else card("Как моргает стоп") { Text("Мигающий стоп появится после обновления прошивки платы.", fontSize = 12.sp, color = MUTE) }
        card("Когда считать стопом") { thresholdSliders(c) { f -> upd(f) } }
        card("Повороты и предупреждение") {
            slider("Поворот гаснет через, с", c.turnSec.toFloat(), 1f, 60f, 1f, "%.0f") { v -> upd { turnSec = v.roundToInt() } }
            slider("Предупреждение от PWM, %", c.warnPwm.toFloat(), 0f, 100f, 5f, "%.0f", "0 - не предупреждать") { v -> upd { warnPwm = v.roundToInt() } }
            colorPicker(c.accelRgb, title = "Цвет разгона") { rgb -> if (rgb.isNotEmpty()) upd { accelRgb = rgb } }
            if (!d.isHelmet) switchRow("Габариты вспыхивают вместе со стопом", c.flag(Cfg.F_TAIL_BRAKE)) { on -> upd { setFlag(Cfg.F_TAIL_BRAKE, on) } }
        }
    }
}

@Composable
private fun brightSection(d: LightDevice) {
    val c = cfgOf(d) ?: return
    fun upd(f: Cfg.() -> Unit) { edits[d.id] = c.copy(f).b }
    key(c.b.hex()) {
        card("Яркость") {
            slider("Габарит", c.brightTail.toFloat(), 0f, 255f, 5f, "%.0f") { v -> upd { brightTail = v.roundToInt() } }
            slider("Стоп и поворот", c.brightBrake.toFloat(), 0f, 255f, 5f, "%.0f") { v -> upd { brightBrake = v.roundToInt() } }
            slider("Предел тока ленты, мА", c.limitMa.toFloat(), 100f, 6000f, 100f, "%.0f", "от USB платы - не больше 400") { v -> upd { limitMa = v.roundToInt() } }
            if (d.isHelmet) switchRow("Белый спереди", c.flag(Cfg.F_FRONT_WHITE)) { on -> upd { setFlag(Cfg.F_FRONT_WHITE, on) } }
        }
        card("Лента") {
            Text("Тип ленты", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            chips(Cfg.STRIPS, c.stripType) { i -> upd { stripType = i } }
            switchRow("Режим разметки (каждая 10-я точка)", c.flag(Cfg.F_CALIBRATE)) { on -> upd { setFlag(Cfg.F_CALIBRATE, on) } }
        }
    }
}

@Composable
private fun pedalSection(d: LightDevice) {
    val c = cfgOf(d) ?: return
    fun upd(f: Cfg.() -> Unit) { edits[d.id] = c.copy(f).b }
    key(c.b.hex()) {
        card("Цвет педалей") {
            colorPicker(c.pedalRgb, title = "Цвет педалей") { rgb -> if (rgb.isNotEmpty()) upd { pedalRgb = rgb } }
            Text("На стоянке педали показывают заряд колеса зелёной шкалой.", fontSize = 12.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun rimSection(d: LightDevice) {
    val c = cfgOf(d) ?: return
    fun upd(f: Cfg.() -> Unit) { edits[d.id] = c.copy(f).b }
    key(c.b.hex()) {
        card("Обод (индукция)") {
            switchRow("Мигать при предупреждении", c.rim and Cfg.RIM_FLASH_WARN != 0) { on -> upd { rim = if (on) rim or Cfg.RIM_FLASH_WARN else rim and Cfg.RIM_FLASH_WARN.inv() } }
            switchRow("Мигать при торможении", c.rim and Cfg.RIM_FLASH_BRAKE != 0) { on -> upd { rim = if (on) rim or Cfg.RIM_FLASH_BRAKE else rim and Cfg.RIM_FLASH_BRAKE.inv() } }
            switchRow("Гасить на стоянке через 30 с", c.rim and Cfg.RIM_OFF_PARK != 0) { on -> upd { rim = if (on) rim or Cfg.RIM_OFF_PARK else rim and Cfg.RIM_OFF_PARK.inv() } }
            Text("Кнопка пульта К1 - главный выключатель обода, плата только гасит и мигает.", fontSize = 12.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun connSection(d: LightDevice) {
    val c = cfgOf(d) ?: return
    fun upd(f: Cfg.() -> Unit) { edits[d.id] = c.copy(f).b }
    key(c.b.hex()) {
        card("Шлем и колесо") {
            if (d.isHelmet) switchRow("Пересылать режим колесу (ESP-NOW)", c.relay and Cfg.RELAY_SEND != 0) { on -> upd { relay = if (on) relay or Cfg.RELAY_SEND else relay and Cfg.RELAY_SEND.inv() } }
            else switchRow("Брать режим у шлема, если телефон не на связи", c.relay and Cfg.RELAY_RECV != 0) { on -> upd { relay = if (on) relay or Cfg.RELAY_RECV else relay and Cfg.RELAY_RECV.inv() } }
            Text("Так колесо горит синхронно со шлемом и без своей связи с телефоном.", fontSize = 12.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun thresholdSliders(c: Cfg, upd: (Cfg.() -> Unit) -> Unit) {
    slider("Замедление, км/ч в секунду", c.brakeDecel / 10f, 0f, 15f, 0.5f, "%.1f", "0 - не учитывать") { v -> upd { brakeDecel = (v * 10).roundToInt() } }
    slider("Наклон назад, градусы", c.brakeTilt / 100f, 0f, 6f, 0.25f, "%.2f", "сверх среднего за ${c.tiltAvgS} с; 0 - не учитывать") { v -> upd { brakeTilt = (v * 100).roundToInt() } }
    slider("Разгон, км/ч в секунду", c.accel / 10f, 0f, 15f, 0.5f, "%.1f", "0 - режим выключен") { v -> upd { accel = (v * 10).roundToInt() } }
    slider("Не ниже скорости, км/ч", c.minSpeed / 10f, 0f, 20f, 1f, "%.0f") { v -> upd { minSpeed = (v * 10).roundToInt() } }
    slider("Сглаживание, мс", c.smoothMs.toFloat(), 50f, 1000f, 50f, "%.0f") { v -> upd { smoothMs = v.roundToInt() } }
}

/** Без платы: пороги хранятся в приложении, режим считает телефон. */
@Composable
private fun thresholdsLocal(d: LightDevice) {
    val c = d.cfgObj()
    key(d.cfg) {
        card("Когда стоп (считает телефон)") {
            thresholdSliders(c) { f -> Lighting.update(d.id) { it.copy(cfg = c.copy(f).b.hex()) } }
            slider("Предупреждение от PWM, %", c.warnPwm.toFloat(), 0f, 100f, 5f, "%.0f") { v -> Lighting.update(d.id) { it.copy(cfg = c.copy { warnPwm = v.roundToInt() }.b.hex()) } }
            Text("Когда появится плата, считать будет она.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- ZENGGE ----------
@Composable
private fun zenggeCard(d: LightDevice, zl: ZenggeLink?) {
    val found = remember { mutableStateMapOf<String, String>() }
    var note by remember { mutableStateOf("") }
    card("ZENGGE") {
        Text(if (d.zgAddr.isEmpty()) "Контроллер не выбран" else "Адрес: ${d.zgAddr}")
        Text("Связь: " + (zl?.status?.value ?: "нет") + (zl?.let { " · сейчас: ${it.shown.value}" } ?: ""))
        Text("В езде: " + d.zgRideLabel.ifEmpty { "не запомнено" }, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = { found.clear(); Lighting.startScan(true) { n, a -> found[a] = n } }) { Text("Найти") }
            Button(onClick = { zl?.remember { note = "Запомнено: $it" } }, enabled = zl?.linked == true) { Text("Запомнить текущий") }
        }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
        found.forEach { (a, n) ->
            Text("$n  $a", Modifier.fillMaxWidth().clickable {
                Lighting.stopScan(); found.clear()
                Lighting.update(d.id) { it.copy(zgAddr = a, zgOn = true) }
            }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
        }
        fun react(bit: Int, on: Boolean) = Lighting.update(d.id) { it.copy(zgReact = if (on) it.zgReact or bit else it.zgReact and bit.inv()) }
        switchRow("Стоп - вся лента красная", d.zgReact and ZenggeLink.R_STOP != 0) { react(ZenggeLink.R_STOP, it) }
        switchRow("Разгон - цвет разгона", d.zgReact and ZenggeLink.R_ACCEL != 0) { react(ZenggeLink.R_ACCEL, it) }
        switchRow("Поворот - мигает жёлтым", d.zgReact and ZenggeLink.R_TURN != 0) { react(ZenggeLink.R_TURN, it) }
        switchRow("Предупреждения колеса", d.zgReact and ZenggeLink.R_WARN != 0) { react(ZenggeLink.R_WARN, it) }
        Text("Сначала выставьте эффект в приложении ZENGGE и нажмите «Запомнить текущий»: после стопа и поворотов вернётся он.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ======================= картинка =======================

/** Шлем на картинках (четыре вида) или колесо сверху, точки по сегментам; цвета - кадр с платы или расчёт. */
@Composable
private fun picture(d: LightDevice, link: EspLink?, zl: ZenggeLink?, small: Boolean = false, quad: Boolean = false) {
    Lighting.frameTick.intValue
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(50); t = System.currentTimeMillis() } }
    val cfg = Cfg(edits[d.id] ?: d.cfgObj().b)
    val fxSim = remember { FxSim() }
    val frame = link?.frame
    val segs = if (d.espOn) cfg.segs else listOf(Seg(130, -1))
    val total = segs.sumOf { it.count }
    val mode = link?.mode?.intValue ?: Lighting.mode.intValue
    val turn = link?.turn?.intValue ?: Lighting.turn.intValue
    val sel = selSeg.intValue
    val segOf = IntArray(total); val jOf = IntArray(total)
    run { var i = 0; segs.forEachIndexed { si, s -> for (j in 0 until s.count) { segOf[i] = si; jOf[i] = j; i++ } } }
    val us = if (d.kind == DevKind.WHEEL) FloatArray(0) else helmetU(segs)
    // без кадра с платы эффект считается здесь же, на 15 км/ч - чтобы было видно, как он бежит
    val fxOn = d.espOn && cfg.hasFx && cfg.fx != Fx.CLASSIC && d.isHelmet && mode != Mode.BRAKE && mode != Mode.WARN
    if (fxOn) fxSim.tick(t, 15f, cfg.fxSpeed)
    fun colorAt(idx: Int): Color {
        val s = segs[segOf[idx]]
        return when {
            frame != null && frame.size >= total * 3 && d.espOn ->
                Color((frame[idx * 3].toInt() and 0xff), (frame[idx * 3 + 1].toInt() and 0xff), (frame[idx * 3 + 2].toInt() and 0xff))
            s.role == -1 -> if (d.zgOn) zgColor(zl, idx, t) else Color.Black
            fxOn && us[idx] >= 0f && !(turn != 0 && turnSide(s.role, turn)) -> {
                val u = us[idx]; val dd = if (u <= 0.5f) u * 2 else (1 - u) * 2
                fxSim.color(cfg, idx, dd, t / 1000f, 15f, 0f, 80).let { Color(it[0], it[1], it[2]) }
            }
            d.espOn && cfg.hasFx && segOf[idx] < Cfg.MAX_SEG && mode != Mode.BRAKE && cfg.segRgb(segOf[idx]).any { it > 0 } ->
                cfg.segRgb(segOf[idx]).let { Color(it[0] / 255f * 0.6f, it[1] / 255f * 0.6f, it[2] / 255f * 0.6f) }
            (s.role == Role.PEDAL_R || s.role == Role.PEDAL_L) && turn == 0 && mode != Mode.WARN ->
                cfg.pedalRgb.let { Color(it[0] / 255f, it[1] / 255f, it[2] / 255f) }
            else -> simColor(s.role, jOf[idx], s.count, mode, turn, t)
        }
    }
    if (d.kind == DevKind.WHEEL) {
        // Шмель по фото владельца (420x420), ленты по его отметкам: торцы спереди и сзади, контур педали
        val img = ImageBitmap.imageResource(com.cooper.wheellog.R.drawable.shmel_bok)
        val groups = listOf(
            listOf(Role.DRL_L, Role.DRL_R) to (floatArrayOf(98.0f,98.0f,98.0f,98.0f,98.0f,98.0f,98.0f,98.0f,98.0f) to floatArrayOf(48.0f,63.2f,78.5f,93.8f,109.0f,124.2f,139.5f,154.8f,170.0f)),
            listOf(Role.TAIL_L, Role.TAIL_R) to (floatArrayOf(339.0f,339.0f,339.0f,339.0f,339.0f,339.0f,339.0f,339.0f,339.0f) to floatArrayOf(48.0f,62.6f,77.2f,91.9f,106.5f,121.1f,135.8f,150.4f,165.0f)),
            listOf(Role.PEDAL_L, Role.PEDAL_R) to (floatArrayOf(130.0f,131.8f,133.7f,140.2f,149.7f,166.1f,182.7f,199.4f,216.1f,232.9f,249.6f,266.3f,283.0f,299.4f,312.6f,318.2f,319.1f,320.0f) to floatArrayOf(276.0f,259.4f,242.8f,227.5f,215.2f,211.8f,210.8f,210.5f,210.2f,210.0f,209.7f,209.4f,209.1f,211.4f,219.2f,234.6f,251.3f,268.0f)))
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth(if (small) 0.62f else 1f).heightIn(max = if (small) 220.dp else 360.dp).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()))
            drawRect(Color.Black.copy(alpha = 0.12f))
            val k = size.width / 420f
            for ((roles, xy) in groups) {
                // точки этой группы берут цвет у сегментов с её ролями, по порядку
                val idxs = (0 until total).filter { segs[segOf[it]].role in roles }
                val n = xy.first.size
                for (q in 0 until n) {
                    val c = if (idxs.isEmpty()) Color(0xFF262F3E) else colorAt(idxs[q * idxs.size / n])
                    led(Offset(xy.first[q] * k, xy.second[q] * k), c, 0.7f)
                }
            }
        }
        }
        return
    }
    val views = HelmetViews.forHelmet(d.kind == DevKind.HELMET_MX)
    @Composable
    fun one(vi: Int, mod: Modifier) {
        val v = views[vi]
        val img = ImageBitmap.imageResource(v.res)
        Canvas(mod.aspectRatio(v.w.toFloat() / v.h).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            val k = size.width / v.w
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()))
            drawRect(Color.Black.copy(alpha = 0.35f))
            for (idx in 0 until total) {
                val u = us[idx]
                if (u < 0f) continue
                for (ln in v.lines) ln.at(u)?.let { (x, y) -> led(Offset(x * k, y * k), colorAt(idx), if (segOf[idx] == sel) 1.6f else 1f) }
            }
        }
    }
    if (!quad) {
        var vi by heroView
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            one(vi, Modifier.fillMaxWidth(if (small) 0.62f else 1f).heightIn(max = if (small) 200.dp else 320.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HelmetViews.titles.forEachIndexed { i, s ->
                    val on = i == vi
                    Column(Modifier.clickable { vi = i }.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color(0xFFEEF0F2) else MUTE)
                        Box(Modifier.padding(top = 4.dp).height(2.dp).width(28.dp).background(if (on) ACC else Color.Transparent))
                    }
                }
            }
        }
        return
    }
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in 0..1) {
                val v = views[row * 2 + c]
                val img = ImageBitmap.imageResource(v.res)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(v.w.toFloat() / v.h).background(Color.Black, RoundedCornerShape(8.dp))) {
                        val k = size.width / v.w
                        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()))
                        drawRect(Color.Black.copy(alpha = 0.35f))  // картинка темнее - точки ленты виднее
                        for (idx in 0 until total) {
                            val u = us[idx]
                            if (u < 0f) continue
                            for (ln in v.lines) ln.at(u)?.let { (x, y) -> led(Offset(x * k, y * k), colorAt(idx), if (segOf[idx] == sel) 1.6f else 1f) }
                        }
                    }
                    Text(HelmetViews.titles[row * 2 + c], style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun DrawScope.led(p: Offset, c: Color, k: Float) {
    val m = max(c.red, max(c.green, c.blue))
    val r = size.minDimension / 75f * k
    if (m > 0.02f) {
        drawCircle(c.copy(alpha = 0.3f), r * 2.2f, p)
        val s = (1f / m) * minOf(1f, 0.4f + m)
        drawCircle(Color(minOf(1f, c.red * s), minOf(1f, c.green * s), minOf(1f, c.blue * s)), r, p)
    } else drawCircle(Color(0xFF262F3E), r, p)
}

private val RING = listOf(Role.FRONT_R, Role.SIDE_R, Role.BACK, Role.SIDE_L, Role.FRONT_L)

/**
 * Место каждой точки на кольце ленты шлема, u: 0 перед по центру, 0.25 правый бок, 0.5 зад, 0.75 левый бок
 * (как HelmetViews). Кольцо - роли по кругу пропорционально числу точек, у подбородка небольшой зазор.
 * Стоп-полоса - сзади, козырёк и выключенные точки не рисуются (-1).
 */
private fun helmetU(segs: List<Seg>): FloatArray {
    val out = FloatArray(segs.sumOf { it.count }) { -1f }
    val gap = 14f; val span = 360f - gap
    val ringRoles = RING + -1
    val tot = segs.filter { it.role in ringRoles }.sumOf { it.count }.coerceAtLeast(1)
    val starts = segs.runningFold(0) { a, s -> a + s.count }
    var k = 0
    for (role in listOf(-1) + RING) segs.forEachIndexed { si, s ->
        if (s.role != role) return@forEachIndexed
        // rev - точки идут сзади вперёд (бег поворота). Кольцо слева идёт сзади вперёд, справа спереди
        // назад: левый сегмент с rev по кольцу прямой, без rev - обратный. Как ringLayout() в прошивке.
        val flip = s.rev != (role == Role.SIDE_L || role == Role.FRONT_L)
        for (i in 0 until s.count) {
            val q = if (flip) s.count - 1 - i else i
            out[starts[si] + i] = (gap / 2 + span * (k + q + 0.5f) / tot) / 360f
        }
        k += s.count
    }
    segs.forEachIndexed { si, s ->
        if (s.role == Role.STOP) for (i in 0 until s.count) out[starts[si] + i] = 0.45f + 0.1f * (if (s.count == 1) 0.5f else i / (s.count - 1f))
    }
    return out
}

/** Колесо сверху: перед вверху; ходовые по углам спереди, габариты сзади, педали по бокам. */
private fun wheelLayout(segs: List<Seg>, sz: Size): List<Offset> {
    val w = sz.width; val h = sz.height
    val starts = segs.runningFold(0) { a, s -> a + s.count }
    val out = Array(segs.sumOf { it.count }) { Offset(-100f, -100f) }
    segs.forEachIndexed { si, s ->
        for (i in 0 until s.count) {
            val f = if (s.count == 1) 0.5f else i / (s.count - 1f)
            val p = when (s.role) {
                Role.DRL_R -> Offset(w * 0.56f + w * 0.12f * f, h * 0.12f)
                Role.DRL_L -> Offset(w * 0.44f - w * 0.12f * f, h * 0.12f)
                Role.TAIL_R -> Offset(w * 0.56f + w * 0.12f * f, h * 0.88f)
                Role.TAIL_L -> Offset(w * 0.44f - w * 0.12f * f, h * 0.88f)
                Role.PEDAL_R -> Offset(w * 0.80f, h * 0.40f + h * 0.2f * f)
                Role.PEDAL_L -> Offset(w * 0.20f, h * 0.40f + h * 0.2f * f)
                else -> Offset(-100f, -100f)
            }
            out[starts[si] + i] = p
        }
    }
    return out.toList()
}

private fun DrawScope.drawWheelBody(sz: Size) {
    val w = sz.width; val h = sz.height
    drawRoundRect(Color(0xFF2A3446), Offset(w * 0.3f, h * 0.1f), Size(w * 0.4f, h * 0.8f), androidx.compose.ui.geometry.CornerRadius(24f))
    drawRoundRect(Color(0xFF0B0E14), Offset(w * 0.43f, h * 0.18f), Size(w * 0.14f, h * 0.64f), androidx.compose.ui.geometry.CornerRadius(20f))
    drawRect(Color(0xFF3A4862), Offset(w * 0.2f, h * 0.48f), Size(w * 0.1f, h * 0.04f))
    drawRect(Color(0xFF3A4862), Offset(w * 0.7f, h * 0.48f), Size(w * 0.1f, h * 0.04f))
}

/** Колесо для списка (шлемы в списке - картинкой). */
private fun DrawScope.silhouette(kind: DevKind, c: Color) {
    val sx = size.width / 120f; val sy = size.height / 100f
    fun P(x: Float, y: Float) = Offset(x * sx, y * sy)
    drawRoundRect(c, P(28f, 8f), Size(64 * sx, 60 * sy), androidx.compose.ui.geometry.CornerRadius(10f))
    drawCircle(Color(0xFF0B0E14), 30 * sy, P(60f, 66f))
    drawCircle(c, 30 * sy, P(60f, 66f), style = Stroke(5f))
    drawRect(Color(0xFF9FB0C8), P(48f, 2f), Size(24 * sx, 8 * sy))
}

/** Поворот идёт по этой стороне - эффект не рисуем, рисует simColor. */
private fun turnSide(role: Int, turn: Int) = turn == 3 || (turn == 2 && Role.isRight(role)) || (turn == 1 && Role.isLeft(role))

/** Цвет точки без кадра с платы: та же логика, что в прошивке, упрощённо. */
private fun simColor(role: Int, j: Int, n: Int, mode: Int, turn: Int, t: Long): Color {
    val tail = Color(0.22f, 0f, 0f); val red = Color.Red; val amber = Color(1f, 0.55f, 0f); val white = Color(0.3f, 0.3f, 0.3f)
    val right = Role.isRight(role); val left = Role.isLeft(role)
    val turnHere = turn == 3 || (turn == 2 && right) || (turn == 1 && left)
    val blink = t % 800 < 500
    if (mode == Mode.WARN && role !in listOf(Role.FRONT_R, Role.FRONT_L, Role.DRL_R, Role.DRL_L)) return if ((t / 125) % 2 == 0L) Color(1f, 0.6f, 0f) else Color(1f, 0.12f, 0f)
    if (turnHere) return when (role) {
        Role.PEAK_R, Role.PEAK_L, Role.PEDAL_R, Role.PEDAL_L -> if (blink) amber else Color.Black
        else -> if (blink && j < n * ((t % 800) + 50) / 500) amber else Color.Black
    }
    return when (role) {
        Role.FRONT_R, Role.FRONT_L, Role.PEAK_R, Role.PEAK_L, Role.DRL_R, Role.DRL_L -> white
        Role.SIDE_R, Role.SIDE_L -> if (mode == Mode.BRAKE) red else tail
        Role.TAIL_R, Role.TAIL_L -> tail
        Role.BACK, Role.STOP -> when (mode) { Mode.BRAKE -> red; Mode.ACCEL -> Color(0f, 0.78f, 0.63f); else -> tail }
        Role.PEDAL_R, Role.PEDAL_L -> Color(1f, 0.35f, 0f)
        else -> Color.Black
    }
}

/** Лента ZENGGE целиком: цвет, который выставили, или радуга - свой эффект контроллера. */
private fun zgColor(zl: ZenggeLink?, i: Int, t: Long): Color {
    val rgb = zl?.shownRgb ?: -1
    if (rgb >= 0) return Color((rgb shr 16 and 0xff) / 255f, (rgb shr 8 and 0xff) / 255f, (rgb and 0xff) / 255f)
    val h = ((i * 8 + t / 11) % 360).toFloat()
    return Color.hsv(h, 1f, 0.7f)
}

// ======================= общие элементы (новый дизайн) =======================

private val HAIR = Color(0x12FFFFFF)
private val MUTE = Color(0xFF7D848E)
private val ACC = Color(0xFFFF9A2E)

/** Секция без рамки: подпись мелкими буквами и тонкая линия сверху. */
@Composable
private fun card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        HorizontalDivider(color = HAIR)
        Text(title.uppercase(), fontSize = 10.5.sp, letterSpacing = 1.6.sp, color = MUTE, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun checkRow(name: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, fontSize = 12.sp, color = MUTE)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
    HorizontalDivider(color = HAIR)
}

@Composable
private fun slider(name: String, value: Float, min: Float, max: Float, step: Float, fmt: String,
                   hint: String = "", onDone: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(value.coerceIn(min, max)) }
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, Modifier.weight(1f), fontSize = 14.sp)
            Text(fmt.format(v), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        // без отметок шагов: на 300 точках они сливаются в полосу; шаг даёт округление
        Slider(value = v, onValueChange = { v = (Math.round(it / step) * step).coerceIn(min, max) },
            onValueChangeFinished = { onDone(v) }, valueRange = min..maxOf(max, min + step),
            colors = SliderDefaults.colors(thumbColor = ACC, activeTrackColor = ACC, inactiveTrackColor = Color(0xFF2A2F36)))
        if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = MUTE)
    }
    HorizontalDivider(color = HAIR)
}

@Composable
private fun switchRow(name: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 6.dp)) {
        Text(name, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = on, onCheckedChange = onChange)
    }
    HorizontalDivider(color = HAIR)
}

/** Выбор одного из нескольких: кнопки-сегменты в одной рамке. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun chips(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, HAIR, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, s ->
            val on = i == selected
            Text(s, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) ACC else MUTE,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) Color(0x24FF9A2E) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 11.dp, vertical = 8.dp))
        }
    }
}

/** Строка-действие со стрелкой; danger - красная. */
@Composable
private fun actionRow(name: String, sub: String = "", danger: Boolean = false, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (danger) Color(0xFFFF6B61) else Color(0xFFEEF0F2))
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.5.sp, color = MUTE)
        }
        Text("›", fontSize = 20.sp, color = Color(0xFF4A5059))
    }
    HorizontalDivider(color = HAIR)
}
