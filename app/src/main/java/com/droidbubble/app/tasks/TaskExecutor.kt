package com.droidbubble.app.tasks

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.droidbubble.app.R
import com.droidbubble.app.island.IslandStateManager
import com.droidbubble.app.model.NotificationInfo
import com.droidbubble.app.service.IslandAccessibilityService
import com.droidbubble.app.service.NotificationReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object TaskExecutor {

    fun execute(context: Context, shortcut: TaskShortcut) {
        val appContext = context.applicationContext
        val repository = TasksRepository.getInstance(appContext)

        CoroutineScope(Dispatchers.Default).launch {
            delay(100L)
            val localVars = mutableMapOf<String, String>()
            executeBlockRange(
                context = appContext,
                repository = repository,
                localVars = localVars,
                actions = shortcut.actions,
                startIndex = 0,
                endIndex = shortcut.actions.size
            )

            if (shortcut.showInBubble) {
                IslandStateManager.triggerTaskConfirmation(shortcut.iconName, shortcut.name)
            }
        }
    }

    private suspend fun executeBlockRange(
        context: Context,
        repository: TasksRepository,
        localVars: MutableMap<String, String>,
        actions: List<TaskAction>,
        startIndex: Int,
        endIndex: Int
    ) {
        var i = startIndex
        while (i < endIndex) {
            val action = actions[i]
            when (action) {
                is TaskAction.RepeatStart -> {
                    val matchEnd = findMatchingRepeatEnd(actions, i, endIndex)
                    val repeatCount = resolveNumber(action.countExpression, localVars, repository)
                        .roundToInt()
                        .coerceIn(0, 1000)

                    if (matchEnd > i) {
                        for (iteration in 0 until repeatCount) {
                            localVars["index"] = iteration.toString()
                            localVars["iteration"] = (iteration + 1).toString()
                            executeBlockRange(
                                context = context,
                                repository = repository,
                                localVars = localVars,
                                actions = actions,
                                startIndex = i + 1,
                                endIndex = matchEnd
                            )
                        }
                        i = matchEnd + 1
                    } else {
                        // Unmatched RepeatStart: execute body to the end of range
                        for (iteration in 0 until repeatCount) {
                            localVars["index"] = iteration.toString()
                            localVars["iteration"] = (iteration + 1).toString()
                            executeBlockRange(
                                context = context,
                                repository = repository,
                                localVars = localVars,
                                actions = actions,
                                startIndex = i + 1,
                                endIndex = endIndex
                            )
                        }
                        i = endIndex
                    }
                }
                is TaskAction.RepeatEnd -> {
                    i++
                }
                else -> {
                    executeSingleAction(context, repository, localVars, action)
                    if (action !is TaskAction.WaitBlock &&
                        action !is TaskAction.SetVariable &&
                        action !is TaskAction.AddToVariable &&
                        action !is TaskAction.MathOperation
                    ) {
                        delay(80L)
                    }
                    i++
                }
            }
        }
    }

    private fun findMatchingRepeatEnd(actions: List<TaskAction>, repeatStartIndex: Int, limitIndex: Int): Int {
        var depth = 0
        for (idx in repeatStartIndex until limitIndex) {
            when (actions[idx]) {
                is TaskAction.RepeatStart -> depth++
                is TaskAction.RepeatEnd -> {
                    depth--
                    if (depth == 0) return idx
                }
                else -> {}
            }
        }
        return -1
    }

    private fun getVariableValue(name: String, localVars: Map<String, String>, repository: TasksRepository): String {
        val key = name.trim().removePrefix("{").removeSuffix("}").removePrefix("$").trim()
        if (localVars.containsKey(key)) {
            return localVars[key] ?: "0"
        }
        return repository.getGlobalVariable(key)
    }

    private fun setVariableValue(
        name: String,
        value: String,
        scope: VariableScope,
        localVars: MutableMap<String, String>,
        repository: TasksRepository
    ) {
        val key = name.trim().removePrefix("{").removeSuffix("}").removePrefix("$").trim()
        if (key.isEmpty()) return
        when (scope) {
            VariableScope.LOCAL -> localVars[key] = value
            VariableScope.GLOBAL -> repository.setGlobalVariable(key, value)
        }
    }

    private fun interpolateText(text: String, localVars: Map<String, String>, repository: TasksRepository): String {
        var result = text
        val braceRegex = Regex("\\{([A-Za-z0-9_]+)\\}")
        result = braceRegex.replace(result) { match ->
            val varName = match.groupValues[1]
            getVariableValue(varName, localVars, repository)
        }
        val dollarRegex = Regex("\\$([A-Za-z0-9_]+)")
        result = dollarRegex.replace(result) { match ->
            val varName = match.groupValues[1]
            getVariableValue(varName, localVars, repository)
        }
        return result
    }

    private fun resolveNumber(expr: String, localVars: Map<String, String>, repository: TasksRepository): Double {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) return 0.0
        trimmed.toDoubleOrNull()?.let { return it }
        val interpolated = interpolateText(trimmed, localVars, repository).trim()
        interpolated.toDoubleOrNull()?.let { return it }
        val directVal = getVariableValue(trimmed, localVars, repository)
        return directVal.toDoubleOrNull() ?: 0.0
    }

    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) return "0"
        val rounded = value.roundToLong()
        return if (abs(value - rounded.toDouble()) < 1e-6) {
            rounded.toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    private suspend fun executeSingleAction(
        context: Context,
        repository: TasksRepository,
        localVars: MutableMap<String, String>,
        action: TaskAction
    ) {
        when (action) {
            is TaskAction.SetVariable -> {
                val resolved = if (action.value.trim().toDoubleOrNull() != null) {
                    action.value.trim()
                } else {
                    val interp = interpolateText(action.value, localVars, repository)
                    interp
                }
                setVariableValue(action.name, resolved, action.scope, localVars, repository)
            }
            is TaskAction.AddToVariable -> {
                val key = action.name.trim().removePrefix("{").removeSuffix("}").removePrefix("$").trim()
                val currentNum = when (action.scope) {
                    VariableScope.LOCAL -> (localVars[key] ?: repository.getGlobalVariable(key)).toDoubleOrNull() ?: 0.0
                    VariableScope.GLOBAL -> repository.getGlobalVariable(key).toDoubleOrNull() ?: 0.0
                }
                val delta = resolveNumber(action.amount, localVars, repository)
                val updated = formatNumber(currentNum + delta)
                setVariableValue(key, updated, action.scope, localVars, repository)
            }
            is TaskAction.MathOperation -> {
                val left = resolveNumber(action.leftOperand, localVars, repository)
                val right = resolveNumber(action.rightOperand, localVars, repository)
                val result = when (action.operator) {
                    MathOperator.ADD -> left + right
                    MathOperator.SUBTRACT -> left - right
                    MathOperator.MULTIPLY -> left * right
                    MathOperator.DIVIDE -> if (abs(right) < 1e-9) 0.0 else left / right
                    MathOperator.MODULO -> if (abs(right) < 1e-9) 0.0 else left % right
                }
                setVariableValue(
                    name = action.targetVar,
                    value = formatNumber(result),
                    scope = action.targetScope,
                    localVars = localVars,
                    repository = repository
                )
            }
            is TaskAction.WaitBlock -> {
                val rawDuration = resolveNumber(action.durationValue, localVars, repository).coerceAtLeast(0.0)
                val delayMs = if (action.isMilliseconds) {
                    rawDuration.roundToLong()
                } else {
                    (rawDuration * 1000.0).roundToLong()
                }
                delay(delayMs.coerceIn(0L, 300_000L))
            }
            is TaskAction.LiveProgressNotification -> {
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                    val channelId = "droidtools_tasks_live_progress"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(
                            channelId,
                            "Tasks Live Progress",
                            NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            description = "Live progress notifications triggered and updated by Tasks"
                            setSound(null, null)
                            enableVibration(false)
                        }
                        nm.createNotificationChannel(channel)
                    }

                    val currentProgress = resolveNumber(action.currentValue, localVars, repository).roundToInt().coerceAtLeast(0)
                    val maxProgress = resolveNumber(action.maxValue, localVars, repository).roundToInt().coerceAtLeast(1)
                    val clampedProgress = currentProgress.coerceIn(0, maxProgress)

                    val resolvedTitle = interpolateText(action.title.ifBlank { "Progress" }, localVars, repository)
                    val resolvedText = interpolateText(
                        action.text.ifBlank { "$clampedProgress / $maxProgress" },
                        localVars,
                        repository
                    )

                    val notifKey = "task_live_progress_${action.notificationId.ifBlank { "default" }}"
                    val notifId = 80000 + (abs(notifKey.hashCode()) % 10000)

                    if (action.dismissWhenComplete && clampedProgress >= maxProgress) {
                        nm.cancel(notifId)
                        IslandStateManager.removeNotification(notifKey)
                        return
                    }

                    val isOngoing = clampedProgress < maxProgress
                    val notif = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_notification_island)
                        .setContentTitle(resolvedTitle)
                        .setContentText(resolvedText)
                        .setProgress(maxProgress, clampedProgress, false)
                        .setOngoing(isOngoing)
                        .setOnlyAlertOnce(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setAutoCancel(!isOngoing)
                        .build()

                    nm.notify(notifId, notif)

                    // Also update Droidbubble Island live progress directly so it shows smoothly in the island!
                    IslandStateManager.updateProgress(
                        NotificationInfo(
                            key = notifKey,
                            packageName = context.packageName,
                            appName = "Droidtools",
                            title = resolvedTitle,
                            text = resolvedText,
                            progress = clampedProgress,
                            maxProgress = maxProgress,
                            isIndeterminate = false,
                            isOngoing = isOngoing
                        )
                    )
                }
            }
            is TaskAction.OpenApp -> {
                runCatching {
                    val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                    }
                }
            }
            is TaskAction.Wifi -> {
                val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val currentOn = wm?.isWifiEnabled == true
                val targetOn = when (action.type) {
                    ToggleActionType.TOGGLE -> !currentOn
                    ToggleActionType.TURN_ON -> true
                    ToggleActionType.TURN_OFF -> false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(panelIntent)
                    }.onFailure {
                        runCatching {
                            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { wm?.isWifiEnabled = targetOn }
                }
            }
            is TaskAction.MobileData -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(panelIntent)
                        return
                    }
                }
                runCatching {
                    val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure {
                    runCatching {
                        val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            }
            is TaskAction.Flashlight -> {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
                runCatching {
                    val id = cm.cameraIdList.firstOrNull { camId ->
                        cm.getCameraCharacteristics(camId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: return
                    val currentOn = IslandStateManager.isFlashlightOn.value
                    val targetOn = when (action.type) {
                        ToggleActionType.TOGGLE -> !currentOn
                        ToggleActionType.TURN_ON -> true
                        ToggleActionType.TURN_OFF -> false
                    }
                    cm.setTorchMode(id, targetOn)
                }
            }
            is TaskAction.LowPower -> {
                runCatching {
                    val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
            is TaskAction.AirplaneMode -> {
                runCatching {
                    val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
            is TaskAction.Bluetooth -> {
                runCatching {
                    @Suppress("DEPRECATION")
                    val btAdapter = BluetoothAdapter.getDefaultAdapter()
                    if (btAdapter != null) {
                        val currentOn = btAdapter.isEnabled
                        val targetOn = when (action.type) {
                            ToggleActionType.TOGGLE -> !currentOn
                            ToggleActionType.TURN_ON -> true
                            ToggleActionType.TURN_OFF -> false
                        }
                        @Suppress("DEPRECATION")
                        val success = if (targetOn) btAdapter.enable() else btAdapter.disable()
                        if (!success) {
                            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    } else {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            }
            is TaskAction.RingerMode -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                runCatching {
                    val current = am.ringerMode
                    val target = when (action.type) {
                        RingerActionType.SET_RING -> AudioManager.RINGER_MODE_NORMAL
                        RingerActionType.SET_VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                        RingerActionType.TOGGLE -> if (current == AudioManager.RINGER_MODE_VIBRATE) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE
                    }
                    am.ringerMode = target
                }.onFailure {
                    runCatching {
                        val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            }
            is TaskAction.SetBrightness -> {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.System.canWrite(context)) {
                            val brightness = (action.level * 255 / 100).coerceIn(0, 255)
                            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                        } else {
                            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    } else {
                        val brightness = (action.level * 255 / 100).coerceIn(0, 255)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                    }
                }
            }
            is TaskAction.SetVolume -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                runCatching {
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (action.level * maxVol / 100).coerceIn(0, maxVol)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                }
            }
            is TaskAction.TakeScreenshot -> {
                runCatching {
                    val accessibility = IslandAccessibilityService.instance
                    if (accessibility != null) {
                        accessibility.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    }
                }
            }
            is TaskAction.GoHome -> {
                runCatching {
                    val accessibility = IslandAccessibilityService.instance
                    val handled = accessibility?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
                    if (!handled) {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(homeIntent)
                    }
                }
            }
            is TaskAction.MediaControls -> {
                runCatching {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    when (action.type) {
                        MediaActionType.PLAY -> {
                            if (IslandStateManager.onPlayPause != null) {
                                IslandStateManager.onPlayPause?.invoke()
                            } else {
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                            }
                        }
                        MediaActionType.PAUSE -> {
                            if (IslandStateManager.onPauseMedia != null) {
                                IslandStateManager.onPauseMedia?.invoke()
                            } else {
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                            }
                        }
                        MediaActionType.TOGGLE_PLAYBACK -> {
                            if (IslandStateManager.onPlayPause != null) {
                                IslandStateManager.onPlayPause?.invoke()
                            } else {
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                            }
                        }
                        MediaActionType.SKIP -> {
                            if (IslandStateManager.onSkipNext != null) {
                                IslandStateManager.onSkipNext?.invoke()
                            } else {
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                            }
                        }
                    }
                }
            }
            is TaskAction.SendNotification -> {
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                    val channelId = "droidtools_tasks_notifications"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(
                            channelId,
                            "Tasks Notifications",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "Notifications triggered by Tasks"
                        }
                        nm.createNotificationChannel(channel)
                    }
                    val resolvedTitle = interpolateText(action.title.ifBlank { "Task Notification" }, localVars, repository)
                    val resolvedText = interpolateText(action.text.ifBlank { "Task executed" }, localVars, repository)
                    val notif = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_notification_island)
                        .setContentTitle(resolvedTitle)
                        .setContentText(resolvedText)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                    nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
                }
            }
            is TaskAction.RemoveNotification -> {
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val resolvedTarget = interpolateText(action.targetTagOrTitle, localVars, repository).trim()
                    val queryLower = resolvedTarget.lowercase()

                    if (action.removeAll || resolvedTarget.isBlank()) {
                        // Cancel all active notifications posted by this app (excluding the persistent FGS notification id=1001)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null) {
                            nm.activeNotifications?.forEach { sbn ->
                                if (sbn.id != 1001) {
                                    nm.cancel(sbn.tag, sbn.id)
                                }
                            }
                        }
                        IslandStateManager.activeProgress.value?.let { prog ->
                            IslandStateManager.removeNotification(prog.key)
                        }
                        IslandStateManager.dismissCurrentNotification()
                        NotificationReceiverService.instance?.cancelMatchingNotifications("", true)
                    } else {
                        // 1. Cancel matching LiveProgressNotification by exact tag ID
                        val notifKey = "task_live_progress_$resolvedTarget"
                        val notifId = 80000 + (abs(notifKey.hashCode()) % 10000)
                        nm?.cancel(notifId)
                        IslandStateManager.removeNotification(notifKey)

                        // Also check if user passed "default" or if any active app notification matches
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null) {
                            nm.activeNotifications?.forEach { sbn ->
                                if (sbn.id != 1001) {
                                    val extras = sbn.notification?.extras
                                    val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.lowercase() ?: ""
                                    val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.lowercase() ?: ""
                                    if (title.contains(queryLower) || text.contains(queryLower) || sbn.id == notifId) {
                                        nm.cancel(sbn.tag, sbn.id)
                                    }
                                }
                            }
                        }

                        // 2. Remove from IslandStateManager if activeProgress or activeNotification matches
                        IslandStateManager.activeProgress.value?.let { prog ->
                            if (prog.key.lowercase().contains(queryLower) ||
                                prog.title.lowercase().contains(queryLower) ||
                                prog.text.lowercase().contains(queryLower) ||
                                prog.appName.lowercase().contains(queryLower)
                            ) {
                                IslandStateManager.removeNotification(prog.key)
                            }
                        }
                        IslandStateManager.activeNotification.value?.let { notif ->
                            if (notif.key.lowercase().contains(queryLower) ||
                                notif.title.lowercase().contains(queryLower) ||
                                notif.text.lowercase().contains(queryLower) ||
                                notif.appName.lowercase().contains(queryLower)
                            ) {
                                IslandStateManager.removeNotification(notif.key)
                            }
                        }

                        // 3. Cancel matching system notifications via NotificationReceiverService
                        NotificationReceiverService.instance?.cancelMatchingNotifications(resolvedTarget, false)
                    }
                }
            }
            is TaskAction.RepeatStart, is TaskAction.RepeatEnd -> {}
        }
    }
}
