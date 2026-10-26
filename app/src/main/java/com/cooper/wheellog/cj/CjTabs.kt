package com.cooper.wheellog.cj

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cooper.wheellog.settings.alarmScreen
import com.cooper.wheellog.settings.lightDeviceScreen
import com.cooper.wheellog.settings.lightingScreen
import com.cooper.wheellog.settings.logScreen
import com.cooper.wheellog.settings.wheelScreen

/** Заголовок раздела первого уровня: крупно и тонко, справа одно действие текстом. */
@Composable
fun CjHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        if (action != null) Text(
            "${action.uppercase()} ›", color = Cj.acc, fontSize = 11.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.clickable(role = Role.Button) { onAction() }.padding(12.dp),
        )
    }
}

/** Подзаголовок второго уровня со стрелкой назад. */
@Composable
fun CjSubHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", fontSize = 26.sp, color = Cj.mute, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Назад") { onBack() }.padding(horizontal = 12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/** Вкладки кроме «Езды»: «Езда» пока остаётся прежним экраном WheelLog (часть 2 дизайна). */
@Composable
fun CjTabContent(tab: CjTab) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val leftW = maxWidth * 0.42f
        val content: @Composable () -> Unit = {
            when (tab) {
                CjTab.LIGHT -> LightTab(wide)
                CjTab.TRIPS -> TripsTab()
                CjTab.WHEEL -> WheelTab()
                CjTab.RIDE -> {}
            }
        }
        // Fold: вкладки первого уровня - слева прибор, справа раздел; разделы устройства - на весь экран
        // одна и та же структура при любом раскладе: иначе NavHost пересоздаётся и раздел сбрасывается к списку
        Row(Modifier.fillMaxSize()) {
            if (wide && !fullScreen.value) {
                Box(Modifier.width(leftW)) { RideScreen() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Cj.hair))
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** Раздел устройства открыт на Fold - на весь экран, без прибора. */
val fullScreen = androidx.compose.runtime.derivedStateOf { (CjState.tab.value == CjTab.LIGHT && lightDev.value && com.cooper.wheellog.settings.devSection.value.isNotEmpty()) || (CjState.tab.value == CjTab.TRIPS && (openTrip.value != null || tripsSub.value)) || (CjState.tab.value == CjTab.WHEEL && wheelSub.value) }
// второй уровень «Поездок» («Запись») и «Колеса» (настройки, предупреждения) - на Fold тоже на весь экран
private val tripsSub = androidx.compose.runtime.mutableStateOf(false)
private val wheelSub = androidx.compose.runtime.mutableStateOf(false)
private val lightDev = androidx.compose.runtime.mutableStateOf(false)

@Composable
private fun LightTab(wide: Boolean = false) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    BackHandler(enabled = entry?.destination?.route == "dev") { nav.popBackStack() }
    lightDev.value = entry?.destination?.route == "dev"
    NavHost(nav, startDestination = "list") {
        composable("list") { lightingScreen(onOpen = { nav.navigate("dev") }, forceNarrow = true) }
        composable("dev") { Column { if (com.cooper.wheellog.settings.devSection.value.isEmpty()) CjSubHeader("Свет") { nav.popBackStack() }; lightDeviceScreen(full = wide) } }
    }
}

@Composable
private fun TripsTab() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val trip = openTrip.value
    BackHandler(enabled = trip != null) { openTrip.value = null }
    BackHandler(enabled = trip == null && entry?.destination?.route == "log") { nav.popBackStack() }
    tripsSub.value = entry?.destination?.route == "log"
    if (trip != null) { TripView(trip) { openTrip.value = null }; return }
    NavHost(nav, startDestination = "list") {
        composable("list") { TripsList(onRecord = { nav.navigate("log") }) }
        composable("log") { Column { CjSubHeader("Запись поездок") { nav.popBackStack() }; logScreen() } }
    }
}

@Composable
private fun WheelTab() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    BackHandler(enabled = entry?.destination?.route != "tele") { nav.popBackStack() }
    wheelSub.value = entry != null && entry?.destination?.route != "tele"
    NavHost(nav, startDestination = "tele") {
        composable("tele") { WheelTelemetry(onSettings = { nav.navigate("set") }) }
        composable("set") { Column { CjSubHeader("Настройки колеса") { nav.popBackStack() }
            WheelSettingsHead(onAlarms = { nav.navigate("alarms") })
            wheelScreen() } }
        composable("alarms") { Column { CjSubHeader("Предупреждения") { nav.popBackStack() }; alarmScreen() } }
    }
}

/** Верх «Настроек колеса»: колесо и MAC, пункт «Предупреждения» со сводкой порогов. */
@Composable
private fun WheelSettingsHead(onAlarms: () -> Unit, appConfig: com.cooper.wheellog.AppConfig = org.koin.compose.koinInject()) {
    val wd = com.cooper.wheellog.WheelData.getInstance()
    val line = listOf(CjState.wheelName.value, wd?.model ?: "", wd?.wheelType?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "", appConfig.lastMac.trimEnd('_'))
        .filter { it.isNotEmpty() && it != "Unknown" }.joinToString(" · ")
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (CjState.connected.value) Cj.green else Cj.dim))
        Text("  " + line.ifEmpty { "колесо не подключалось" }, fontSize = 12.5.sp, color = Cj.mute)
    }
    val sum = if (!appConfig.alarmsEnabled) "выключены"
        else (if (appConfig.pwmBasedAlarms) "по PWM ${appConfig.alarmFactor1}-${appConfig.alarmFactor2}%" else "по скорости ${appConfig.alarm1Speed} км/ч") +
            (if (appConfig.alarmCurrent > 0) " · ток ${appConfig.alarmCurrent} А" else "") +
            (if (appConfig.alarmTemperature > 0) " · ${appConfig.alarmTemperature} °C" else "")
    com.cooper.wheellog.settings.clickablePref(name = "Предупреждения", desc = sum, onClick = onAlarms)
}
