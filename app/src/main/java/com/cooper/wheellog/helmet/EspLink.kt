package com.cooper.wheellog.helmet

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import timber.log.Timber
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
    /** Прошивка по Bluetooth: плата умеет (characteristic ota) и ход отправки для экрана. */
    val hasOta get() = chOta != null
    val ota = mutableStateOf("")
    /** Последний кадр ленты, RGB по 3 байта на точку. */
    @Volatile var frame: ByteArray = ByteArray(0)
    val linked get() = ready && gatt != null

    private var gatt: BluetoothGatt? = null
    private var chData: BluetoothGattCharacteristic? = null
    private var chConfig: BluetoothGattCharacteristic? = null
    private var chCmd: BluetoothGattCharacteristic? = null
    private var chFrame: BluetoothGattCharacteristic? = null
    private var chNet: BluetoothGattCharacteristic? = null
    private var chOta: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var otaThread: Thread? = null
    @Volatile private var otaLatch: CountDownLatch? = null
    @Volatile private var otaReadVal = -1
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

        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) { main.post { if (st == BluetoothGatt.GATT_SUCCESS) mtu = m; g.discoverServices() } }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                val s = g.getService(Lighting.SERVICE)
                if (s == null) { setStatus("это не плата CJ"); retry(); return@post }
                chData = s.getCharacteristic(UUID_DATA)
                chConfig = s.getCharacteristic(UUID_CONFIG)
                chCmd = s.getCharacteristic(UUID_CMD)
                chFrame = s.getCharacteristic(UUID_FRAME)
                chNet = s.getCharacteristic(UUID_NET)
                chOta = s.getCharacteristic(UUID_OTA)
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
            if (Cfg.isBoardCfg(v)) Lighting.onBoardConfig(devId, v)
        }
        if (c.uuid == UUID_OTA) {
            otaReadVal = if (st == BluetoothGatt.GATT_SUCCESS && v != null && v.size >= 4) ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).int else -1
            otaLatch?.countDown()
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

    /**
     * Обновить прошивку по Bluetooth: телефон скачивает с cjgun.ru (<плата>.json и .bin), сверяет MD5 и шлёт
     * кусками [смещение][данные] без подтверждения; каждые 32 куска читает, сколько плата приняла, и продолжает
     * с этого места. Плата сама проверяет MD5 и перезапускается. Ход - в [ota].
     */
    fun otaFromSite() {
        if (otaThread?.isAlive == true) return
        val board = net.value["board"] ?: run { ota.value = "ошибка: плата не сказала, какая она"; return }
        val cur = net.value["ver"]
        otaThread = thread(name = "helmet-ota") {
            fun say(s: String) = main.post { ota.value = s }
            try {
                say("проверяю cjgun.ru")
                val js = JSONObject(URL("https://cjgun.ru/assets/proshivka/$board.json").readText())
                val md5 = js.getString("md5"); val ver = js.optString("ver")
                if (ver.isNotEmpty() && ver == cur) { say("уже последняя ($ver)"); return@thread }
                say("скачиваю $ver")
                val bin = URL(js.getString("url")).readBytes()
                val got = MessageDigest.getInstance("MD5").digest(bin).joinToString("") { "%02x".format(it) }
                if (got != md5) { say("ошибка: файл скачался с ошибкой, повторите"); return@thread }
                sendOta(bin, md5, ::say)
            } catch (e: Exception) {
                Timber.w(e, "helmet ota"); say("ошибка: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun waitIdle(timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            val l = CountDownLatch(1); var idle = false
            main.post { idle = ops.isEmpty() && !opBusy; l.countDown() }
            l.await(1, TimeUnit.SECONDS)
            if (idle) return true
            if (gatt == null) return false
            Thread.sleep(5)
        }
        return false
    }

    private fun readOta(): Int {
        val l = CountDownLatch(1); otaLatch = l; otaReadVal = -1
        enqueue { val g = gatt ?: return@enqueue false.also { l.countDown() }; val c = chOta ?: return@enqueue false.also { l.countDown() }; g.readCharacteristic(c) }
        l.await(5, TimeUnit.SECONDS)
        return otaReadVal
    }

    private fun sendOta(bin: ByteArray, md5: String, say: (String) -> Unit) {
        main.post { setFrame(false) }
        netCmd("ota ${bin.size} $md5")
        if (!waitIdle(5000)) { say("ошибка: плата не на связи"); return }
        Thread.sleep(300)
        if (readOta() != 0) { say("ошибка: плата не начала приём (${net.value["upd"]})"); return }
        val chunk = (mtu - 3 - 4).coerceIn(16, 240)
        var off = 0
        var stuck = 0
        val t0 = SystemClock.elapsedRealtime()
        while (off < bin.size) {
            var o = off
            for (k in 0 until 32) {
                if (o >= bin.size) break
                val n = minOf(chunk, bin.size - o)
                val pkt = ByteBuffer.allocate(4 + n).order(ByteOrder.LITTLE_ENDIAN).putInt(o).put(bin, o, n).array()
                enqueue { val g = gatt ?: return@enqueue false; val c = chOta ?: return@enqueue false; write(g, c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
                o += n
            }
            if (!waitIdle(15000)) { say("ошибка: связь прервалась на ${off * 100 / bin.size}%, повторите"); return }
            val got = readOta()
            if (got < 0) { say("ошибка: связь прервалась на ${off * 100 / bin.size}%, повторите"); return }
            if (got == off) { if (++stuck > 5) { say("ошибка: плата не принимает (${net.value["upd"]})"); return } } else stuck = 0
            off = got
            val sec = (SystemClock.elapsedRealtime() - t0) / 1000
            say("отправка ${off * 100 / bin.size}%, $sec с")
        }
        say("плата проверяет прошивку")
        for (i in 0 until 20) {
            Thread.sleep(700)
            readNet(); waitIdle(3000)
            val u = net.value["upd"] ?: ""
            if (u.startsWith("готово")) { say("готово, плата перезапускается в новую прошивку"); return }
            if (u.startsWith("ошибка")) { say(u); return }
        }
        say("готово, плата перезапускается")
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
        val UUID_OTA: UUID = UUID.fromString("8a1f3c52-6e4b-4d7a-b9c3-2f5e7d1a0c64")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
