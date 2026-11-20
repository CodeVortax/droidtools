package com.droidbubble.app.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

object BatteryUtils {

    private const val TAG = "BatteryUtils"
    private const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
    private const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"

    private val SYSFS_CURRENT_PATHS = listOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/usb/current_now",
        "/sys/class/power_supply/main/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/ac/current_now"
    )

    private val SYSFS_VOLTAGE_PATHS = listOf(
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/usb/voltage_now",
        "/sys/class/power_supply/main/voltage_now",
        "/sys/class/power_supply/bms/voltage_now"
    )

    /**
     * Queries the actual charging wattage received by the device from BatteryManager, sysfs, or broadcast extras,
     * and returns it rounded to the nearest whole Watt (e.g. 18.0, 33.0, 90.0), or null if unavailable.
     */
    fun getChargingWattage(context: Context, batteryIntent: Intent? = null): Double? {
        return try {
            val intent = batteryIntent ?: getBatteryIntent(context)
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

            // 1. Primary: Actual real-time current (BatteryManager CURRENT_NOW / CURRENT_AVERAGE) * battery/cell voltage
            if (bm != null) {
                var rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (rawCurrent == 0 || rawCurrent == Int.MIN_VALUE || abs(rawCurrent) < 15) {
                    rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                }

                val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                val cellVolts = normalizeVoltageVolts(rawVoltage) ?: readSysfsVoltageVolts() ?: 4.0
                val amps = normalizeCurrentAmps(abs(rawCurrent))

                if (amps != null && amps >= 0.1) {
                    // Dual-cell batteries often report single-cell voltage (~4.2V) while charging at 2S or higher,
                    // or report battery-side current (e.g. 6A-18A at 4.3V). Multiply by actual cell voltage.
                    val watts = amps * cellVolts
                    if (watts in 0.8..240.0) {
                        return watts.roundToInt().coerceAtLeast(1).toDouble()
                    }
                }
            }

            // 2. Secondary: Direct sysfs power_supply current_now & voltage_now reading
            val sysfsAmps = readSysfsCurrentAmps()
            if (sysfsAmps != null && sysfsAmps >= 0.1) {
                val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                val volts = normalizeVoltageVolts(rawVoltage) ?: readSysfsVoltageVolts() ?: 4.0
                val watts = sysfsAmps * volts
                if (watts in 0.8..240.0) {
                    return watts.roundToInt().coerceAtLeast(1).toDouble()
                }
            }

            // 3. Fallback: Negotiated max_charging_current * max_charging_voltage from ACTION_BATTERY_CHANGED
            if (intent != null) {
                val rawCurrent = intent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, -1)
                val rawVoltage = intent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, -1)

                val amps = normalizeCurrentAmps(rawCurrent)
                val volts = normalizeVoltageVolts(rawVoltage)

                if (amps != null && volts != null) {
                    val watts = amps * volts
                    if (watts in 0.8..240.0) {
                        return watts.roundToInt().coerceAtLeast(1).toDouble()
                    }
                }

                if (amps != null) {
                    val rawCellVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    val cellVolts = normalizeVoltageVolts(rawCellVoltage) ?: 5.0
                    val effectiveVolts = if (cellVolts in 3.0..4.6 && amps <= 1.5) 5.0 else cellVolts
                    val watts = amps * effectiveVolts
                    if (watts in 0.8..240.0) {
                        return watts.roundToInt().coerceAtLeast(1).toDouble()
                    }
                }

                // 4. Final fallback based on plugged source so a reasonable rounded wattage is shown
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL ||
                        plugged > 0
                if (isCharging) {
                    return when (plugged) {
                        BatteryManager.BATTERY_PLUGGED_AC -> 18.0
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> 10.0
                        BatteryManager.BATTERY_PLUGGED_USB -> 5.0
                        else -> null
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get charging wattage", e)
            null
        }
    }

    private fun readSysfsCurrentAmps(): Double? {
        for (path in SYSFS_CURRENT_PATHS) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val text = file.readText().trim()
                    val value = text.toLongOrNull() ?: continue
                    val absVal = abs(value)
                    if (absVal > 0) {
                        val amps = when {
                            absVal > 15_000L -> absVal / 1_000_000.0
                            absVal in 50L..15_000L -> absVal / 1_000.0
                            else -> null
                        }
                        if (amps != null && amps in 0.1..40.0) {
                            return amps
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun readSysfsVoltageVolts(): Double? {
        for (path in SYSFS_VOLTAGE_PATHS) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val text = file.readText().trim()
                    val value = text.toIntOrNull() ?: continue
                    val volts = normalizeVoltageVolts(value)
                    if (volts != null && volts in 3.0..25.0) {
                        return volts
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun getBatteryIntent(context: Context): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
            }
        } catch (_: Exception) {
            try {
                context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun normalizeCurrentAmps(rawCurrent: Int): Double? {
        if (rawCurrent <= 0 || rawCurrent == Int.MIN_VALUE) return null
        return when {
            rawCurrent > 15_000 -> rawCurrent / 1_000_000.0 // Microamperes (uA) -> Amps
            rawCurrent in 50..15_000 -> rawCurrent / 1_000.0 // Milliamperes (mA) -> Amps
            else -> null
        }
    }

    private fun normalizeVoltageVolts(rawVoltage: Int): Double? {
        if (rawVoltage <= 0 || rawVoltage == Int.MIN_VALUE) return null
        return when {
            rawVoltage > 100_000 -> rawVoltage / 1_000_000.0 // Microvolts (uV) -> Volts
            rawVoltage in 1_000..100_000 -> rawVoltage / 1_000.0 // Millivolts (mV) -> Volts
            rawVoltage in 3..25 -> rawVoltage.toDouble() // Direct Volts
            else -> null
        }
    }

    data class DetailedBatteryInfo(
        val levelPercent: Int = 100,
        val statusText: String = "Discharging",
        val isCharging: Boolean = false,
        val pluggedSource: String = "On Battery",
        val wattage: Double? = null,
        val currentMa: Int? = null,
        val voltageVolts: Double? = null,
        val temperatureC: Double = 25.0,
        val healthText: String = "Good",
        val technology: String = "Li-ion",
        val chargeCounterMah: Int? = null,
        val designCapacityMah: Int? = null,
        val cycleCount: Int? = null
    )

    fun getDetailedBatteryInfo(context: Context): DetailedBatteryInfo {
        val intent = getBatteryIntent(context)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPct = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else 100

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged > 0

        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            else -> if (isCharging) "Charging" else "Discharging"
        }

        val pluggedSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
            else -> "Battery"
        }

        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
        val tempC = (rawTemp / 10.0).coerceIn(-20.0, 90.0)

        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val volts = normalizeVoltageVolts(rawVoltage) ?: readSysfsVoltageVolts()

        var currentMa: Int? = null
        if (bm != null) {
            var rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (rawCurrent == 0 || rawCurrent == Int.MIN_VALUE || abs(rawCurrent) < 10) {
                rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            }
            val amps = normalizeCurrentAmps(abs(rawCurrent)) ?: readSysfsCurrentAmps()
            if (amps != null) {
                currentMa = (amps * 1000.0).roundToInt()
            }
        }

        val watts = if (isCharging) {
            getChargingWattage(context, intent)
        } else if (currentMa != null && volts != null) {
            val w = (currentMa / 1000.0) * volts
            if (w in 0.2..60.0) (w * 10.0).roundToInt() / 10.0 else null
        } else null

        val healthInt = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            ?: BatteryManager.BATTERY_HEALTH_GOOD
        val healthText = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Good"
        }

        val tech = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() } ?: "Li-ion"

        val chargeCounterUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1
        val chargeCounterMah = if (chargeCounterUah > 10_000) (chargeCounterUah / 1000.0).roundToInt() else null

        val designMah = readDesignCapacityMah(context)
        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val c = intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
            if (c >= 0) c else readSysfsCycleCount()
        } else {
            readSysfsCycleCount()
        }

        return DetailedBatteryInfo(
            levelPercent = levelPct,
            statusText = statusText,
            isCharging = isCharging,
            pluggedSource = pluggedSource,
            wattage = watts,
            currentMa = currentMa,
            voltageVolts = volts,
            temperatureC = tempC,
            healthText = healthText,
            technology = tech,
            chargeCounterMah = chargeCounterMah,
            designCapacityMah = designMah,
            cycleCount = cycles
        )
    }

    private fun readDesignCapacityMah(context: Context): Int? {
        val sysPaths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full_design"
        )
        for (p in sysPaths) {
            runCatching {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val v = f.readText().trim().toLongOrNull() ?: return@runCatching
                    val mah = if (v > 100_000L) (v / 1000L).toInt() else v.toInt()
                    if (mah in 1000..25000) return mah
                }
            }
        }
        return runCatching {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val cap = powerProfileClass.getMethod("getBatteryCapacity").invoke(powerProfile) as? Double
            cap?.roundToInt()?.takeIf { it in 1000..25000 }
        }.getOrNull()
    }

    private fun readSysfsCycleCount(): Int? {
        val paths = listOf(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count"
        )
        for (p in paths) {
            runCatching {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val v = f.readText().trim().toIntOrNull()
                    if (v != null && v >= 0) return v
                }
            }
        }
        return null
    }
}
