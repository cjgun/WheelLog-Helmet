package com.cooper.wheellog.helmet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Роли сегментов, как enum Role в fw/src/protokol.h. */
object Role {
    const val FRONT_R = 0; const val SIDE_R = 1; const val BACK = 2; const val SIDE_L = 3; const val FRONT_L = 4
    const val STOP = 5; const val PEAK_R = 6; const val PEAK_L = 7
    const val DRL_R = 8; const val DRL_L = 9; const val TAIL_R = 10; const val TAIL_L = 11
    const val PEDAL_R = 12; const val PEDAL_L = 13; const val OFF = 14
    const val COUNT = 15

    val names = listOf("Перед справа", "Бок справа", "Зад", "Бок слева", "Перед слева", "Стоп-полоса",
        "Козырёк справа", "Козырёк слева", "Ходовой справа", "Ходовой слева", "Габарит справа", "Габарит слева",
        "Педаль справа", "Педаль слева", "Не светит")
    /** Цвет метки в редакторе. */
    val colors = longArrayOf(0xFFDFE6F0, 0xFFE5484D, 0xFFFF6B6B, 0xFFC2383C, 0xFFC8D0DC, 0xFFFF3030,
        0xFFF0A020, 0xFFD08A10, 0xFFF2F2F2, 0xFFDADADA, 0xFFE5484D, 0xFFC2383C, 0xFFFF8A00, 0xFFE07800, 0xFF555555)
    val forHelmet = listOf(FRONT_R, SIDE_R, BACK, SIDE_L, FRONT_L, STOP, PEAK_R, PEAK_L, OFF)
    val forWheel = listOf(DRL_R, DRL_L, TAIL_R, TAIL_L, PEDAL_R, PEDAL_L, OFF)
    fun isRight(r: Int) = r in intArrayOf(FRONT_R, SIDE_R, PEAK_R, DRL_R, TAIL_R, PEDAL_R)
    fun isLeft(r: Int) = r in intArrayOf(FRONT_L, SIDE_L, PEAK_L, DRL_L, TAIL_L, PEDAL_L)
}

data class Seg(val count: Int, val role: Int, val rev: Boolean = false)

/**
 * Настройки платы v4, 74 байта little-endian (struct Config в fw/src/protokol.h).
 * Каждая правка - новая копия, чтобы Compose видел изменение.
 */
class Cfg(src: ByteArray = DEFAULT) {
    val b: ByteArray = src.copyOf(SIZE)
    private val bb get() = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(o: Int) = b[o].toInt() and 0xff
    fun u16(o: Int) = bb.getShort(o).toInt() and 0xffff
    fun copy(f: Cfg.() -> Unit): Cfg = Cfg(b).apply(f)
    private fun set8(o: Int, v: Int) { b[o] = v.coerceIn(0, 255).toByte() }
    private fun set16(o: Int, v: Int) { bb.putShort(o, v.coerceIn(0, 65535).toShort()) }

    val ok get() = u8(0) == VER
    var brightTail get() = u8(1); set(v) = set8(1, v)
    var brightBrake get() = u8(2); set(v) = set8(2, v)
    fun flag(m: Int) = u8(3) and m != 0
    fun setFlag(m: Int, on: Boolean) = set8(3, if (on) u8(3) or m else u8(3) and m.inv())
    var smoothMs get() = u16(4); set(v) = set16(4, v)
    var brakeDecel get() = u16(6); set(v) = set16(6, v)       // 0.1 км/ч/с
    var brakeCurrent get() = u16(8); set(v) = set16(8, v)
    var accel get() = u16(10); set(v) = set16(10, v)
    var minSpeed get() = u16(12); set(v) = set16(12, v)
    val numLeds get() = u16(14)
    var accelRgb: IntArray get() = intArrayOf(u8(24), u8(25), u8(26)); set(v) { for (i in 0..2) set8(24 + i, v[i]) }
    var turnSec get() = u8(27); set(v) = set8(27, v)
    var limitMa get() = u16(28); set(v) = set16(28, v)
    var brakeTilt get() = u16(30); set(v) = set16(30, v)        // 0.01 градуса
    var tiltAvgS get() = u8(32); set(v) = set8(32, v)
    var stripType get() = u8(33); set(v) = set8(33, v)
    var kind get() = u8(34); set(v) = set8(34, v)               // 0 шлем, 1 колесо
    var pedalRgb: IntArray get() = intArrayOf(u8(68), u8(69), u8(70)); set(v) { for (i in 0..2) set8(68 + i, v[i]) }
    var warnPwm get() = u8(71); set(v) = set8(71, v)
    var rim get() = u8(72); set(v) = set8(72, v)
    var relay get() = u8(73); set(v) = set8(73, v)

    var segs: List<Seg>
        get() = (0 until u8(35).coerceIn(0, MAX_SEG)).map { i ->
            val o = 36 + i * 4
            Seg(u16(o), u8(o + 2), u8(o + 3) and 1 != 0)
        }
        set(list) {
            val l = list.take(MAX_SEG)
            set8(35, l.size)
            for (i in 0 until MAX_SEG) {
                val o = 36 + i * 4
                val s = l.getOrNull(i) ?: Seg(0, Role.OFF)
                set16(o, s.count); set8(o + 2, s.role); set8(o + 3, if (s.rev) 1 else 0)
            }
            set16(14, l.sumOf { it.count })
        }

    /** Что не так с сегментами - для подсказки в редакторе; null - всё в порядке. */
    fun problem(): String? {
        val s = segs
        if (s.isEmpty()) return "Нет ни одного сегмента"
        val total = s.sumOf { it.count }
        if (total < 1 || total > 300) return "Всего точек $total, можно от 1 до 300"
        s.forEach { if ((it.role == Role.BACK || it.role == Role.STOP) && it.count in 1..4) return "${Role.names[it.role]}: всего ${it.count} точки - стоп и разгон почти не видно" }
        return null
    }

    companion object {
        const val VER = 4
        const val SIZE = 74
        const val MAX_SEG = 8
        const val F_CURRENT_INVERT = 0x01; const val F_FRONT_WHITE = 0x02; const val F_CALIBRATE = 0x04; const val F_TAIL_BRAKE = 0x08
        const val RIM_FLASH_WARN = 0x01; const val RIM_FLASH_BRAKE = 0x02; const val RIM_OFF_PARK = 0x04
        const val RELAY_SEND = 0x01; const val RELAY_RECV = 0x02
        val STRIPS = listOf("WS2812 / WS2815 (GRB)", "RGB", "BRG", "SK6812 RGBW (GRBW)", "RGBW")

        val TEMPLATE_FF = listOf(Seg(15, Role.FRONT_R), Seg(40, Role.SIDE_R), Seg(25, Role.BACK), Seg(40, Role.SIDE_L, true), Seg(15, Role.FRONT_L, true))
        // мотокросс: плата на затылке, стоп-полоса, правый бок к подбородку, козырёк, левый бок назад
        val TEMPLATE_MX = listOf(Seg(15, Role.STOP), Seg(40, Role.SIDE_R, true), Seg(2, Role.PEAK_R), Seg(2, Role.PEAK_L), Seg(40, Role.SIDE_L))
        val TEMPLATE_WHEEL = listOf(Seg(8, Role.DRL_R), Seg(8, Role.DRL_L), Seg(6, Role.PEDAL_L), Seg(8, Role.TAIL_L), Seg(8, Role.TAIL_R), Seg(6, Role.PEDAL_R))

        /** Значения по умолчанию, как CFG_DEFAULT в прошивке. */
        val DEFAULT: ByteArray = ByteArray(SIZE).also { a ->
            val c = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
            a[0] = VER.toByte(); a[1] = 40; a[2] = 255.toByte(); a[3] = F_FRONT_WHITE.toByte()
            c.putShort(4, 250); c.putShort(6, 50); c.putShort(8, 0); c.putShort(10, 40); c.putShort(12, 30)
            c.putShort(16, 15); c.putShort(18, 55); c.putShort(20, 80); c.putShort(22, 120)
            a[24] = 0; a[25] = 200.toByte(); a[26] = 160.toByte(); a[27] = 15
            c.putShort(28, 2000); c.putShort(30, 250); a[32] = 8; a[33] = 0
            a[68] = 255.toByte(); a[69] = 90; a[70] = 0; a[71] = 85; a[72] = RIM_FLASH_WARN.toByte(); a[73] = RELAY_SEND.toByte()
        }.let { Cfg(it).apply { segs = TEMPLATE_FF }.b }

        fun forKind(kind: DevKind): Cfg = Cfg().copy {
            when (kind) {
                DevKind.HELMET_FF -> { this.kind = 0; segs = TEMPLATE_FF; relay = RELAY_SEND }
                DevKind.HELMET_MX -> { this.kind = 0; segs = TEMPLATE_MX; relay = RELAY_SEND }
                DevKind.WHEEL -> { this.kind = 1; segs = TEMPLATE_WHEEL; relay = RELAY_RECV; limitMa = 3000 }
            }
        }
    }
}
