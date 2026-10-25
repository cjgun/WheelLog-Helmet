package com.cooper.wheellog.helmet

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import timber.log.Timber
import java.util.UUID

/**
 * Связь с платой CJ (шлем или колесо): своё GATT-соединение, BLE колеса не трогает.
 * Android выполняет одну GATT-операцию за раз, поэтому всё, кроме пакетов данных, идёт очередью.
 */
@SuppressLint("MissingPermission")
class EspLink(private val ctx: Context, val address: String, val devId: String) {
    val status = mutableStateOf("подключение")
    val mode = mutableIntStateOf(-1)
    val turn = mutableIntStateOf(0)
    val config = mutableStateOf<ByteArray?>(null)
    val sent = mutableIntStateOf(0)
    val ma = mutableIntStateOf(0)
    /** Сеть и обновление (characteristic net): name, ssid, wifi, upd, ver, board. Пусто - старая прошивка. */
    val net = mutableStateOf<Map<String, String>>(emptyMap())
    val hasNet get() = chNet != null
    /** Последний кадр ленты, RGB по 3 байта на точку. */
    @Volatile var frame: ByteArray = ByteArray(0)
    val linked get() = ready && gatt != null

    private var gatt: BluetoothGatt? = null
    private var chData: BluetoothGattCharacteristic? = null
    private var chConfig: BluetoothGattCharacteristic? = null
    private var chCmd: BluetoothGattCharacteristic? = null
    private var chFrame: BluetoothGattCharacteristic? = null
    private var chNet: BluetoothGattCharacteristic? = null
    private var ready = false
    private var closed = false
    private var frameWanted = false
    private var frameOn = false
    private val frameBuf = ByteArray(300 * 3)
    private val main = Lighting.main

    // очередь GATT-операций: операция возвращает true, если ждёт колбэк
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private var opSince = 0L

    private fun enqueue(op: () -> Boolean) { main.post { ops.addLast(op); next() } }
    private fun next() {
        if (opBusy && SystemClock.elapsedRealtime() - opSince > 3000) opBusy = false  // колбэк потерялся
        if (opBusy) return
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            val waits = try { op() } catch (e: Exception) { Timber.w(e, "helmet op"); false }
            if (waits) { opBusy = true; opSince = SystemClock.elapsedRealtime(); return }
        }
    }
    private fun done() { main.post { opBusy = false; next() } }

    fun connect() {
        if (closed || gatt != null) return
        val dev = try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(address) } catch (e: Exception) { null } ?: return
        setStatus("подключение")
        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            else dev.connectGatt(ctx, false, cb)
        } catch (e: SecurityException) { setStatus("нет разрешения Bluetooth"); null }
    }

    fun close() {
        closed = true
        main.post {
            ready = false
            ops.clear(); opBusy = false
            gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
            gatt = null
            mode.intValue = -1
            Lighting.statusChanged()
        }
    }

    private fun retry() {
        gatt?.let { try { it.close() } catch (_: Exception) {} }
        gatt = null
        ready = false; frameOn = false
        ops.clear(); opBusy = false
        if (!closed) main.postDelayed({ connect() }, 3000)
    }

    private fun setStatus(s: String) { status.value = s; Lighting.statusChanged() }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (closed) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("подключено, ищу сервис")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // кадры ленты идут кусками по 183 байта - нужен MTU побольше
                    if (!g.requestMtu(247)) g.discoverServices()
                } else {
                    retry()
                    mode.intValue = -1
                    setStatus("нет связи, повтор")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { main.post { g.discoverServices() } }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                val s = g.getService(Lighting.SERVICE)
                if (s == null) { setStatus("это не плата CJ"); retry(); return@post }
                chData = s.getCharacteristic(UUID_DATA)
                chConfig = s.getCharacteristic(UUID_CONFIG)
                chCmd = s.getCharacteristic(UUID_CMD)
                chFrame = s.getCharacteristic(UUID_FRAME)
                chNet = s.getCharacteristic(UUID_NET)
                val chState = s.getCharacteristic(UUID_STATE)
                ready = chData != null
                setStatus(if (chCmd == null) "на связи, старая прошивка" else "на связи")
                if (chState != null) subscribe(g, chState, true)
                readConfig()
                readNet()
                if (frameWanted) setFrame(true)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) = done()

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            @Suppress("DEPRECATION") val v = c.value
            main.post { onRead(c, v, st) }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, st: Int) {
            main.post { onRead(c, v, st) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (c.uuid == UUID_CONFIG) main.post {
                setStatus(if (st == BluetoothGatt.GATT_SUCCESS) "настройки записаны" else "плата не приняла настройки")
                readConfig()  // прочитать назад, что действует на самом деле
            }
            if (c.uuid == UUID_NET) main.post { readNet() }
            done()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value
            onNotify(c.uuid, v)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) = onNotify(c.uuid, v)
    }

    private fun onRead(c: BluetoothGattCharacteristic, v: ByteArray?, st: Int) {
        if (c.uuid == UUID_CONFIG && st == BluetoothGatt.GATT_SUCCESS && v != null) {
            config.value = v
            if (v.size == Cfg.SIZE && v[0].toInt() == Cfg.VER) Lighting.onBoardConfig(devId, v)
        }
        if (c.uuid == UUID_NET && st == BluetoothGatt.GATT_SUCCESS && v != null) {
            net.value = String(v, Charsets.UTF_8).lines().mapNotNull { l ->
                val i = l.indexOf('='); if (i > 0) l.substring(0, i) to l.substring(i + 1) else null
            }.toMap()
        }
        done()
    }

    private fun onNotify(u: UUID, v: ByteArray?) {
        if (v == null || v.isEmpty()) return
        if (u == UUID_STATE) main.post {
            mode.intValue = v[0].toInt()
            if (v.size > 1) turn.intValue = v[1].toInt()
            if (v.size >= 12) ma.intValue = (v[10].toInt() and 0xff) or ((v[11].toInt() and 0xff) shl 8)
            Lighting.refreshMode()
        } else if (u == UUID_FRAME && v.size >= 3) {
            val off = (v[0].toInt() and 0xff) or ((v[1].toInt() and 0xff) shl 8)
            val cnt = v[2].toInt() and 0xff
            if (off + cnt > 300 || v.size < 3 + cnt * 3) return
            System.arraycopy(v, 3, frameBuf, off * 3, cnt * 3)
            val n = Cfg(config.value ?: Cfg.DEFAULT).numLeds
            if (off + cnt >= n) {  // последний кусок кадра
                frame = frameBuf.copyOf(n * 3)
                main.post { Lighting.frameTick.intValue++ }
            }
        }
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic, on: Boolean) = enqueue {
        g.setCharacteristicNotification(c, on)
        val d = c.getDescriptor(CCCD) ?: return@enqueue false
        val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else { @Suppress("DEPRECATION") d.value = v; @Suppress("DEPRECATION") g.writeDescriptor(d) }
    }

    fun readConfig() = enqueue {
        val g = gatt ?: return@enqueue false
        val c = chConfig ?: return@enqueue false
        g.readCharacteristic(c)
    }

    fun readNet() = enqueue {
        val g = gatt ?: return@enqueue false
        val c = chNet ?: return@enqueue false
        g.readCharacteristic(c)
    }

    /** Команда сети: name=, wifi=<сеть>\t<пароль>, wifi on, wifi off, upd, reboot (fw/src/protokol.h). */
    fun netCmd(cmd: String) = enqueue {
        val g = gatt ?: return@enqueue false
        val c = chNet ?: return@enqueue false
        write(g, c, cmd.toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    /** Записать config целиком; после записи плата перечитывается. */
    fun writeConfig(bytes: ByteArray) = enqueue {
        val g = gatt ?: return@enqueue false
        val c = chConfig ?: return@enqueue false
        write(g, c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).also { if (!it) setStatus("плата занята, повторите") }
    }

    /** Команда: 1 - проверка, 2 - подсветка сегмента, 3 - поворот. */
    fun cmd(op: Int, arg: Int) = enqueue {
        val g = gatt ?: return@enqueue false
        val c = chCmd ?: return@enqueue false
        write(g, c, byteArrayOf(op.toByte(), arg.toByte()), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    /** Кадры ленты нужны, пока открыт экран с картинкой. */
    fun setFrame(on: Boolean) {
        frameWanted = on
        val g = gatt ?: return
        val c = chFrame ?: return
        if (on == frameOn) return
        frameOn = on
        subscribe(g, c, on)
    }

    private var lastData = 0L
    fun sendData(pkt: ByteArray) { main.post {
        val g = gatt ?: return@post
        val c = chData ?: return@post
        if (!ready || opBusy || ops.isNotEmpty()) return@post  // идёт настройка - пакет пропускаем
        val now = SystemClock.elapsedRealtime()
        if (now - lastData < 50) return@post
        lastData = now
        // старая прошивка (v3, без команд) понимает только пакет v1
        val p = if (chCmd == null) byteArrayOf(1, pkt[1], pkt[2], pkt[3], pkt[4], pkt[5], pkt[6], pkt[7]) else pkt
        if (write(g, c, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) { sent.intValue++; opBusy = true; opSince = now }
    } }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, type: Int): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, type) == BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION") c.writeType = type
            @Suppress("DEPRECATION") c.value = v
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
        }
    } catch (e: Exception) { Timber.w(e, "helmet write"); false }

    companion object {
        val UUID_DATA: UUID = UUID.fromString("e7af526e-d864-4f2c-a552-34f2f8265c65")
        val UUID_CONFIG: UUID = UUID.fromString("0754273b-03f6-4547-ac98-f6652dd7cb00")
        val UUID_STATE: UUID = UUID.fromString("ca1c4800-cea8-44c2-8e14-3be95eb3af36")
        val UUID_CMD: UUID = UUID.fromString("4f1d2a6e-8c3b-4e57-9a10-6b2d7c9e0f31")
        val UUID_FRAME: UUID = UUID.fromString("9b7e3c21-5d4a-4f68-b2e1-0c8a6f4d2e95")
        val UUID_NET: UUID = UUID.fromString("6d3e1f40-2b7a-4c55-9e10-3a8f5c2d7b61")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
