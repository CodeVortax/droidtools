package com.droidbubble.app.tools

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.droidbubble.app.util.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

enum class RoutineThemeAction(val displayName: String) {
    NO_CHANGE("No change"),
    DARK("Dark mode"),
    LIGHT("Light mode")
}

data class BrightnessThemeRoutine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "Routine",
    val enabled: Boolean = true,
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 0,
    val brightnessPercent: Int = 20, // 0..100
    val themeAction: RoutineThemeAction = RoutineThemeAction.DARK,
    val restoreBrightnessPercent: Int = 65,
    val restoreThemeAction: RoutineThemeAction = RoutineThemeAction.LIGHT
)

data class NightLightConfig(
    val isCurrentlyOn: Boolean = false,
    val intensityPercent: Int = 50, // 0..100 (warmth)
    val scheduleEnabled: Boolean = false,
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 0,
    val turnOnForAppsEnabled: Boolean = false,
    val triggerApps: Set<String> = emptySet()
)

data class PerAppVolumeRule(
    val packageName: String,
    val appLabel: String,
    val volumePercent: Int, // 0..100
    val enabled: Boolean = true
)

data class SoundToolsConfig(
    val perAppVolumeEnabled: Boolean = false,
    val fasterVolumeSwitching: Boolean = false,
    val fasterVolumeStepSize: Int = 2, // 2x or 3x steps per volume key press
    val disableSafeVolumeWarning: Boolean = false
)

data class BatteryPowerConfig(
    val lowPowerModeEnabled: Boolean = false,
    val autoLowPowerEnabled: Boolean = false,
    val autoLowPowerThreshold: Int = 20, // 5..50%
    val brightnessReductionEnabled: Boolean = false,
    val brightnessReductionThreshold: Int = 20, // 5..50%
    val brightnessReductionPercent: Int = 25, // 5..60%
    val pixelGridOverlayEnabled: Boolean = false,
    val pixelGridSpacing: Int = 2, // 2 = 1px black dot per 2x2, 3 = 1px per 3x3, 4 = 1px checkerboard
    val shortScreenTimeoutEnabled: Boolean = false, // 15s timeout
    val disableAodOnPowerSave: Boolean = false,
    val disableAnimationsOnPowerSave: Boolean = false,
    val darkModeOnPowerSave: Boolean = false,
    val restrictAutoSyncOnPowerSave: Boolean = false
)

enum class NotificationLightingStyle(val displayName: String) {
    SIMPLE_EDGE_GLOW("Simple Edge Glow"),
    OUTLINE("Outline"),
    TOP_FULL_PULSE("Full Screen Pulse from Top"),
    DROPLET("Droplet Effect"),
    CORNER_BEAMS("Corner Beams"),
    NEON_CHASE("Chasing Neon"),
    DUAL_SIDE_WAVE("Dual Side Wave"),
    STARBURST_PARTICLES("Starburst Particles"),
    HORIZON_RIPPLE("Horizon Ripple"),
    LASER_SCAN("Laser Matrix Scan")
}

enum class LightingColorMode(val displayName: String) {
    CUSTOM("Custom Color"),
    DYNAMIC_APP("App Icon Color"),
    RAINBOW("Spectrum Shift")
}

data class PerAppLightingConfig(
    val packageName: String,
    val appLabel: String,
    val enabled: Boolean = true,
    val colorArgb: Long = 0xFF64D2FF,
    val style: NotificationLightingStyle? = null // null = use global default style
)

data class NotificationLightingConfig(
    val enabled: Boolean = false,
    val style: NotificationLightingStyle = NotificationLightingStyle.SIMPLE_EDGE_GLOW,
    val colorMode: LightingColorMode = LightingColorMode.CUSTOM,
    val customColorArgb: Long = 0xFF64D2FF,
    val cornerRadiusDp: Int = 28,
    val thicknessDp: Float = 8f,
    val durationSeconds: Int = 4,
    val animationSpeed: Float = 1.0f,
    val repeatCount: Int = 2,
    val showWhenScreenOn: Boolean = true,
    val showOnLockScreen: Boolean = true,
    val perAppConfigs: List<PerAppLightingConfig> = emptyList()
)

data class ActiveLightingEvent(
    val id: Long = System.currentTimeMillis(),
    val style: NotificationLightingStyle,
    val colorArgb: Long,
    val isRainbow: Boolean = false,
    val cornerRadiusDp: Int,
    val thicknessDp: Float,
    val durationMs: Long,
    val animationSpeed: Float,
    val repeatCount: Int
)

class SystemToolsManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("droidtools_system_tools_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _brightnessRoutines = MutableStateFlow<List<BrightnessThemeRoutine>>(emptyList())
    val brightnessRoutines: StateFlow<List<BrightnessThemeRoutine>> = _brightnessRoutines.asStateFlow()

    private val _nightLightConfig = MutableStateFlow(NightLightConfig())
    val nightLightConfig: StateFlow<NightLightConfig> = _nightLightConfig.asStateFlow()

    private val _perAppVolumeRules = MutableStateFlow<List<PerAppVolumeRule>>(emptyList())
    val perAppVolumeRules: StateFlow<List<PerAppVolumeRule>> = _perAppVolumeRules.asStateFlow()

    private val _soundConfig = MutableStateFlow(SoundToolsConfig())
    val soundConfig: StateFlow<SoundToolsConfig> = _soundConfig.asStateFlow()

    private val _batteryPowerConfig = MutableStateFlow(BatteryPowerConfig())
    val batteryPowerConfig: StateFlow<BatteryPowerConfig> = _batteryPowerConfig.asStateFlow()

    private val _notificationLightingConfig = MutableStateFlow(NotificationLightingConfig())
    val notificationLightingConfig: StateFlow<NotificationLightingConfig> = _notificationLightingConfig.asStateFlow()

    private val _activeLightingEvent = MutableStateFlow<ActiveLightingEvent?>(null)
    val activeLightingEvent: StateFlow<ActiveLightingEvent?> = _activeLightingEvent.asStateFlow()

    private var savedBrightnessBeforeReduction: Int? = null
    private var savedScreenTimeoutBeforeShort: Int? = null

    private var lastAppliedRoutineId: String? = null
    private var lastRoutineWasActive: Boolean = false
    private var nightLightForcedByApp: Boolean = false
    private var nightLightForcedBySchedule: Boolean = false
    private var previousVolumeBeforeAppOverride: Int? = null
    private var activeOverriddenAppPkg: String? = null

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        // Load Brightness Routines
        val routinesJson = prefs.getString(KEY_BRIGHTNESS_ROUTINES, null)
        if (!routinesJson.isNullOrBlank()) {
            _brightnessRoutines.value = deserializeRoutines(routinesJson)
        }

        // Load Night Light Config
        val nlEnabled = getSystemNightDisplayState()
        val nlIntensity = prefs.getInt(KEY_NL_INTENSITY, 50)
        val nlSched = prefs.getBoolean(KEY_NL_SCHEDULE_ENABLED, false)
        val nlStartH = prefs.getInt(KEY_NL_START_H, 22)
        val nlStartM = prefs.getInt(KEY_NL_START_M, 0)
        val nlEndH = prefs.getInt(KEY_NL_END_H, 7)
        val nlEndM = prefs.getInt(KEY_NL_END_M, 0)
        val nlAppsEnabled = prefs.getBoolean(KEY_NL_APPS_ENABLED, false)
        val nlApps = prefs.getStringSet(KEY_NL_APPS_SET, emptySet()) ?: emptySet()
        _nightLightConfig.value = NightLightConfig(
            isCurrentlyOn = nlEnabled,
            intensityPercent = nlIntensity,
            scheduleEnabled = nlSched,
            startHour = nlStartH,
            startMinute = nlStartM,
            endHour = nlEndH,
            endMinute = nlEndM,
            turnOnForAppsEnabled = nlAppsEnabled,
            triggerApps = nlApps
        )

        // Load Per-App Volume Rules
        val volRulesJson = prefs.getString(KEY_PER_APP_VOLUMES, null)
        if (!volRulesJson.isNullOrBlank()) {
            _perAppVolumeRules.value = deserializeVolumeRules(volRulesJson)
        }

        // Load Sound Config
        _soundConfig.value = SoundToolsConfig(
            perAppVolumeEnabled = prefs.getBoolean(KEY_PER_APP_VOL_ENABLED, false),
            fasterVolumeSwitching = prefs.getBoolean(KEY_FASTER_VOL_SWITCHING, false),
            fasterVolumeStepSize = prefs.getInt(KEY_FASTER_VOL_STEP, 2),
            disableSafeVolumeWarning = prefs.getBoolean(KEY_DISABLE_SAFE_VOL, false)
        )

        // Load Battery & Power Saving Config
        val sysLowPower = runCatching {
            Settings.Global.getInt(context.contentResolver, "low_power", 0) == 1
        }.getOrDefault(prefs.getBoolean(KEY_BAT_LOW_POWER, false))
        _batteryPowerConfig.value = BatteryPowerConfig(
            lowPowerModeEnabled = sysLowPower,
            autoLowPowerEnabled = prefs.getBoolean(KEY_BAT_AUTO_LP_ENABLED, false),
            autoLowPowerThreshold = prefs.getInt(KEY_BAT_AUTO_LP_THRESHOLD, 20),
            brightnessReductionEnabled = prefs.getBoolean(KEY_BAT_BRIGHT_RED_ENABLED, false),
            brightnessReductionThreshold = prefs.getInt(KEY_BAT_BRIGHT_RED_THRESHOLD, 20),
            brightnessReductionPercent = prefs.getInt(KEY_BAT_BRIGHT_RED_PCT, 25),
            pixelGridOverlayEnabled = prefs.getBoolean(KEY_BAT_PIXEL_GRID_ENABLED, false),
            pixelGridSpacing = prefs.getInt(KEY_BAT_PIXEL_GRID_SPACING, 2),
            shortScreenTimeoutEnabled = prefs.getBoolean(KEY_BAT_SHORT_TIMEOUT, false),
            disableAodOnPowerSave = prefs.getBoolean(KEY_BAT_DISABLE_AOD, false),
            disableAnimationsOnPowerSave = prefs.getBoolean(KEY_BAT_DISABLE_ANIMS, false),
            darkModeOnPowerSave = prefs.getBoolean(KEY_BAT_DARK_MODE, false),
            restrictAutoSyncOnPowerSave = prefs.getBoolean(KEY_BAT_RESTRICT_SYNC, false)
        )

        // Load Notification Lighting Config
        val lightingStyle = runCatching {
            NotificationLightingStyle.valueOf(
                prefs.getString(KEY_LIGHT_STYLE, NotificationLightingStyle.SIMPLE_EDGE_GLOW.name)
                    ?: NotificationLightingStyle.SIMPLE_EDGE_GLOW.name
            )
        }.getOrDefault(NotificationLightingStyle.SIMPLE_EDGE_GLOW)
        val lightingColorMode = runCatching {
            LightingColorMode.valueOf(
                prefs.getString(KEY_LIGHT_COLOR_MODE, LightingColorMode.CUSTOM.name)
                    ?: LightingColorMode.CUSTOM.name
            )
        }.getOrDefault(LightingColorMode.CUSTOM)
        val perAppLightJson = prefs.getString(KEY_LIGHT_PER_APP, null)
        val perAppLightList = if (!perAppLightJson.isNullOrBlank()) {
            deserializePerAppLighting(perAppLightJson)
        } else emptyList()

        _notificationLightingConfig.value = NotificationLightingConfig(
            enabled = prefs.getBoolean(KEY_LIGHT_ENABLED, false),
            style = lightingStyle,
            colorMode = lightingColorMode,
            customColorArgb = prefs.getLong(KEY_LIGHT_CUSTOM_COLOR, 0xFF64D2FFL),
            cornerRadiusDp = prefs.getInt(KEY_LIGHT_CORNER_RADIUS, 28),
            thicknessDp = prefs.getFloat(KEY_LIGHT_THICKNESS, 8f),
            durationSeconds = prefs.getInt(KEY_LIGHT_DURATION, 4),
            animationSpeed = prefs.getFloat(KEY_LIGHT_SPEED, 1.0f),
            repeatCount = prefs.getInt(KEY_LIGHT_REPEAT, 2),
            showWhenScreenOn = prefs.getBoolean(KEY_LIGHT_SCREEN_ON, true),
            showOnLockScreen = prefs.getBoolean(KEY_LIGHT_LOCK_SCREEN, true),
            perAppConfigs = perAppLightList
        )
    }

    // =========================================================================
    // DISPLAY SETTINGS HELPERS
    // =========================================================================

    fun canWriteSystemSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else true
    }

    fun hasPrivilegedAccess(): Boolean {
        return ShizukuHelper.hasWriteSecureSettings(context) ||
                (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasShizukuPermission())
    }

    fun getCurrentBrightnessPercent(): Int {
        return try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            ((raw / 255f) * 100f).roundToInt().coerceIn(0, 100)
        } catch (_: Throwable) {
            50
        }
    }

    fun setSystemBrightnessPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val raw = ((clamped / 100f) * 255f).roundToInt().coerceIn(0, 255)
        if (canWriteSystemSettings()) {
            runCatching {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
                return
            }
        }
        ShizukuHelper.executeAsync(
            "settings put system screen_brightness_mode 0 && settings put system screen_brightness $raw"
        )
    }

    fun isDarkModeActive(ctx: Context = context): Boolean {
        return try {
            val nightMask = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES) return true
            if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_NO) return false
            val uiModeManager = ctx.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            if (uiModeManager != null) {
                return uiModeManager.nightMode == android.app.UiModeManager.MODE_NIGHT_YES
            }
            Settings.Secure.getInt(ctx.contentResolver, "ui_night_mode", 1) == 2
        } catch (_: Throwable) {
            false
        }
    }

    fun setDarkMode(dark: Boolean) {
        val modeVal = if (dark) 2 else 1
        val cmdVal = if (dark) "yes" else "no"
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Secure.putInt(context.contentResolver, "ui_night_mode", modeVal)
            }
        }
        ShizukuHelper.executeAsync("cmd uimode night $cmdVal ; settings put secure ui_night_mode $modeVal")
    }

    fun getSystemNightDisplayState(): Boolean {
        return try {
            Settings.Secure.getInt(context.contentResolver, "night_display_activated", 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    fun setNightLightActivated(enabled: Boolean) {
        _nightLightConfig.value = _nightLightConfig.value.copy(isCurrentlyOn = enabled)
        val v = if (enabled) 1 else 0
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Secure.putInt(context.contentResolver, "night_display_activated", v)
            }
        }
        ShizukuHelper.executeAsync("settings put secure night_display_activated $v")
    }

    fun setNightLightIntensity(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _nightLightConfig.value = _nightLightConfig.value.copy(intensityPercent = clamped)
        prefs.edit().putInt(KEY_NL_INTENSITY, clamped).apply()
        // Android night_display_color_temperature ranges typically from 4082 (coolest, 0%) down to 2596 (warmest, 100%)
        val colorTemp = (4082 - ((clamped / 100f) * (4082 - 2596))).roundToInt()
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Secure.putInt(context.contentResolver, "night_display_color_temperature", colorTemp)
            }
        }
        ShizukuHelper.executeAsync("settings put secure night_display_color_temperature $colorTemp")
    }

    fun updateNightLightConfig(update: (NightLightConfig) -> NightLightConfig) {
        val updated = update(_nightLightConfig.value)
        _nightLightConfig.value = updated
        prefs.edit()
            .putInt(KEY_NL_INTENSITY, updated.intensityPercent)
            .putBoolean(KEY_NL_SCHEDULE_ENABLED, updated.scheduleEnabled)
            .putInt(KEY_NL_START_H, updated.startHour)
            .putInt(KEY_NL_START_M, updated.startMinute)
            .putInt(KEY_NL_END_H, updated.endHour)
            .putInt(KEY_NL_END_M, updated.endMinute)
            .putBoolean(KEY_NL_APPS_ENABLED, updated.turnOnForAppsEnabled)
            .putStringSet(KEY_NL_APPS_SET, updated.triggerApps)
            .apply()
        checkSchedulesNow()
    }

    fun isAlwaysOnDisplayEnabled(): Boolean {
        return try {
            val doze = Settings.Secure.getInt(context.contentResolver, "doze_always_on", -1)
            if (doze != -1) return doze == 1
            Settings.System.getInt(context.contentResolver, "aod_mode", 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    fun setAlwaysOnDisplay(enabled: Boolean) {
        val v = if (enabled) 1 else 0
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Secure.putInt(context.contentResolver, "doze_always_on", v)
            }
        }
        if (canWriteSystemSettings()) {
            runCatching {
                Settings.System.putInt(context.contentResolver, "aod_mode", v)
            }
        }
        ShizukuHelper.executeAsync("settings put secure doze_always_on $v ; settings put system aod_mode $v")
    }

    val timeoutStepsMs = listOf(
        15_000,      // 15s
        30_000,      // 30s
        60_000,      // 1m
        120_000,     // 2m
        300_000,     // 5m
        600_000,     // 10m
        900_000,     // 15m
        1_800_000    // 30m
    )

    fun formatTimeoutStep(ms: Int): String {
        return when {
            ms < 60_000 -> "${ms / 1000}s"
            else -> "${ms / 60_000}m"
        }
    }

    fun getCurrentScreenTimeoutIndex(): Int {
        val currentMs = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        } catch (_: Throwable) {
            60_000
        }
        return timeoutStepsMs.indices.minByOrNull { kotlin.math.abs(timeoutStepsMs[it] - currentMs) } ?: 2
    }

    fun setScreenTimeoutByIndex(index: Int) {
        val ms = timeoutStepsMs.getOrElse(index) { 60_000 }
        if (canWriteSystemSettings()) {
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
                return
            }
        }
        ShizukuHelper.executeAsync("settings put system screen_off_timeout $ms")
    }

    fun getWindowAnimationScale(): Float {
        return try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f)
        } catch (_: Throwable) {
            1.0f
        }
    }

    fun getTransitionAnimationScale(): Float {
        return try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        } catch (_: Throwable) {
            1.0f
        }
    }

    fun getAnimatorDurationScale(): Float {
        return try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        } catch (_: Throwable) {
            1.0f
        }
    }

    fun setAnimationScale(key: String, scale: Float) {
        val formatted = ((scale * 10f).roundToInt() / 10f).coerceIn(0f, 5f)
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Global.putFloat(context.contentResolver, key, formatted)
            }
        }
        ShizukuHelper.executeAsync("settings put global $key $formatted")
    }

    fun isStatusBarHidden(): Boolean {
        return ShizukuHelper.isSystemStatusBarIconsHidden(context)
    }

    fun setStatusBarHidden(hidden: Boolean) {
        ShizukuHelper.setSystemStatusBarIconsHidden(context, hidden)
    }

    // =========================================================================
    // ROUTINES MANAGEMENT & EVALUATION
    // =========================================================================

    fun saveBrightnessRoutine(routine: BrightnessThemeRoutine) {
        val list = _brightnessRoutines.value.toMutableList()
        val idx = list.indexOfFirst { it.id == routine.id }
        if (idx >= 0) {
            list[idx] = routine
        } else {
            list.add(routine)
        }
        _brightnessRoutines.value = list
        prefs.edit().putString(KEY_BRIGHTNESS_ROUTINES, serializeRoutines(list)).apply()
        checkSchedulesNow()
    }

    fun deleteBrightnessRoutine(id: String) {
        val list = _brightnessRoutines.value.filter { it.id != id }
        _brightnessRoutines.value = list
        prefs.edit().putString(KEY_BRIGHTNESS_ROUTINES, serializeRoutines(list)).apply()
    }

    fun runBrightnessRoutineNow(routine: BrightnessThemeRoutine) {
        setSystemBrightnessPercent(routine.brightnessPercent)
    }

    fun checkSchedulesNow() {
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        // 1. Check Brightness Routines
        val activeRoutine = _brightnessRoutines.value.firstOrNull { r ->
            r.enabled && isTimeInWindow(nowMinutes, r.startHour * 60 + r.startMinute, r.endHour * 60 + r.endMinute)
        }
        if (activeRoutine != null) {
            if (lastAppliedRoutineId != activeRoutine.id || !lastRoutineWasActive) {
                lastAppliedRoutineId = activeRoutine.id
                lastRoutineWasActive = true
                runBrightnessRoutineNow(activeRoutine)
            }
        } else if (lastRoutineWasActive && lastAppliedRoutineId != null) {
            val prev = _brightnessRoutines.value.firstOrNull { it.id == lastAppliedRoutineId }
            lastRoutineWasActive = false
            lastAppliedRoutineId = null
            if (prev != null) {
                setSystemBrightnessPercent(prev.restoreBrightnessPercent)
            }
        }

        // 2. Check Night Light Schedule (when not overridden by foreground app)
        val nl = _nightLightConfig.value
        if (nl.scheduleEnabled && !nightLightForcedByApp) {
            val inWindow = isTimeInWindow(
                nowMinutes,
                nl.startHour * 60 + nl.startMinute,
                nl.endHour * 60 + nl.endMinute
            )
            if (inWindow && !nightLightForcedBySchedule) {
                nightLightForcedBySchedule = true
                setNightLightActivated(true)
                setNightLightIntensity(nl.intensityPercent)
            } else if (!inWindow && nightLightForcedBySchedule) {
                nightLightForcedBySchedule = false
                setNightLightActivated(false)
            }
        }
    }

    private fun isTimeInWindow(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        return if (startMin <= endMin) {
            nowMin in startMin until endMin
        } else {
            // Crosses midnight (e.g. 22:00 to 07:00)
            nowMin >= startMin || nowMin < endMin
        }
    }

    // =========================================================================
    // FOREGROUND APP INTERCEPTION (NIGHT LIGHT & PER-APP VOLUME)
    // =========================================================================

    fun onForegroundAppChanged(packageName: String) {
        if (packageName == "com.android.systemui" || packageName == context.packageName) return

        // 1. Night light on app entry
        val nl = _nightLightConfig.value
        if (nl.turnOnForAppsEnabled) {
            val shouldTurnOn = nl.triggerApps.contains(packageName)
            if (shouldTurnOn && !nightLightForcedByApp) {
                nightLightForcedByApp = true
                setNightLightActivated(true)
                setNightLightIntensity(nl.intensityPercent)
            } else if (!shouldTurnOn && nightLightForcedByApp) {
                nightLightForcedByApp = false
                // Restore to schedule state if schedule is active, otherwise turn off
                val cal = Calendar.getInstance()
                val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val keepOnForSchedule = nl.scheduleEnabled && isTimeInWindow(
                    nowMinutes,
                    nl.startHour * 60 + nl.startMinute,
                    nl.endHour * 60 + nl.endMinute
                )
                setNightLightActivated(keepOnForSchedule)
            }
        }

        // 2. Per-app volume (Shizuku + AudioManager)
        val sc = _soundConfig.value
        if (sc.perAppVolumeEnabled) {
            val rule = _perAppVolumeRules.value.firstOrNull { it.packageName == packageName && it.enabled }
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (rule != null && am != null) {
                if (activeOverriddenAppPkg != packageName) {
                    if (previousVolumeBeforeAppOverride == null) {
                        previousVolumeBeforeAppOverride = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                    activeOverriddenAppPkg = packageName
                    applyAppVolume(am, rule.packageName, rule.volumePercent)
                }
            } else if (activeOverriddenAppPkg != null && am != null) {
                val restoreVol = previousVolumeBeforeAppOverride
                activeOverriddenAppPkg = null
                previousVolumeBeforeAppOverride = null
                if (restoreVol != null) {
                    runCatching {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
                    }
                }
            }
        }
    }

    private fun applyAppVolume(am: AudioManager, packageName: String, volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetIndex = ((clamped / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        }
        // Also apply via Shizuku appops & media_session/cmd audio if available
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasShizukuPermission()) {
            val muteOp = if (clamped == 0) "ignore" else "allow"
            ShizukuHelper.executeAsync(
                "cmd appops set $packageName PLAY_AUDIO $muteOp ; cmd media_session volume --stream 3 --set $targetIndex"
            )
        }
    }

    // =========================================================================
    // SOUND & VIBRATIONS SETTINGS
    // =========================================================================

    fun savePerAppVolumeRule(rule: PerAppVolumeRule) {
        val list = _perAppVolumeRules.value.toMutableList()
        val idx = list.indexOfFirst { it.packageName == rule.packageName }
        if (idx >= 0) {
            list[idx] = rule
        } else {
            list.add(rule)
        }
        _perAppVolumeRules.value = list
        prefs.edit().putString(KEY_PER_APP_VOLUMES, serializeVolumeRules(list)).apply()

        // Immediately apply if it's 0% mute or unmute via Shizuku appops
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasShizukuPermission()) {
            val op = if (rule.enabled && rule.volumePercent == 0) "ignore" else "allow"
            ShizukuHelper.executeAsync("cmd appops set ${rule.packageName} PLAY_AUDIO $op")
        }
    }

    fun deletePerAppVolumeRule(packageName: String) {
        val list = _perAppVolumeRules.value.filter { it.packageName != packageName }
        _perAppVolumeRules.value = list
        prefs.edit().putString(KEY_PER_APP_VOLUMES, serializeVolumeRules(list)).apply()
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasShizukuPermission()) {
            ShizukuHelper.executeAsync("cmd appops set $packageName PLAY_AUDIO allow")
        }
    }

    fun updateSoundConfig(update: (SoundToolsConfig) -> SoundToolsConfig) {
        val prev = _soundConfig.value
        val updated = update(prev)
        _soundConfig.value = updated
        prefs.edit()
            .putBoolean(KEY_PER_APP_VOL_ENABLED, updated.perAppVolumeEnabled)
            .putBoolean(KEY_FASTER_VOL_SWITCHING, updated.fasterVolumeSwitching)
            .putInt(KEY_FASTER_VOL_STEP, updated.fasterVolumeStepSize)
            .putBoolean(KEY_DISABLE_SAFE_VOL, updated.disableSafeVolumeWarning)
            .apply()

        if (prev.disableSafeVolumeWarning != updated.disableSafeVolumeWarning) {
            applyDisableSafeVolumeWarning(updated.disableSafeVolumeWarning)
        }
        if (prev.fasterVolumeSwitching != updated.fasterVolumeSwitching || prev.fasterVolumeStepSize != updated.fasterVolumeStepSize) {
            applyFasterVolumeSwitching(updated.fasterVolumeSwitching, updated.fasterVolumeStepSize)
        }
    }

    fun applyDisableSafeVolumeWarning(disable: Boolean) {
        // State 3 = SAFE_MEDIA_VOLUME_DISABLED, State 2 = SAFE_MEDIA_VOLUME_INACTIVE, State 1 = NOT_CONFIGURED
        val stateVal = if (disable) 3 else 1
        val bypassVal = if (disable) 1 else 0
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Global.putInt(context.contentResolver, "audio_safe_volume_state", stateVal)
                Settings.Secure.putInt(context.contentResolver, "unsafe_volume_music_active_ms", if (disable) 0 else 1)
                Settings.System.putInt(context.contentResolver, "audio_safe_volume_state", stateVal)
            }
        }
        ShizukuHelper.executeAsync(
            "settings put global audio_safe_volume_state $stateVal ; " +
            "settings put system audio_safe_volume_state $stateVal ; " +
            "settings put secure unsafe_volume_music_active_ms 0 ; " +
            "setprop audio.safemedia.bypass ${if (disable) "true" else "false"} ; " +
            "settings put global audio_safe_csd_current_value $bypassVal"
        )
    }

    fun applyFasterVolumeSwitching(enabled: Boolean, stepMultiplier: Int) {
        // Adjusts volume steps and lowers volume panel dismissal delay for snappier switching
        val steps = if (enabled) {
            when (stepMultiplier) {
                3 -> 8
                else -> 10
            }
        } else 15
        ShizukuHelper.executeAsync(
            "setprop ro.config.media_vol_steps $steps ; " +
            "settings put secure volume_dialog_timeout ${if (enabled) 1500 else 3000}"
        )
    }

    fun onVolumeKeyPressed(direction: Int): Boolean {
        val sc = _soundConfig.value
        if (!sc.fasterVolumeSwitching) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        // Boost each press by extra step(s) so switching from low to high or vice versa is 2x-3x faster
        val extraSteps = (sc.fasterVolumeStepSize - 1).coerceIn(1, 3)
        scope.launch {
            runCatching {
                repeat(extraSteps) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        direction,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
            }
        }
        return false // Let system also process the primary press + show native volume UI
    }

    // =========================================================================
    // BATTERY & POWER SAVING
    // =========================================================================

    fun updateBatteryPowerConfig(update: (BatteryPowerConfig) -> BatteryPowerConfig) {
        val prev = _batteryPowerConfig.value
        val next = update(prev)
        _batteryPowerConfig.value = next
        prefs.edit()
            .putBoolean(KEY_BAT_LOW_POWER, next.lowPowerModeEnabled)
            .putBoolean(KEY_BAT_AUTO_LP_ENABLED, next.autoLowPowerEnabled)
            .putInt(KEY_BAT_AUTO_LP_THRESHOLD, next.autoLowPowerThreshold)
            .putBoolean(KEY_BAT_BRIGHT_RED_ENABLED, next.brightnessReductionEnabled)
            .putInt(KEY_BAT_BRIGHT_RED_THRESHOLD, next.brightnessReductionThreshold)
            .putInt(KEY_BAT_BRIGHT_RED_PCT, next.brightnessReductionPercent)
            .putBoolean(KEY_BAT_PIXEL_GRID_ENABLED, next.pixelGridOverlayEnabled)
            .putInt(KEY_BAT_PIXEL_GRID_SPACING, next.pixelGridSpacing)
            .putBoolean(KEY_BAT_SHORT_TIMEOUT, next.shortScreenTimeoutEnabled)
            .putBoolean(KEY_BAT_DISABLE_AOD, next.disableAodOnPowerSave)
            .putBoolean(KEY_BAT_DISABLE_ANIMS, next.disableAnimationsOnPowerSave)
            .putBoolean(KEY_BAT_DARK_MODE, next.darkModeOnPowerSave)
            .putBoolean(KEY_BAT_RESTRICT_SYNC, next.restrictAutoSyncOnPowerSave)
            .apply()

        if (prev.lowPowerModeEnabled != next.lowPowerModeEnabled) {
            applySystemLowPowerMode(next.lowPowerModeEnabled)
        }
        if (prev.brightnessReductionEnabled != next.brightnessReductionEnabled ||
            prev.brightnessReductionThreshold != next.brightnessReductionThreshold ||
            prev.brightnessReductionPercent != next.brightnessReductionPercent
        ) {
            evaluateAutoBrightnessReduction(next)
        }
        if (prev.shortScreenTimeoutEnabled != next.shortScreenTimeoutEnabled) {
            applyShortScreenTimeout(next.shortScreenTimeoutEnabled)
        }
        if (prev.disableAodOnPowerSave != next.disableAodOnPowerSave) {
            applyAodDisabled(next.disableAodOnPowerSave)
        }
        if (prev.disableAnimationsOnPowerSave != next.disableAnimationsOnPowerSave) {
            applyAnimationsDisabled(next.disableAnimationsOnPowerSave)
        }
        if (prev.darkModeOnPowerSave != next.darkModeOnPowerSave) {
            setDarkMode(next.darkModeOnPowerSave)
        }
        if (prev.restrictAutoSyncOnPowerSave != next.restrictAutoSyncOnPowerSave) {
            runCatching {
                android.content.ContentResolver.setMasterSyncAutomatically(!next.restrictAutoSyncOnPowerSave)
            }
        }
    }

    fun applySystemLowPowerMode(enabled: Boolean) {
        val v = if (enabled) 1 else 0
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Global.putInt(context.contentResolver, "low_power", v)
                return
            }
        }
        ShizukuHelper.executeAsync("settings put global low_power $v")
    }

    private fun applyBrightnessReduction(enabled: Boolean, reductionPercent: Int) {
        val current = getCurrentBrightnessPercent()
        if (enabled) {
            if (savedBrightnessBeforeReduction == null) {
                savedBrightnessBeforeReduction = current
            }
            val base = savedBrightnessBeforeReduction ?: current
            val target = (base * (100 - reductionPercent.coerceIn(5, 80)) / 100f).roundToInt().coerceIn(5, 100)
            setSystemBrightnessPercent(target)
        } else {
            val restore = savedBrightnessBeforeReduction
            savedBrightnessBeforeReduction = null
            if (restore != null) {
                setSystemBrightnessPercent(restore)
            }
        }
    }

    private fun applyShortScreenTimeout(enabled: Boolean) {
        if (enabled) {
            val currentMs = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
            }.getOrDefault(60_000)
            if (savedScreenTimeoutBeforeShort == null && currentMs > 15_000) {
                savedScreenTimeoutBeforeShort = currentMs
            }
            setScreenTimeoutMs(15_000)
        } else {
            val restoreMs = savedScreenTimeoutBeforeShort ?: 60_000
            savedScreenTimeoutBeforeShort = null
            setScreenTimeoutMs(restoreMs)
        }
    }

    private fun setScreenTimeoutMs(timeoutMs: Int) {
        if (canWriteSystemSettings()) {
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs)
                return
            }
        }
        ShizukuHelper.executeAsync("settings put system screen_off_timeout $timeoutMs")
    }

    private fun applyAodDisabled(disabled: Boolean) {
        val aodVal = if (disabled) 0 else 1
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                Settings.Secure.putInt(context.contentResolver, "doze_always_on", aodVal)
                return
            }
        }
        ShizukuHelper.executeAsync("settings put secure doze_always_on $aodVal")
    }

    private fun applyAnimationsDisabled(disabled: Boolean) {
        val scale = if (disabled) "0.0" else "1.0"
        if (ShizukuHelper.hasWriteSecureSettings(context)) {
            runCatching {
                val cr = context.contentResolver
                Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, scale.toFloat())
                Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, scale.toFloat())
                Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, scale.toFloat())
                return
            }
        }
        ShizukuHelper.executeAsync(
            "settings put global window_animation_scale $scale && " +
                    "settings put global transition_animation_scale $scale && " +
                    "settings put global animator_duration_scale $scale"
        )
    }

    private fun evaluateAutoBrightnessReduction(
        cfg: BatteryPowerConfig,
        currentLevelPercent: Int? = null,
        currentIsCharging: Boolean? = null
    ) {
        if (!cfg.brightnessReductionEnabled) {
            applyBrightnessReduction(false, cfg.brightnessReductionPercent)
            return
        }
        val (level, charging) = if (currentLevelPercent != null && currentIsCharging != null) {
            currentLevelPercent to currentIsCharging
        } else {
            val batteryIntent = runCatching {
                context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isChg = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val lvl = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scl = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (lvl >= 0 && scl > 0) ((lvl.toFloat() / scl.toFloat()) * 100f).roundToInt().coerceIn(0, 100) else 100
            pct to isChg
        }
        val shouldReduce = !charging && level <= cfg.brightnessReductionThreshold
        applyBrightnessReduction(shouldReduce, cfg.brightnessReductionPercent)
    }

    fun onBatteryChanged(levelPercent: Int, isCharging: Boolean) {
        val cfg = _batteryPowerConfig.value
        if (cfg.autoLowPowerEnabled) {
            if (!isCharging && levelPercent <= cfg.autoLowPowerThreshold && !cfg.lowPowerModeEnabled) {
                updateBatteryPowerConfig { it.copy(lowPowerModeEnabled = true) }
            } else if (isCharging && levelPercent > cfg.autoLowPowerThreshold && cfg.lowPowerModeEnabled) {
                updateBatteryPowerConfig { it.copy(lowPowerModeEnabled = false) }
            }
        }
        if (cfg.brightnessReductionEnabled) {
            evaluateAutoBrightnessReduction(cfg, levelPercent, isCharging)
        }
    }

    // =========================================================================
    // NOTIFICATION LIGHTING
    // =========================================================================

    fun updateNotificationLightingConfig(update: (NotificationLightingConfig) -> NotificationLightingConfig) {
        val next = update(_notificationLightingConfig.value)
        _notificationLightingConfig.value = next
        prefs.edit()
            .putBoolean(KEY_LIGHT_ENABLED, next.enabled)
            .putString(KEY_LIGHT_STYLE, next.style.name)
            .putString(KEY_LIGHT_COLOR_MODE, next.colorMode.name)
            .putLong(KEY_LIGHT_CUSTOM_COLOR, next.customColorArgb)
            .putInt(KEY_LIGHT_CORNER_RADIUS, next.cornerRadiusDp)
            .putFloat(KEY_LIGHT_THICKNESS, next.thicknessDp)
            .putInt(KEY_LIGHT_DURATION, next.durationSeconds)
            .putFloat(KEY_LIGHT_SPEED, next.animationSpeed)
            .putInt(KEY_LIGHT_REPEAT, next.repeatCount)
            .putBoolean(KEY_LIGHT_SCREEN_ON, next.showWhenScreenOn)
            .putBoolean(KEY_LIGHT_LOCK_SCREEN, next.showOnLockScreen)
            .putString(KEY_LIGHT_PER_APP, serializePerAppLighting(next.perAppConfigs))
            .apply()
    }

    fun addOrUpdatePerAppLighting(rule: PerAppLightingConfig) {
        updateNotificationLightingConfig { current ->
            val list = current.perAppConfigs.toMutableList()
            val idx = list.indexOfFirst { it.packageName == rule.packageName }
            if (idx >= 0) list[idx] = rule else list.add(rule)
            current.copy(perAppConfigs = list)
        }
    }

    fun removePerAppLighting(packageName: String) {
        updateNotificationLightingConfig { current ->
            current.copy(perAppConfigs = current.perAppConfigs.filterNot { it.packageName == packageName })
        }
    }

    fun triggerNotificationLighting(
        packageName: String? = null,
        extractedColorArgb: Long? = null,
        isPreview: Boolean = false
    ) {
        val cfg = _notificationLightingConfig.value
        if (!isPreview && !cfg.enabled) return

        if (!isPreview) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isInteractive = pm?.isInteractive ?: true
            val isLocked = km?.isKeyguardLocked ?: false
            if (isInteractive && !isLocked && !cfg.showWhenScreenOn) return
            if (isLocked && !cfg.showOnLockScreen) return
        }

        val appRule = if (packageName != null) {
            cfg.perAppConfigs.firstOrNull { it.packageName == packageName && it.enabled }
        } else null

        val chosenStyle = appRule?.style ?: cfg.style
        val chosenColor = when {
            appRule != null -> appRule.colorArgb
            cfg.colorMode == LightingColorMode.DYNAMIC_APP && extractedColorArgb != null -> extractedColorArgb
            else -> cfg.customColorArgb
        }
        val isRainbow = appRule == null && cfg.colorMode == LightingColorMode.RAINBOW

        val durationMs = (cfg.durationSeconds.coerceIn(1, 15) * 1000L)
        val event = ActiveLightingEvent(
            id = System.currentTimeMillis(),
            style = chosenStyle,
            colorArgb = chosenColor,
            isRainbow = isRainbow,
            cornerRadiusDp = cfg.cornerRadiusDp.coerceIn(0, 64),
            thicknessDp = cfg.thicknessDp.coerceIn(2f, 28f),
            durationMs = durationMs,
            animationSpeed = cfg.animationSpeed.coerceIn(0.4f, 2.5f),
            repeatCount = cfg.repeatCount.coerceIn(1, 5)
        )
        _activeLightingEvent.value = event

        scope.launch {
            kotlinx.coroutines.delay(durationMs + 250L)
            if (_activeLightingEvent.value?.id == event.id) {
                _activeLightingEvent.value = null
            }
        }
    }

    fun clearActiveLightingEvent() {
        _activeLightingEvent.value = null
    }

    // =========================================================================
    // SERIALIZATION
    // =========================================================================

    private fun serializeRoutines(list: List<BrightnessThemeRoutine>): String {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("enabled", r.enabled)
            o.put("startHour", r.startHour)
            o.put("startMinute", r.startMinute)
            o.put("endHour", r.endHour)
            o.put("endMinute", r.endMinute)
            o.put("brightnessPercent", r.brightnessPercent)
            o.put("themeAction", r.themeAction.name)
            o.put("restoreBrightnessPercent", r.restoreBrightnessPercent)
            o.put("restoreThemeAction", r.restoreThemeAction.name)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun deserializeRoutines(json: String): List<BrightnessThemeRoutine> {
        val list = mutableListOf<BrightnessThemeRoutine>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    BrightnessThemeRoutine(
                        id = o.optString("id", java.util.UUID.randomUUID().toString()),
                        name = o.optString("name", "Routine"),
                        enabled = o.optBoolean("enabled", true),
                        startHour = o.optInt("startHour", 22),
                        startMinute = o.optInt("startMinute", 0),
                        endHour = o.optInt("endHour", 7),
                        endMinute = o.optInt("endMinute", 0),
                        brightnessPercent = o.optInt("brightnessPercent", 20),
                        themeAction = runCatching {
                            RoutineThemeAction.valueOf(o.optString("themeAction", "DARK"))
                        }.getOrDefault(RoutineThemeAction.DARK),
                        restoreBrightnessPercent = o.optInt("restoreBrightnessPercent", 65),
                        restoreThemeAction = runCatching {
                            RoutineThemeAction.valueOf(o.optString("restoreThemeAction", "LIGHT"))
                        }.getOrDefault(RoutineThemeAction.LIGHT)
                    )
                )
            }
        }
        return list
    }

    private fun serializeVolumeRules(list: List<PerAppVolumeRule>): String {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("packageName", r.packageName)
            o.put("appLabel", r.appLabel)
            o.put("volumePercent", r.volumePercent)
            o.put("enabled", r.enabled)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun deserializeVolumeRules(json: String): List<PerAppVolumeRule> {
        val list = mutableListOf<PerAppVolumeRule>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PerAppVolumeRule(
                        packageName = o.optString("packageName"),
                        appLabel = o.optString("appLabel"),
                        volumePercent = o.optInt("volumePercent", 70),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
        }
        return list
    }

    private fun serializePerAppLighting(list: List<PerAppLightingConfig>): String {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("packageName", r.packageName)
            o.put("appLabel", r.appLabel)
            o.put("enabled", r.enabled)
            o.put("colorArgb", r.colorArgb)
            o.put("style", r.style?.name ?: "")
            arr.put(o)
        }
        return arr.toString()
    }

    private fun deserializePerAppLighting(json: String): List<PerAppLightingConfig> {
        val list = mutableListOf<PerAppLightingConfig>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val styleStr = o.optString("style", "")
                val parsedStyle = if (styleStr.isNotBlank()) {
                    runCatching { NotificationLightingStyle.valueOf(styleStr) }.getOrNull()
                } else null
                list.add(
                    PerAppLightingConfig(
                        packageName = o.optString("packageName"),
                        appLabel = o.optString("appLabel"),
                        enabled = o.optBoolean("enabled", true),
                        colorArgb = o.optLong("colorArgb", 0xFF64D2FFL),
                        style = parsedStyle
                    )
                )
            }
        }
        return list
    }

    companion object {
        private const val KEY_BRIGHTNESS_ROUTINES = "brightness_theme_routines"
        private const val KEY_NL_INTENSITY = "nl_intensity"
        private const val KEY_NL_SCHEDULE_ENABLED = "nl_schedule_enabled"
        private const val KEY_NL_START_H = "nl_start_h"
        private const val KEY_NL_START_M = "nl_start_m"
        private const val KEY_NL_END_H = "nl_end_h"
        private const val KEY_NL_END_M = "nl_end_m"
        private const val KEY_NL_APPS_ENABLED = "nl_apps_enabled"
        private const val KEY_NL_APPS_SET = "nl_apps_set"
        private const val KEY_PER_APP_VOLUMES = "per_app_volume_rules"
        private const val KEY_PER_APP_VOL_ENABLED = "per_app_vol_enabled"
        private const val KEY_FASTER_VOL_SWITCHING = "faster_vol_switching"
        private const val KEY_FASTER_VOL_STEP = "faster_vol_step"
        private const val KEY_DISABLE_SAFE_VOL = "disable_safe_vol"

        private const val KEY_BAT_LOW_POWER = "bat_low_power"
        private const val KEY_BAT_AUTO_LP_ENABLED = "bat_auto_lp_enabled"
        private const val KEY_BAT_AUTO_LP_THRESHOLD = "bat_auto_lp_threshold"
        private const val KEY_BAT_BRIGHT_RED_ENABLED = "bat_bright_red_enabled"
        private const val KEY_BAT_BRIGHT_RED_THRESHOLD = "bat_bright_red_threshold"
        private const val KEY_BAT_BRIGHT_RED_PCT = "bat_bright_red_pct"
        private const val KEY_BAT_PIXEL_GRID_ENABLED = "bat_pixel_grid_enabled"
        private const val KEY_BAT_PIXEL_GRID_SPACING = "bat_pixel_grid_spacing"
        private const val KEY_BAT_SHORT_TIMEOUT = "bat_short_timeout"
        private const val KEY_BAT_DISABLE_AOD = "bat_disable_aod"
        private const val KEY_BAT_DISABLE_ANIMS = "bat_disable_anims"
        private const val KEY_BAT_DARK_MODE = "bat_dark_mode"
        private const val KEY_BAT_RESTRICT_SYNC = "bat_restrict_sync"

        private const val KEY_LIGHT_ENABLED = "light_enabled"
        private const val KEY_LIGHT_STYLE = "light_style"
        private const val KEY_LIGHT_COLOR_MODE = "light_color_mode"
        private const val KEY_LIGHT_CUSTOM_COLOR = "light_custom_color"
        private const val KEY_LIGHT_CORNER_RADIUS = "light_corner_radius"
        private const val KEY_LIGHT_THICKNESS = "light_thickness"
        private const val KEY_LIGHT_DURATION = "light_duration"
        private const val KEY_LIGHT_SPEED = "light_speed"
        private const val KEY_LIGHT_REPEAT = "light_repeat"
        private const val KEY_LIGHT_SCREEN_ON = "light_screen_on"
        private const val KEY_LIGHT_LOCK_SCREEN = "light_lock_screen"
        private const val KEY_LIGHT_PER_APP = "light_per_app"

        @Volatile
        private var instance: SystemToolsManager? = null

        fun getInstance(context: Context): SystemToolsManager {
            return instance ?: synchronized(this) {
                instance ?: SystemToolsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

