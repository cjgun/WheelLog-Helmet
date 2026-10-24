package com.cooper.wheellog.helmet

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * Контроллер ZENGGE (LEDnetWF, Symphony 0xA3) по протоколу проекта lednetwf_ble.
 * Проверено на контроллере владельца 24.10.2026: цвет 0x3B, состояние 0x81, музыка 0x73 вернулась байт в байт.
 * Зон у этого контроллера нет (команда 0xA0 игнорируется) - реакции на всю ленту.
 */
@SuppressLint("MissingPermission")
class ZenggeLink(private val ctx: Context, val address: String, val devId: String) {
    val status = mutableStateOf("подключение")
    /** Что сейчас показываем: для картинки на экране. */
    val shown = mutableStateOf("эффект")
    @Volatile var shownRgb: Int = -1   // -1 - свой эффект ZENGGE
    val linked get() = ready && gatt != null

    private var gatt: BluetoothGatt? = null
    private var chWr: BluetoothGattCharacteristic? = null
    private var ready = false
    private var closed = false
    private var seq = 0
    private val main = Lighting.main
    private var replyCb: ((ByteArray) -> Unit)? = null
    private var current = ""          // что сейчас выставлено: чтобы не слать одно и то же
    private var blinkOn = false

    fun connect() {
        if (closed || gatt != null) return
        val dev = try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(address) } catch (e: Exception) { null } ?: return
        status.value = "подключение"
        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            else dev.connectGatt(ctx, false, cb)
        } catch (e: SecurityException) { status.value = "нет разрешения Bluetooth"; null }
    }

    fun close() {
        closed = true
        main.removeCallbacks(blink)
        main.post {
            ready = false
            gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
            gatt = null
            Lighting.statusChanged()
        }
    }

    private fun retry() {
        gatt?.let { try { it.close() } catch (_: Exception) {} }
        gatt = null; ready = false; current = ""
        if (!closed) main.postDelayed({ connect() }, 3000)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (closed) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) { status.value = "подключено, ищу сервис"; g.discoverServices() }
                else { status.value = "нет связи, повтор"; retry(); Lighting.statusChanged() }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                val s = g.getService(SVC)
                chWr = s?.getCharacteristic(WR)
                val nt = s?.getCharacteristic(NT)
                if (chWr == null || nt == null) { status.value = "это не ZENGGE"; retry(); return@post }
                g.setCharacteristicNotification(nt, true)
                nt.getDescriptor(EspLink.CCCD)?.let { d ->
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
                }
                ready = true
                status.value = "на связи"
                Lighting.statusChanged()
            }
        }
        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value
            onReply(v)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) = onReply(v)
    }

    /** Ответ контроллера: заголовок и JSON {"code":0,"payload":"81..."}. */
    private fun onReply(v: ByteArray?) {
        if (v == null) return
        val s = String(v, Charsets.ISO_8859_1)
        val a = s.indexOf('{'); val z = s.lastIndexOf('}')
        val payload = try {
            if (a >= 0 && z > a) hex(JSONObject(s.substring(a, z + 1)).getString("payload")) else v.copyOfRange(minOf(8, v.size), v.size)
        } catch (e: Exception) { return }
        val f = replyCb ?: return
        replyCb = null
        main.post { f(payload) }
    }

    /** Обёртка транспорта: 8 байт заголовка, family 0x0b - команда, 0x0a - запрос с ответом. */
    private fun send(raw: ByteArray, family: Int = 0x0b): Boolean {
        val g = gatt ?: return false
        val c = chWr ?: return false
        if (!ready) return false
        val p = ByteArray(8 + raw.size)
        p[0] = 0; p[1] = (seq++ and 0xff).toByte(); p[2] = 0x80.toByte(); p[3] = 0
        p[4] = (raw.size shr 8).toByte(); p[5] = raw.size.toByte(); p[6] = (raw.size + 1).toByte(); p[7] = family.toByte()
        System.arraycopy(raw, 0, p, 8, raw.size)
        return try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; @Suppress("DEPRECATION") c.value = p; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
        } catch (e: Exception) { Timber.w(e, "zengge"); false }
    }

    private fun withSum(vararg b: Int): ByteArray {
        val r = ByteArray(b.size + 1)
        var s = 0
        b.forEachIndexed { i, v -> r[i] = v.toByte(); s += v and 0xff }
        r[b.size] = (s and 0xff).toByte()
        return r
    }

    private fun query(raw: ByteArray, cb: (ByteArray) -> Unit) {
        replyCb = cb
        if (!send(raw, 0x0a)) { replyCb = null; return }
        main.postDelayed({ if (replyCb === cb) replyCb = null }, 1500)
    }

    /** Цвет на всю ленту (0x3B, режим A1: HSV + RGB). */
    private fun color(r: Int, g: Int, b: Int, bright: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        // упаковка как pack_hue_saturation в lednetwf_ble: (оттенок 0-360 << 7) | насыщенность 0-100
        val packed = (hsv[0].toInt().coerceIn(0, 360) shl 7) or (hsv[1] * 100).toInt().coerceIn(0, 100)
        send(withSum(0x3B, 0xA1, packed shr 8 and 0xff, packed and 0xff, bright, 0, 0, r, g, b, 0, 0))
        shownRgb = (r shl 16) or (g shl 8) or b
    }

    private fun restoreRide() {
        val d = Lighting.device(devId)
        val raw = d?.zgRide?.takeIf { it.isNotEmpty() }?.let { hex(it) }
        if (raw != null) send(raw) else color(255, 0, 0, 30)  // эффект не запомнен - тусклый красный габарит
        shownRgb = if (raw != null) -1 else 0x4c0000
    }

    // ---------- реакции ----------
    private var wantMode = -1; private var wantTurn = 0

    /** Режим изменился: выставить ленту по реакциям устройства. */
    fun apply(mode: Int, turn: Int) {
        wantMode = mode; wantTurn = turn
        main.removeCallbacks(blink)
        val react = Lighting.device(devId)?.zgReact ?: 0
        val key = when {
            turn != 0 && react and R_TURN != 0 -> "turn"
            mode == Mode.WARN && react and R_WARN != 0 -> "warn"
            mode == Mode.BRAKE && react and R_STOP != 0 -> "brake"
            mode == Mode.ACCEL && react and R_ACCEL != 0 -> "accel"
            else -> "ride"
        }
        if (key == current && key != "turn" && key != "warn") return
        current = key
        shown.value = mapOf("turn" to "поворот", "warn" to "предупреждение", "brake" to "стоп", "accel" to "разгон", "ride" to "эффект")[key]!!
        when (key) {
            "brake" -> color(255, 0, 0, 100)
            "accel" -> { val c = Lighting.device(devId)?.cfgObj()?.accelRgb ?: intArrayOf(0, 200, 160); color(c[0], c[1], c[2], 100) }
            "ride" -> restoreRide()
            else -> { blinkOn = false; main.post(blink) }
        }
    }

    // поворот: жёлтый 400 мс / выкл 400 мс; предупреждение: красный и жёлтый по 150 мс
    private val blink = object : Runnable {
        override fun run() {
            if (closed) return
            blinkOn = !blinkOn
            if (current == "turn") { if (blinkOn) color(255, 140, 0, 100) else color(0, 0, 0, 0); main.postDelayed(this, 400) }
            else if (current == "warn") { if (blinkOn) color(255, 30, 0, 100) else color(255, 150, 0, 100); main.postDelayed(this, 150) }
        }
    }

    /**
     * Запомнить эффект, который сейчас стоит в приложении ZENGGE: после стопа и поворотов вернём его.
     * Состояние 0x81: [81, модель, питание, режим, подрежим, яркость, R, G, B, ...];
     * для музыки цвета берём из запроса 0x44.
     */
    fun remember(done: (String) -> Unit) {
        query(withSum(0x81, 0x8A, 0x8B)) { st ->
            if (st.size < 9 || st[0] != 0x81.toByte()) { done("контроллер не ответил"); return@query }
            val m = st[3].toInt() and 0xff; val sub = st[4].toInt() and 0xff; val br = st[5].toInt() and 0xff
            val r = st[6].toInt() and 0xff; val g = st[7].toInt() and 0xff; val b = st[8].toInt() and 0xff
            when (m) {
                0x62 -> query(withSum(0x44, 0x4A, 0x4B, 0xF0)) { s2 ->
                    // [.., 44, эффект, FG R G B, BG R G B, скорость, ...] - цвета музыки
                    val fg = if (s2.size >= 9) intArrayOf(s2[3].toInt() and 0xff, s2[4].toInt() and 0xff, s2[5].toInt() and 0xff) else intArrayOf(r, g, b)
                    val bg = if (s2.size >= 9) intArrayOf(s2[6].toInt() and 0xff, s2[7].toInt() and 0xff, s2[8].toInt() and 0xff) else intArrayOf(0, 0, 255)
                    val cmd = withSum(0x73, 0x01, 0x27, sub, fg[0], fg[1], fg[2], bg[0], bg[1], bg[2], 50, br.coerceIn(1, 100))
                    save(cmd, "музыка, эффект $sub", done)
                }
                0x61 -> save(withSum(0x3B, 0xA1, 0, 0, br.coerceIn(1, 100), 0, 0, r, g, b, 0, 0).also { colorCmdFix(it, r, g, b) }, "цвет #%02x%02x%02x".format(r, g, b), done)
                0x25 -> save(withSum(0x42, sub, 50, br.coerceIn(1, 100)), "эффект $sub", done)
                else -> query(withSum(0x44, 0x4A, 0x4B, 0xF0)) { s2 ->
                    if (s2.size >= 12) {
                        val e = s2[2].toInt() and 0xff
                        val cmd = withSum(0x41, e, s2[3].toInt() and 0xff, s2[4].toInt() and 0xff, s2[5].toInt() and 0xff,
                            s2[6].toInt() and 0xff, s2[7].toInt() and 0xff, s2[8].toInt() and 0xff, s2[9].toInt() and 0xff, 0, 0, 0xF0)
                        save(cmd, "статичный эффект $e", done)
                    } else done("режим 0x%02x не разобран".format(m))
                }
            }
        }
    }

    // в команде 0x3B оттенок и насыщенность нужны верные - пересчитать по RGB
    private fun colorCmdFix(c: ByteArray, r: Int, g: Int, b: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val packed = (hsv[0].toInt().coerceIn(0, 360) shl 7) or (hsv[1] * 100).toInt().coerceIn(0, 100)
        c[2] = (packed shr 8).toByte(); c[3] = packed.toByte()
        var s = 0; for (i in 0 until c.size - 1) s += c[i].toInt() and 0xff
        c[c.size - 1] = s.toByte()
    }

    private fun save(cmd: ByteArray, label: String, done: (String) -> Unit) {
        Lighting.update(devId) { it.copy(zgRide = cmd.hex(), zgRideLabel = label) }
        done(label)
    }

    /** Вернуть запомненный эффект сейчас (кнопка на экране). */
    fun showRide() { current = ""; apply(Mode.NORM, 0) }

    companion object {
        const val R_STOP = 1; const val R_ACCEL = 2; const val R_TURN = 4; const val R_WARN = 8
        val SVC: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        val WR: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val NT: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    }
}
