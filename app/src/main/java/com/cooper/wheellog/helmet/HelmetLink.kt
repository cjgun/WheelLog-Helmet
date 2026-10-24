package com.cooper.wheellog.helmet

import android.content.Context
import com.cooper.wheellog.WheelData

/**
 * Прежняя точка входа модуля «Шлем»: её зовут WheelLog.kt, WheelData и MainActivity.
 * Теперь всё делает Lighting (шлемы, колесо, ZENGGE); здесь только переходник.
 */
object HelmetLink {
    fun init(context: Context) = Lighting.init(context)

    @JvmStatic
    fun onWheelData(wd: WheelData) = Lighting.onWheelData(wd)

    var enabled: Boolean
        get() = Lighting.enabled.value
        set(v) = Lighting.setEnabled(v)

    /** Хоть одна плата или ZENGGE на связи. */
    val isLinked get() = Lighting.isLinked

    /** Не пусто, если хоть одно устройство выбрано (для значка в верхней строке). */
    val address: String get() = if (Lighting.hasHardware) "есть" else ""

    var onStatusChange: (() -> Unit)?
        get() = Lighting.onStatusChange
        set(v) { Lighting.onStatusChange = v }
}
