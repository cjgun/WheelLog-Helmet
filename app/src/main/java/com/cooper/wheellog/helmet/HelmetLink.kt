package com.cooper.wheellog.helmet

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.cooper.wheellog.WheelData
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Модуль «Шлем» (CJ-Helmet): второе BLE-соединение, к ESP32 в шлеме.
 * На каждое обновление данных колеса пишет пакет v1 (8 байт) в характеристику data,
 * не чаще 20 раз в секунду. Колесу ничего не пишет - только читает WheelData.
 * Протокол: /root/helmet/README.md на NL.
 */
@SuppressLint("MissingPermission")
object HelmetLink {
    val SERVICE: UUID = UUID.fromString("e5c7e8c9-d675-4a18-81ca-fe8e20469649")
    private val DATA = UUID.fromString("e7af526e-d864-4f2c-a552-34f2f8265c65")
    private val CONFIG = UUID.fromString("0754273b-03f6-4547-ac98-f6652dd7cb00")
    private val STATE = UUID.fromString("ca1c4800-cea8-44c2-8e14-3be95eb3af36")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val MIN_INTERVAL_MS = 50L
    private const val RETRY_MS = 3000L

    // Состояние для экрана настроек
    val status = mutableStateOf("выключено")
    val mode = mutableStateOf(-1)          // режим из state: 0 нет связи, 1 норма, 2 стоп, 3 разгон
    val config = mutableStateOf<ByteArray?>(null)
    val sent = mutableStateOf(0)

    /** Вызывается на главном потоке при смене состояния связи (значок шлема в верхней строке). */
    var onStatusChange: (() -> Unit)? = null
    /** Шлем на связи: сервис найден, пакеты данных можно писать. */
    val isLinked: Boolean get() = ready && gatt != null

    private fun setStatus(s: String) {
        status.value = s
        onStatusChange?.invoke()
    }

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var chData: BluetoothGattCharacteristic? = null
    private var chConfig: BluetoothGattCharacteristic? = null
    private var ready = false
    private var busy = false               // идёт запись config или подписка - пакеты данных пропускаем
    private var lastSend = 0L

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply(); if (v) connect() else disconnect() }
    var address: String
        get() = prefs.getString("address", "") ?: ""
        set(v) { prefs.edit().putString("address", v).apply(); disconnect(); if (enabled) connect() }

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("helmet", Context.MODE_PRIVATE)
        if (enabled) connect()
    }

    private fun adapter(): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun connect() {
        if (!::ctx.isInitialized || gatt != null || address.isEmpty()) return
        val dev = try { adapter()?.getRemoteDevice(address) } catch (e: Exception) { null } ?: return
        setStatus("подключение")
        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            else dev.connectGatt(ctx, false, callback)
        } catch (e: SecurityException) {
            setStatus("нет разрешения Bluetooth")
            null
        }
    }

    fun disconnect() {
        main.removeCallbacksAndMessages(null)
        ready = false
        busy = false
        chData = null
        chConfig = null
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        mode.value = -1
        setStatus(if (enabled) "отключено" else "выключено")
    }

    private fun retry() {
        gatt?.let { try { it.close() } catch (_: Exception) {} }
        gatt = null
        ready = false
        busy = false
        if (enabled) main.postDelayed({ connect() }, RETRY_MS)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("подключено, ищу сервис")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else {
                    retry()
                    setStatus("нет связи со шлемом, повтор")
                    mode.value = -1
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                val s = g.getService(SERVICE)
                if (s == null) { setStatus("это не шлем CJ-Helmet"); retry(); return@post }
                chData = s.getCharacteristic(DATA)
                chConfig = s.getCharacteristic(CONFIG)
                val chState = s.getCharacteristic(STATE)
                ready = chData != null
                setStatus("на связи")
                // подписка на state, затем чтение config - по одной операции за раз
                busy = true
                g.setCharacteristicNotification(chState, true)
                val d = chState?.getDescriptor(CCCD)
                if (d != null) writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else readConfig()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post { readConfig() }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            @Suppress("DEPRECATION") val v = c.value
            main.post { onRead(c, v, st) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, st: Int) {
            main.post { onRead(c, v, st) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (c.uuid == CONFIG) main.post {
                setStatus(if (st == BluetoothGatt.GATT_SUCCESS) "настройки записаны" else "шлем не принял настройки")
                readConfig()  // прочитать назад, что действует на самом деле
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value
            main.post { onState(v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
            main.post { onState(v) }
        }
    }

    private fun onRead(c: BluetoothGattCharacteristic, v: ByteArray?, st: Int) {
        busy = false
        if (c.uuid == CONFIG && st == BluetoothGatt.GATT_SUCCESS && v != null) config.value = v
    }

    private fun onState(v: ByteArray?) {
        if (v != null && v.isNotEmpty()) mode.value = v[0].toInt()
    }

    private fun readConfig() {
        val g = gatt ?: return
        val c = chConfig ?: run { busy = false; return }
        busy = true
        if (!g.readCharacteristic(c)) busy = false
    }

    /** Записать config целиком (структура Config прошивки); после записи шлем перечитывается. */
    fun writeConfig(bytes: ByteArray) {
        val g = gatt ?: return
        val c = chConfig ?: return
        busy = true
        if (!write(g, c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
            busy = false
            setStatus("шлем занят, повторите")
        }
    }

    /** Вызывается из WheelData на каждое новое обновление данных колеса. */
    @JvmStatic
    fun onWheelData(wd: WheelData) {
        if (!ready || busy) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSend < MIN_INTERVAL_MS) return
        lastSend = now
        // скорость и фазный ток со знаком, как их отдаёт колесо (до настройки «отрицательные значения»)
        val speed = wd.helmetSpeedRaw / 10          // 0.01 км/ч -> 0.1 км/ч
        val current = wd.helmetCurrentRaw / 10      // 0.01 А -> 0.1 А
        val pitch = Math.round(wd.angle * 100).toInt()
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        b.put(1).put(if (wd.isConnected) 1 else 0)
        b.putShort(speed.coerceIn(-32768, 32767).toShort())
        b.putShort(current.coerceIn(-32768, 32767).toShort())
        b.putShort(pitch.coerceIn(-32768, 32767).toShort())
        val g = gatt ?: return
        val c = chData ?: return
        if (write(g, c, b.array(), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) sent.value++
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, type: Int): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, type) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = v
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            Timber.w(e, "helmet write")
            false
        }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v)
        else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    // ---------- поиск шлема ----------
    private var scanCb: ScanCallback? = null

    fun startScan(found: (name: String, address: String) -> Unit): Boolean {
        val sc = adapter()?.bluetoothLeScanner ?: return false
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                main.post { found(r.scanRecord?.deviceName ?: r.device.name ?: "CJ-Helmet", r.device.address) }
            }
        }
        scanCb = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            sc.startScan(listOf(filter), settings, cb)
            main.postDelayed({ stopScan() }, 10000)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        try { adapter()?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }
}
