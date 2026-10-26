package com.cooper.wheellog.cj

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cooper.wheellog.compose.TripsScreen
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
    Box(Modifier.fillMaxSize()) {
        when (tab) {
            CjTab.LIGHT -> LightTab()
            CjTab.TRIPS -> TripsTab()
            CjTab.WHEEL -> WheelTab()
            CjTab.RIDE -> {}
        }
    }
}

@Composable
private fun LightTab() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    BackHandler(enabled = entry?.destination?.route == "dev") { nav.popBackStack() }
    NavHost(nav, startDestination = "list") {
        composable("list") { lightingScreen(onOpen = { nav.navigate("dev") }) }
        composable("dev") { Column { if (com.cooper.wheellog.settings.devSection.value.isEmpty()) CjSubHeader("Свет") { nav.popBackStack() }; lightDeviceScreen() } }
    }
}

@Composable
private fun TripsTab() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    BackHandler(enabled = entry?.destination?.route == "log") { nav.popBackStack() }
    NavHost(nav, startDestination = "list") {
        composable("list") { Column { CjHeader("Поездки", "Запись") { nav.navigate("log") }; TripsScreen() } }
        composable("log") { Column { CjSubHeader("Запись поездок") { nav.popBackStack() }; logScreen() } }
    }
}

@Composable
private fun WheelTab() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    BackHandler(enabled = entry?.destination?.route == "alarms") { nav.popBackStack() }
    NavHost(nav, startDestination = "wheel") {
        composable("wheel") { Column { CjHeader("Колесо", "Предупреждения") { nav.navigate("alarms") }; wheelScreen() } }
        composable("alarms") { Column { CjSubHeader("Предупреждения") { nav.popBackStack() }; alarmScreen() } }
    }
}
