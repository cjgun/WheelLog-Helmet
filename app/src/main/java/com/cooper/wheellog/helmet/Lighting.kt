package com.cooper.wheellog.helmet

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.cooper.wheellog.WheelData
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class DevKind(val title: String) { HELMET_FF("Интеграл"), HELMET_MX("Мотокросс"), WHEEL("Колесо") }

/** Режимы, как enum Mode в прошивке. */
object Mode {
    const val NOSIGNAL = 0; const val NORM = 1; const val BRAKE = 2; const val ACCEL = 3; const val WARN = 4; const val PARK = 5
    val names = mapOf(-1 to "нет соединения", NOSIGNAL to "нет данных колеса", NORM to "норма", BRAKE to "стоп",
        ACCEL to "разгон", WARN to "предупреждение", PARK to "стоянка")
}

/** Шлем или колесо и что на нём стоит. Хранится в настройках приложения JSON-ом. */
data class LightDevice(
    val id: String,
    val kind: DevKind,
    val name: String,
    val espOn: Boolean = false,
    val espAddr: String = "",
    val zgOn: Boolean = false,
    val zgAddr: String = "",
    val zgReact: Int = ZenggeLink.R_STOP or ZenggeLink.R_TURN or ZenggeLink.R_WARN,
    val zgRide: String = "",        // команда возврата эффекта ZENGGE, hex
    val zgRideLabel: String = "",
    val cfg: String = "",           // config платы v4 hex: копия с платы или пороги для расчёта на телефоне
) {
    fun cfgObj(): Cfg = if (cfg.isEmpty()) Cfg.forKind(kind) else Cfg(hex(cfg))
    val isHelmet get() = kind != DevKind.WHEEL

    fun toJson() = JSONObject().put("id", id).put("kind", kind.name).put("name", name).put("espOn", espOn).put("espAddr", espAddr)
        .put("zgOn", zgOn).put("zgAddr", zgAddr).put("zgReact", zgReact).put("zgRide", zgRide).put("zgRideLabel", zgRideLabel).put("cfg", cfg)

    companion object {
        fun fromJson(o: JSONObject) = LightDevice(o.getString("id"), DevKind.valueOf(o.getString("kind")), o.optString("name"),
            o.optBoolean("espOn"), o.optString("espAddr"), o.optBoolean("zgOn"), o.optString("zgAddr"),
            o.optInt("zgReact", ZenggeLink.R_STOP or ZenggeLink.R_TURN or ZenggeLink.R_WARN), o.optString("zgRide"), o.optString("zgRideLabel"), o.optString("cfg"))
    }
}

fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/**
 * Подсветка: шлемы и колесо. Держит список устройств, связи с платами CJ и контроллерами ZENGGE,
 * раздаёт данные колеса и режим. Колесу ничего не пишет - только читает WheelData.
 * Протокол плат: /root/helmet/README.md на NL.
 */
@SuppressLint("MissingPermission")
object Lighting {
    val SERVICE: UUID = UUID.fromString("e5c7e8c9-d675-4a18-81ca-fe8e20469649")

    val devices = mutableStateListOf<LightDevice>()
    val activeHelmet = mutableStateOf("")
    val enabled = mutableStateOf(true)
    /** Свет шлемов и колеса по отдельности (значки в шапке главного экрана). */
    val helmetsOn = mutableStateOf(true)
    val wheelOn = mutableStateOf(true)
    /** Открытое в настройках устройство. */
    val selected = mutableStateOf("")
    /** Режим для экрана и ZENGGE: с платы шлема или посчитанный телефоном. */
    val mode = mutableIntStateOf(-1)
    val turn = mutableIntStateOf(0)
    var onStatusChange: (() -> Unit)? = null

    val esp = mutableMapOf<String, EspLink>()      // по id устройства
    val zg = mutableMapOf<String, ZenggeLink>()
    private val detector = Detector()
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    internal val main = Handler(Looper.getMainLooper())
    private var testUntil = 0L
    private var testWhat = 0

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("helmet", Context.MODE_PRIVATE)
        load()
        reconcile()
    }

    // ---------- хранение ----------
    private fun load() {
        devices.clear()
        val js = prefs.getString("devices", null)
        if (js != null) {
            val a = JSONArray(js)
            for (i in 0 until a.length()) devices.add(LightDevice.fromJson(a.getJSONObject(i)))
        } else {
            // первый запуск после обновления: шлем из прежнего модуля «Шлем» становится интегралом с платой
            val old = prefs.getString("address", "") ?: ""
            devices.add(LightDevice("h1", DevKind.HELMET_FF, "Шлем 1", espOn = old.isNotEmpty(), espAddr = old))
            activeHelmet.value = "h1"
        }
        activeHelmet.value = prefs.getString("active", activeHelmet.value) ?: ""
        if (devices.none { it.id == activeHelmet.value }) activeHelmet.value = devices.firstOrNull { it.isHelmet }?.id ?: ""
        enabled.value = prefs.getBoolean("enabled2", true)
        helmetsOn.value = prefs.getBoolean("helmetsOn", true)
        wheelOn.value = prefs.getBoolean("wheelOn", true)
    }

    private fun save() {
        val a = JSONArray()
        devices.forEach { a.put(it.toJson()) }
        prefs.edit().putString("devices", a.toString()).putString("active", activeHelmet.value).putBoolean("enabled2", enabled.value).putBoolean("helmetsOn", helmetsOn.value).putBoolean("wheelOn", wheelOn.value).apply()
    }

    fun device(id: String) = devices.firstOrNull { it.id == id }

    fun update(id: String, f: (LightDevice) -> LightDevice) {
        val i = devices.indexOfFirst { it.id == id }
        if (i < 0) return
        devices[i] = f(devices[i])
        save()
        reconcile()
    }

    /** Только название: без перебора связей, иначе при наборе теряются буквы. */
    fun rename(id: String, name: String) {
        val i = devices.indexOfFirst { it.id == id }
        if (i < 0 || devices[i].name == name) return
        devices[i] = devices[i].copy(name = name)
        main.removeCallbacks(saveLater); main.postDelayed(saveLater, 500)
    }
    private val saveLater = Runnable { save() }

    fun add(kind: DevKind): String {
        var n = 1
        while (devices.any { it.id == "d$n" }) n++
        val name = if (kind == DevKind.WHEEL) "Колесо" else "Шлем ${devices.count { it.isHelmet } + 1}"
        devices.add(LightDevice("d$n", kind, name))
        if (kind != DevKind.WHEEL && activeHelmet.value.isEmpty()) activeHelmet.value = "d$n"
        save()
        return "d$n"
    }

    fun remove(id: String) {
        devices.removeAll { it.id == id }
        if (activeHelmet.value == id) activeHelmet.value = devices.firstOrNull { it.isHelmet }?.id ?: ""
        save()
        reconcile()
    }

    fun setActive(id: String) { activeHelmet.value = id; save(); reconcile() }
    fun setEnabled(on: Boolean) { enabled.value = on; save(); reconcile() }
    fun setHelmetsOn(on: Boolean) { helmetsOn.value = on; save(); reconcile() }
    fun setWheelOn(on: Boolean) { wheelOn.value = on; save(); reconcile() }

    /** Связи держим со всеми устройствами, где стоит галка оборудования и выбран адрес. */
    fun reconcile() {
        if (!::ctx.isInitialized) return
        val want = devices.filter { enabled.value && (if (it.isHelmet) helmetsOn.value else wheelOn.value) }
        val espWant = want.filter { it.espOn && it.espAddr.isNotEmpty() }.associateBy { it.id }
        val zgWant = want.filter { it.zgOn && it.zgAddr.isNotEmpty() }.associateBy { it.id }
        esp.keys.toList().forEach { id -> if (espWant[id]?.espAddr != esp[id]?.address) { esp.remove(id)?.close() } }
        zg.keys.toList().forEach { id -> if (zgWant[id]?.zgAddr != zg[id]?.address) { zg.remove(id)?.close() } }
        espWant.forEach { (id, d) -> if (esp[id] == null) esp[id] = EspLink(ctx, d.espAddr, id).also { it.connect() } }
        zgWant.forEach { (id, d) -> if (zg[id] == null) zg[id] = ZenggeLink(ctx, d.zgAddr, id).also { it.connect() } }
        statusChanged()
    }

    internal fun statusChanged() { main.post { onStatusChange?.invoke() } }

    val isLinked get() = esp.values.any { it.linked } || zg.values.any { it.linked }
    val hasHardware get() = devices.any { (it.espOn && it.espAddr.isNotEmpty()) || (it.zgOn && it.zgAddr.isNotEmpty()) }

    /** Плата сообщила свой config - помним копию, по ней экран и расчёт на телефоне. */
    internal fun onBoardConfig(id: String, cfg: ByteArray) {
        val d = device(id) ?: return
        if (d.cfg != cfg.hex()) update(id) { it.copy(cfg = cfg.hex()) }
    }

    // ---------- данные колеса ----------
    private var lastSend = 0L

    /** Вызывается из WheelData на каждое обновление данных колеса. */
    fun onWheelData(wd: WheelData) {
        if (!enabled.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSend < 50) return
        lastSend = now
        // скорость и фазный ток со знаком, как их отдаёт колесо (до настройки «отрицательные значения»)
        val speed = (wd.helmetSpeedRaw / 10).coerceIn(-32768, 32767)        // 0.01 -> 0.1 км/ч
        val current = (wd.helmetCurrentRaw / 10).coerceIn(-32768, 32767)    // 0.01 -> 0.1 А
        val pitch = Math.round(wd.angle * 100).toInt().coerceIn(-32768, 32767)
        val pwm = Math.round(wd.calculatedPwm).toInt().coerceIn(0, 100)
        val bat = wd.batteryLevel.coerceIn(0, 100)
        val connected = wd.isConnected
        val b = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
        b.put(2).put(if (connected) 1 else 0).putShort(speed.toShort()).putShort(current.toShort()).putShort(pitch.toShort())
        b.put(pwm.toByte()).put(bat.toByte()).put(0)
        val pkt = b.array()
        esp.values.forEach { it.sendData(pkt) }
        // режим без платы: считает телефон по порогам шлема с ZENGGE без платы (или первого устройства)
        val own = devices.firstOrNull { it.zgOn && !it.espOn } ?: devices.firstOrNull()
        detector.cfg = own?.cfgObj() ?: Cfg()
        detector.feed(now, connected, speed, current, pitch, pwm)
        main.post { refreshMode() }
    }

    /** Режим: проверка из приложения, затем плата активного шлема, затем плата колеса, затем телефон. */
    internal fun refreshMode() {
        val now = SystemClock.elapsedRealtime()
        var m: Int; var t: Int
        // плата шлема главнее платы колеса: у шлема свои пороги и поворотник
        val board = esp.entries.filter { it.value.linked && it.value.mode.intValue >= 0 }
            .sortedBy { if (device(it.key)?.isHelmet == true) 0 else 1 }.firstOrNull()?.value
        if (board != null) { m = board.mode.intValue; t = board.turn.intValue }
        else { m = detector.mode; t = 0 }
        if (now < testUntil) {
            m = when (testWhat) { 2 -> Mode.BRAKE; 3 -> Mode.ACCEL; 7 -> Mode.WARN; 8 -> Mode.PARK; else -> Mode.NORM }
            t = when (testWhat) { 4 -> 1; 5 -> 2; 6 -> 3; else -> 0 }
        }
        if (m != mode.intValue || t != turn.intValue) {
            mode.intValue = m; turn.intValue = t
            zg.values.forEach { it.apply(m, t) }
        }
    }

    /** Проверка: на всех платах и ZENGGE сразу, 5 с. 0 - остановить. */
    fun test(what: Int) {
        testWhat = what
        testUntil = if (what == 0) 0 else SystemClock.elapsedRealtime() + 5000
        esp.values.forEach { it.cmd(1, what) }
        refreshMode()
        main.postDelayed({ refreshMode() }, 5100)
    }

    // ---------- поиск ----------
    private var scanCb: ScanCallback? = null
    val scanning = mutableStateOf(false)

    /** Ищет платы CJ (по сервису) и контроллеры ZENGGE (по имени LEDnetWF). */
    fun startScan(zengge: Boolean, found: (name: String, address: String) -> Unit): Boolean {
        val sc = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return false
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: ""
                val isCj = r.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
                val isZg = name.startsWith("LEDnetWF") || name.startsWith("IOTWF")
                if ((zengge && isZg) || (!zengge && isCj)) main.post { found(name.ifEmpty { "?" }, r.device.address) }
            }
        }
        scanCb = cb
        return try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            scanning.value = true
            main.postDelayed({ stopScan() }, 12000)
            true
        } catch (e: SecurityException) { false }
    }

    fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        scanning.value = false
        try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    /** Счётчик для перерисовки экрана при приходе кадров. */
    val frameTick = mutableIntStateOf(0)
}
