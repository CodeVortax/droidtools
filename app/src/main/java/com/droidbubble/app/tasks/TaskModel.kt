package com.droidbubble.app.tasks

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

enum class ToggleActionType {
    TOGGLE,
    TURN_ON,
    TURN_OFF
}

enum class RingerActionType {
    SET_RING,
    SET_VIBRATE,
    TOGGLE
}

enum class MediaActionType {
    PLAY,
    PAUSE,
    TOGGLE_PLAYBACK,
    SKIP
}

enum class VariableScope {
    LOCAL,
    GLOBAL
}

enum class MathOperator(val symbol: String, val label: String) {
    ADD("+", "Add (+)"),
    SUBTRACT("-", "Subtract (-)"),
    MULTIPLY("×", "Multiply (×)"),
    DIVIDE("÷", "Divide (÷)"),
    MODULO("%", "Modulo (%)")
}

enum class DeviceEventType(val displayName: String, val description: String) {
    WIFI_CONNECTED("Wi-Fi Connected", "When connected to a Wi-Fi network"),
    WIFI_DISCONNECTED("Wi-Fi Disconnected", "When disconnected from Wi-Fi"),
    CHARGING_CONNECTED("Charging Started", "When charger is plugged in"),
    CHARGING_DISCONNECTED("Charging Stopped", "When charger is unplugged"),
    BATTERY_AT_LEVEL("Battery at Exact Level", "When battery reaches exact percentage"),
    BATTERY_BELOW_LEVEL("Battery Falls Below", "When battery drops to or below percentage"),
    BATTERY_ABOVE_LEVEL("Battery Rises Above", "When battery reaches or exceeds percentage"),
    BLUETOOTH_CONNECTED("Bluetooth Connected", "When a Bluetooth device connects"),
    BLUETOOTH_DISCONNECTED("Bluetooth Disconnected", "When a Bluetooth device disconnects"),
    SCREEN_UNLOCKED("Device Unlocked", "When the screen is unlocked"),
    SCREEN_OFF("Screen Turned Off", "When the display turns off"),
    AIRPLANE_MODE_ON("Aeroplane Mode On", "When Aeroplane mode is enabled"),
    AIRPLANE_MODE_OFF("Aeroplane Mode Off", "When Aeroplane mode is disabled"),
    RINGER_SILENT("Ringer Set to Silent", "When sound mode switches to Silent"),
    RINGER_VIBRATE("Ringer Set to Vibrate", "When sound mode switches to Vibrate"),
    RINGER_NORMAL("Ringer Set to Ring", "When sound mode switches to Normal Ring");

    val requiresBatteryThreshold: Boolean
        get() = this == BATTERY_AT_LEVEL || this == BATTERY_BELOW_LEVEL || this == BATTERY_ABOVE_LEVEL
}

sealed class TaskTrigger {
    abstract val id: String

    data class TimeSchedule(
        override val id: String = java.util.UUID.randomUUID().toString(),
        val hour: Int = 8,
        val minute: Int = 0,
        // ISO days of week: 1 = Mon, 2 = Tue, 3 = Wed, 4 = Thu, 5 = Fri, 6 = Sat, 7 = Sun
        val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)
    ) : TaskTrigger()

    data class SpecificDate(
        override val id: String = java.util.UUID.randomUUID().toString(),
        val year: Int = 2026,
        val month: Int = 1, // 1..12
        val dayOfMonth: Int = 1,
        val hour: Int = 9,
        val minute: Int = 0
    ) : TaskTrigger()

    data class DeviceEvent(
        override val id: String = java.util.UUID.randomUUID().toString(),
        val eventType: DeviceEventType = DeviceEventType.WIFI_CONNECTED,
        val batteryThreshold: Int = 20
    ) : TaskTrigger()

    fun getDisplayName(): String {
        return when (this) {
            is TimeSchedule -> {
                val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
                val dayNames = mapOf(
                    1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
                    5 to "Fri", 6 to "Sat", 7 to "Sun"
                )
                val daysStr = when {
                    daysOfWeek.size >= 7 || daysOfWeek.isEmpty() -> "Every day"
                    daysOfWeek == setOf(1, 2, 3, 4, 5) -> "Weekdays"
                    daysOfWeek == setOf(6, 7) -> "Weekends"
                    else -> daysOfWeek.sorted().mapNotNull { dayNames[it] }.joinToString(", ")
                }
                "At $timeStr • $daysStr"
            }
            is SpecificDate -> {
                val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, dayOfMonth)
                val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
                "Date: $dateStr at $timeStr"
            }
            is DeviceEvent -> {
                if (eventType.requiresBatteryThreshold) {
                    "${eventType.displayName} ($batteryThreshold%)"
                } else {
                    eventType.displayName
                }
            }
        }
    }

    fun getIcon(): ImageVector {
        return when (this) {
            is TimeSchedule -> Icons.Default.Schedule
            is SpecificDate -> Icons.Default.Event
            is DeviceEvent -> when (eventType) {
                DeviceEventType.WIFI_CONNECTED, DeviceEventType.WIFI_DISCONNECTED -> Icons.Default.Wifi
                DeviceEventType.CHARGING_CONNECTED, DeviceEventType.CHARGING_DISCONNECTED -> Icons.Default.Bolt
                DeviceEventType.BATTERY_AT_LEVEL, DeviceEventType.BATTERY_BELOW_LEVEL, DeviceEventType.BATTERY_ABOVE_LEVEL -> Icons.Default.BatteryStd
                DeviceEventType.BLUETOOTH_CONNECTED, DeviceEventType.BLUETOOTH_DISCONNECTED -> Icons.Default.Bluetooth
                DeviceEventType.SCREEN_UNLOCKED, DeviceEventType.SCREEN_OFF -> Icons.Default.LockOpen
                DeviceEventType.AIRPLANE_MODE_ON, DeviceEventType.AIRPLANE_MODE_OFF -> Icons.Default.AirplanemodeActive
                DeviceEventType.RINGER_SILENT, DeviceEventType.RINGER_VIBRATE, DeviceEventType.RINGER_NORMAL -> Icons.Default.NotificationsActive
            }
        }
    }
}

sealed class TaskAction {
    data class OpenApp(val packageName: String, val appLabel: String) : TaskAction()
    data class Wifi(val type: ToggleActionType) : TaskAction()
    data class MobileData(val type: ToggleActionType) : TaskAction()
    data class Flashlight(val type: ToggleActionType) : TaskAction()
    data class LowPower(val type: ToggleActionType) : TaskAction()
    data class AirplaneMode(val type: ToggleActionType) : TaskAction()
    data class Bluetooth(val type: ToggleActionType) : TaskAction()
    data class RingerMode(val type: RingerActionType) : TaskAction()
    data class SetBrightness(val level: Int) : TaskAction()
    data class SetVolume(val level: Int) : TaskAction()
    object TakeScreenshot : TaskAction()
    object GoHome : TaskAction()
    data class MediaControls(val type: MediaActionType) : TaskAction()
    data class SendNotification(val title: String, val text: String) : TaskAction()

    // Advanced Task Actions
    data class SetVariable(
        val name: String = "count",
        val value: String = "0",
        val scope: VariableScope = VariableScope.LOCAL
    ) : TaskAction()

    data class AddToVariable(
        val name: String = "count",
        val amount: String = "1",
        val scope: VariableScope = VariableScope.LOCAL
    ) : TaskAction()

    data class MathOperation(
        val targetVar: String = "result",
        val targetScope: VariableScope = VariableScope.LOCAL,
        val leftOperand: String = "0",
        val operator: MathOperator = MathOperator.ADD,
        val rightOperand: String = "1"
    ) : TaskAction()

    data class RepeatStart(
        val countExpression: String = "5",
        val blockId: String = java.util.UUID.randomUUID().toString()
    ) : TaskAction()

    data class RepeatEnd(
        val blockId: String = ""
    ) : TaskAction()

    data class LiveProgressNotification(
        val notificationId: String = "progress_1",
        val title: String = "Progress",
        val text: String = "{count}%",
        val currentValue: String = "{count}",
        val maxValue: String = "100",
        val dismissWhenComplete: Boolean = false
    ) : TaskAction()

    data class RemoveNotification(
        val targetTagOrTitle: String = "progress_1",
        val removeAll: Boolean = false
    ) : TaskAction()

    data class WaitBlock(
        val durationValue: String = "1",
        val isMilliseconds: Boolean = false
    ) : TaskAction()

    fun getDisplayName(): String {
        return when (this) {
            is OpenApp -> "Open $appLabel"
            is Wifi -> when (type) {
                ToggleActionType.TOGGLE -> "Toggle Wi-Fi"
                ToggleActionType.TURN_ON -> "Turn On Wi-Fi"
                ToggleActionType.TURN_OFF -> "Turn Off Wi-Fi"
            }
            is MobileData -> when (type) {
                ToggleActionType.TOGGLE -> "Toggle Mobile Data"
                ToggleActionType.TURN_ON -> "Turn On Mobile Data"
                ToggleActionType.TURN_OFF -> "Turn Off Mobile Data"
            }
            is Flashlight -> when (type) {
                ToggleActionType.TOGGLE -> "Toggle Flashlight"
                ToggleActionType.TURN_ON -> "Turn On Flashlight"
                ToggleActionType.TURN_OFF -> "Turn Off Flashlight"
            }
            is LowPower -> when (type) {
                ToggleActionType.TOGGLE -> "Toggle Low Power Mode"
                ToggleActionType.TURN_ON -> "Turn On Low Power Mode"
                ToggleActionType.TURN_OFF -> "Turn Off Low Power Mode"
            }
            is AirplaneMode -> when (type) {
                ToggleActionType.TOGGLE -> "Toggle Aeroplane Mode"
                ToggleActionType.TURN_ON -> "Turn On Aeroplane Mode"
                ToggleActionType.TURN_OFF -> "Turn Off Aeroplane Mode"
            }
            is Bluetooth -> when (type) {
                ToggleActionType.TOGGLE -> "Toggle Bluetooth"
                ToggleActionType.TURN_ON -> "Turn On Bluetooth"
                ToggleActionType.TURN_OFF -> "Turn Off Bluetooth"
            }
            is RingerMode -> when (type) {
                RingerActionType.SET_RING -> "Set Ring Mode"
                RingerActionType.SET_VIBRATE -> "Set Vibrate Mode"
                RingerActionType.TOGGLE -> "Toggle Ring / Vibrate"
            }
            is SetBrightness -> "Set brightness to $level%"
            is SetVolume -> "Set volume to $level%"
            is TakeScreenshot -> "Take screenshot"
            is GoHome -> "Go to home screen"
            is MediaControls -> when (type) {
                MediaActionType.PLAY -> "Play media"
                MediaActionType.PAUSE -> "Pause media"
                MediaActionType.TOGGLE_PLAYBACK -> "Toggle media playback"
                MediaActionType.SKIP -> "Skip to next track"
            }
            is SendNotification -> "Send notification"
            is SetVariable -> "Variable (${if (scope == VariableScope.GLOBAL) "Global" else "Local"}): $name = $value"
            is AddToVariable -> "Add $amount to $name (${if (scope == VariableScope.GLOBAL) "Global" else "Local"})"
            is MathOperation -> "Math: $targetVar = $leftOperand ${operator.symbol} $rightOperand"
            is RepeatStart -> "Repeat Start ($countExpression times)"
            is RepeatEnd -> "Repeat End"
            is LiveProgressNotification -> "Live Progress Notification ($currentValue / $maxValue)"
            is RemoveNotification -> if (removeAll || targetTagOrTitle.isBlank()) "Remove all task & active notifications" else "Remove notification ($targetTagOrTitle)"
            is WaitBlock -> "Wait $durationValue ${if (isMilliseconds) "ms" else "s"}"
        }
    }

    fun getIcon(): ImageVector {
        return when (this) {
            is OpenApp -> Icons.Default.Apps
            is Wifi -> Icons.Default.Wifi
            is MobileData -> Icons.Default.SignalCellularAlt
            is Flashlight -> Icons.Default.FlashlightOn
            is LowPower -> Icons.Default.BatteryChargingFull
            is AirplaneMode -> Icons.Default.AirplanemodeActive
            is Bluetooth -> Icons.Default.Bluetooth
            is RingerMode -> when (type) {
                RingerActionType.SET_VIBRATE -> Icons.Default.Vibration
                else -> Icons.Default.NotificationsActive
            }
            is SetBrightness -> Icons.Default.BrightnessMedium
            is SetVolume -> Icons.Default.VolumeUp
            is TakeScreenshot -> Icons.Default.Screenshot
            is GoHome -> Icons.Default.Home
            is MediaControls -> when (type) {
                MediaActionType.PLAY -> Icons.Default.PlayArrow
                MediaActionType.PAUSE -> Icons.Default.Pause
                MediaActionType.TOGGLE_PLAYBACK -> Icons.Default.PlayCircleOutline
                MediaActionType.SKIP -> Icons.Default.SkipNext
            }
            is SendNotification -> Icons.Default.NotificationAdd
            is SetVariable -> Icons.Default.DataObject
            is AddToVariable -> Icons.Default.ExposurePlus1
            is MathOperation -> Icons.Default.Calculate
            is RepeatStart -> Icons.Default.Repeat
            is RepeatEnd -> Icons.Default.SubdirectoryArrowRight
            is LiveProgressNotification -> Icons.Default.LinearScale
            is RemoveNotification -> Icons.Default.NotificationsOff
            is WaitBlock -> Icons.Default.HourglassBottom
        }
    }
}

data class TaskShortcut(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val iconName: String = "Bolt",
    val showInBubble: Boolean = true,
    val showInDroidbar: Boolean = false,
    val automationEnabled: Boolean = true,
    val triggers: List<TaskTrigger> = emptyList(),
    val actions: List<TaskAction> = emptyList()
)
