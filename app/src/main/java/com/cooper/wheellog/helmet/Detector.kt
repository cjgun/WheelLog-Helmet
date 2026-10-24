package com.cooper.wheellog.helmet

import kotlin.math.abs

/**
 * Режим по данным колеса, когда платы нет (например, шлем только с ZENGGE).
 * Та же логика, что updateMode() в прошивке: замедление, наклон назад, ток, разгон, PWM, стоянка.
 */
class Detector {
    var cfg = Cfg()
    var mode = Mode.NOSIGNAL; private set

    private var accelF = 0f
    private var tiltAvg = 0f
    private var prevSpeed = 0
    private var prevT = 0L
    private var modeSince = 0L
    private var stillSince = 0L

    /** speed, current: 0.1 км/ч и 0.1 А со знаком; pitch: 0.01 градуса; pwm: %. */
    fun feed(now: Long, connected: Boolean, speed: Int, current: Int, pitch: Int, pwm: Int) {
        val v = abs(speed)
        val dt = now - prevT
        if (prevT == 0L || dt > 500) { accelF = 0f; tiltAvg = pitch.toFloat() }
        else if (dt > 0) {
            val a = (v - prevSpeed) * 1000f / dt
            accelF += dt.toFloat() / (cfg.smoothMs + dt) * (a - accelF)
            tiltAvg += dt.toFloat() / (cfg.tiltAvgS * 1000f + dt) * (pitch - tiltAvg)
        }
        prevSpeed = v; prevT = now

        val m: Int
        if (!connected) m = Mode.NOSIGNAL
        else {
            var regen = if (speed < 0) current else -current
            if (cfg.flag(Cfg.F_CURRENT_INVERT)) regen = -regen
            val keep = mode == Mode.BRAKE
            val thrA = if (keep) cfg.brakeDecel * 0.6f else cfg.brakeDecel.toFloat()
            val thrI = if (keep) cfg.brakeCurrent * 6 / 10 else cfg.brakeCurrent
            val thrT = if (keep) cfg.brakeTilt * 0.6f else cfg.brakeTilt.toFloat()
            val tilt = pitch - tiltAvg
            val byTilt = cfg.brakeTilt > 0 && v >= 50 && tilt >= thrT
            val byCur = cfg.brakeCurrent > 0 && v >= cfg.minSpeed && regen >= thrI
            val brake = (cfg.brakeDecel > 0 && accelF <= -thrA) || byCur || byTilt
            val thrAcc = if (mode == Mode.ACCEL) cfg.accel * 0.6f else cfg.accel.toFloat()
            val acc = cfg.accel > 0 && v >= cfg.minSpeed && accelF >= thrAcc
            val warn = cfg.warnPwm > 0 && pwm >= cfg.warnPwm
            if (v >= 10) stillSince = now
            val park = now - stillSince >= 5000
            var mm = when { warn -> Mode.WARN; brake -> Mode.BRAKE; acc -> Mode.ACCEL; park -> Mode.PARK; else -> Mode.NORM }
            // стоп и разгон держатся не меньше 300 мс - кочка не моргает
            if ((mode == Mode.BRAKE || mode == Mode.ACCEL) && mm != mode && mm != Mode.BRAKE && mm != Mode.WARN && now - modeSince < 300) mm = mode
            m = mm
        }
        if (m != mode) { mode = m; modeSince = now }
    }
}
