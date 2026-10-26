package com.cooper.wheellog.cj

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

/** Нижние вкладки нового дизайна. */
enum class CjTab(val title: String) { RIDE("Езда"), LIGHT("Свет"), TRIPS("Поездки"), WHEEL("Колесо") }

/** Состояние оболочки, общее для MainActivity и Compose. */
object CjState {
    val tab = mutableStateOf(CjTab.RIDE)
    val connected = mutableStateOf(false)
    val searching = mutableStateOf(false)
    val wheelName = mutableStateOf("")
    val wheelModel = mutableStateOf("")
}

/**
 * Шапка: слева связь с колесом (значок Bluetooth и имя), справа свет шлемов, свет колеса и настройки.
 * Действия - колбэками MainActivity, чтобы не дублировать её логику подключения.
 */
@Composable
fun CjTopBar(
    helmetsOn: Boolean, wheelOn: Boolean,
    onConnect: () -> Unit, onHelmets: () -> Unit, onWheel: () -> Unit, onSettings: () -> Unit,
) {
    val conn = CjState.connected.value
    val search = CjState.searching.value
    Row(
        Modifier.fillMaxWidth().background(Cj.bg).padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (conn) "Отключить колесо" else "Подключить колесо") { onConnect() }
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) { bluetooth(if (conn) Cj.ink else Cj.dim) }
                Box(
                    Modifier.offset(9.dp, (-9).dp).size(7.dp).clip(CircleShape)
                        .background(if (conn) Cj.green else if (search) Cj.warm else Color(0xFF3A3F46))
                )
            }
            Column {
                val name = CjState.wheelName.value.ifEmpty { "Колесо" }
                Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 1,
                    color = if (conn) Cj.ink else Cj.mute)
                Text(
                    if (conn) CjState.wheelModel.value.ifEmpty { "на связи" } else if (search) "поиск..." else "не подключено",
                    fontSize = 11.5.sp, color = Cj.mute, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconToggle(helmetsOn, "Свет шлемов", onHelmets) { on -> helmetIcon(on) }
        IconToggle(wheelOn, "Свет колеса", onWheel) { on -> wheelIcon(on) }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Настройки") { onSettings() }
                .semantics { contentDescription = "Настройки" },
            contentAlignment = Alignment.Center,
        ) { Canvas(Modifier.size(22.dp)) { gear(Cj.mute) } }
    }
}

@Composable
private fun IconToggle(on: Boolean, label: String, onClick: () -> Unit, draw: DrawScope.(Boolean) -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Switch, onClickLabel = label) { onClick() }
            .semantics { contentDescription = "$label: " + if (on) "включён" else "выключен" },
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(28.dp)) { draw(on) } }
}

/** Нижние вкладки. */
@Composable
fun CjBottomBar(onTab: (CjTab) -> Unit) {
    val cur = CjState.tab.value
    Column(Modifier.fillMaxWidth().background(Cj.bg)) {
        HorizontalDivider(color = Cj.hair)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            CjTab.entries.forEach { t ->
                val on = t == cur
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab) { onTab(t) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.size(22.dp)) { tabIcon(t, if (on) Cj.ink else Cj.dim) }
                    Spacer(Modifier.height(3.dp))
                    Text(t.title.uppercase(), fontSize = 10.5.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.SemiBold,
                        color = if (on) Cj.ink else Cj.dim)
                }
            }
        }
    }
}

// ---------- значки (рисуются, как в макете) ----------
private fun DrawScope.u(x: Float, y: Float, s: Float = 24f) = androidx.compose.ui.geometry.Offset(size.width * x / s, size.height * y / s)
private fun DrawScope.st(w: Float = 1.6f) = Stroke(width = size.width * w / 24f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.bluetooth(c: Color) {
    val p = Path().apply { moveTo(u(7f, 7.5f).x, u(7f, 7.5f).y); lineTo(u(17f, 16.5f).x, u(17f, 16.5f).y); lineTo(u(12f, 21f).x, u(12f, 21f).y)
        lineTo(u(12f, 3f).x, u(12f, 3f).y); lineTo(u(17f, 7.5f).x, u(17f, 7.5f).y); lineTo(u(7f, 16.5f).x, u(7f, 16.5f).y) }
    drawPath(p, c, style = st())
}

private fun DrawScope.gear(c: Color) {
    val pts = listOf(10.3f to 3.5f, 13.7f to 3.5f, 14.2f to 5.8f, 15.8f to 6.7f, 18f to 5.9f, 19.7f to 8.8f, 18f to 10.4f, 18f to 12.3f,
        19.7f to 13.9f, 18f to 16.8f, 15.8f to 16f, 14.2f to 16.9f, 13.7f to 19.2f, 10.3f to 19.2f, 9.8f to 16.9f, 8.2f to 16f,
        6f to 16.8f, 4.3f to 13.9f, 6f to 12.3f, 6f to 10.4f, 4.3f to 8.8f, 6f to 5.9f, 8.2f to 6.7f, 9.8f to 5.8f)
    val p = Path().apply { pts.forEachIndexed { i, (x, y) -> val o = u(x, y + 0.6f); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
    drawPath(p, c, style = st(1.4f))
    drawCircle(c, radius = size.width * 2.8f / 24f, center = u(12f, 12f), style = st(1.4f))
}

private fun DrawScope.helmetIcon(on: Boolean) {
    val c = if (on) Color(0xFFC9CED5) else Cj.dim
    val s = 32f
    val p = Path().apply {
        moveTo(u(4.5f, 19.5f, s).x, u(4.5f, 19.5f, s).y)
        cubicTo(u(4.5f, 11.2f, s).x, u(4.5f, 11.2f, s).y, u(9.7f, 5.5f, s).x, u(9.7f, 5.5f, s).y, u(16.6f, 5.5f, s).x, u(16.6f, 5.5f, s).y)
        cubicTo(u(22.9f, 5.5f, s).x, u(22.9f, 5.5f, s).y, u(27.5f, 10.2f, s).x, u(27.5f, 10.2f, s).y, u(27.5f, 16.5f, s).x, u(27.5f, 16.5f, s).y)
        lineTo(u(27.5f, 19.7f, s).x, u(27.5f, 19.7f, s).y); lineTo(u(26f, 22f, s).x, u(26f, 22f, s).y)
        lineTo(u(6.8f, 22f, s).x, u(6.8f, 22f, s).y); lineTo(u(4.5f, 19.7f, s).x, u(4.5f, 19.7f, s).y); close()
    }
    drawPath(p, c, style = Stroke(size.width * 1.5f / s, join = StrokeJoin.Round))
    val v = Path().apply { moveTo(u(15.2f, 11.2f, s).x, u(15.2f, 11.2f, s).y); lineTo(u(27f, 12.8f, s).x, u(27f, 12.8f, s).y)
        lineTo(u(27.4f, 16.4f, s).x, u(27.4f, 16.4f, s).y); lineTo(u(16.7f, 16.4f, s).x, u(16.7f, 16.4f, s).y); close() }
    drawPath(v, c.copy(alpha = 0.18f)); drawPath(v, c, style = Stroke(size.width * 1.3f / s, join = StrokeJoin.Round))
    led(on, u(6.5f, 25f, s), u(25.5f, 25f, s), s)
}

private fun DrawScope.wheelIcon(on: Boolean) {
    val c = if (on) Color(0xFFC9CED5) else Cj.dim
    val s = 32f
    drawLine(c, u(13f, 3.5f, s), u(19f, 3.5f, s), size.width * 1.6f / s, StrokeCap.Round)
    drawRoundRect(c, u(9.5f, 5f, s), androidx.compose.ui.geometry.Size(size.width * 13f / s, size.height * 20f / s),
        androidx.compose.ui.geometry.CornerRadius(size.width * 4.5f / s), style = Stroke(size.width * 1.5f / s))
    drawCircle(c, size.width * 1.9f / s, u(16f, 10f, s))
    drawRoundRect(c.copy(alpha = 0.9f), u(14f, 20f, s), androidx.compose.ui.geometry.Size(size.width * 4f / s, size.height * 9.5f / s),
        androidx.compose.ui.geometry.CornerRadius(size.width * 2f / s))
    drawLine(c, u(4.5f, 18f, s), u(9.5f, 18f, s), size.width * 1.8f / s, StrokeCap.Round)
    drawLine(c, u(22.5f, 18f, s), u(27.5f, 18f, s), size.width * 1.8f / s, StrokeCap.Round)
    led(on, u(9.5f, 11f, s), u(9.5f, 18f, s), s); led(on, u(22.5f, 11f, s), u(22.5f, 18f, s), s)
}

/** Полоска ленты на значке: светится, когда свет включён. */
private fun DrawScope.led(on: Boolean, a: androidx.compose.ui.geometry.Offset, b: androidx.compose.ui.geometry.Offset, s: Float) {
    if (on) drawLine(Cj.acc.copy(alpha = 0.35f), a, b, size.width * 6f / s, StrokeCap.Round)
    drawLine(if (on) Cj.acc else Color(0xFF3A3F46), a, b, size.width * 2.2f / s, StrokeCap.Round)
}

private fun DrawScope.tabIcon(t: CjTab, c: Color) {
    when (t) {
        CjTab.RIDE -> {
            drawArc(c, 180f, 180f, false, u(4.5f, 8.5f), androidx.compose.ui.geometry.Size(size.width * 15f / 24f, size.height * 15f / 24f), style = st())
            drawLine(c, u(12f, 16f), u(15.5f, 11.5f), size.width * 1.6f / 24f, StrokeCap.Round)
        }
        CjTab.LIGHT -> {
            drawArc(c, 180f, 180f, false, u(4f, 6f), androidx.compose.ui.geometry.Size(size.width * 16f / 24f, size.height * 19f / 24f), style = st())
            drawLine(c, u(3.5f, 15.5f), u(20.5f, 15.5f), size.width * 1.6f / 24f, StrokeCap.Round)
        }
        CjTab.TRIPS -> {
            val p = Path().apply { moveTo(u(5f, 19f).x, u(5f, 19f).y)
                cubicTo(u(8f, 12f).x, u(8f, 12f).y, u(10f, 21f).x, u(10f, 21f).y, u(13f, 13f).x, u(13f, 13f).y)
                cubicTo(u(16f, 5f).x, u(16f, 5f).y, u(17f, 7f).x, u(17f, 7f).y, u(19f, 6f).x, u(19f, 6f).y) }
            drawPath(p, c, style = st())
        }
        CjTab.WHEEL -> {
            drawCircle(c, size.width * 7.5f / 24f, u(12f, 12f), style = st())
            drawCircle(c, size.width * 2.5f / 24f, u(12f, 12f), style = st())
        }
    }
}
