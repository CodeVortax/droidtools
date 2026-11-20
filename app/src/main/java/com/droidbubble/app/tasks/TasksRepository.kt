package com.droidbubble.app.tasks

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TasksRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("droidtools_tasks_prefs", Context.MODE_PRIVATE)

    private val _shortcuts = MutableStateFlow<List<TaskShortcut>>(emptyList())
    val shortcuts: StateFlow<List<TaskShortcut>> = _shortcuts.asStateFlow()

    init {
        loadShortcuts()
    }

    fun getGlobalVariable(name: String): String {
        val key = name.trim()
        if (key.isEmpty()) return "0"
        val mapJson = prefs.getString(KEY_GLOBAL_VARS, null) ?: return "0"
        return runCatching {
            val obj = JSONObject(mapJson)
            obj.optString(key, "0")
        }.getOrDefault("0")
    }

    fun setGlobalVariable(name: String, value: String) {
        val key = name.trim()
        if (key.isEmpty()) return
        runCatching {
            val mapJson = prefs.getString(KEY_GLOBAL_VARS, null)
            val obj = if (mapJson.isNullOrBlank()) JSONObject() else JSONObject(mapJson)
            obj.put(key, value)
            prefs.edit().putString(KEY_GLOBAL_VARS, obj.toString()).apply()
        }
    }

    fun getAllGlobalVariables(): Map<String, String> {
        val mapJson = prefs.getString(KEY_GLOBAL_VARS, null) ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        runCatching {
            val obj = JSONObject(mapJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.optString(k, "0")
            }
        }
        return result
    }

    private fun loadShortcuts() {
        val json = prefs.getString(KEY_SHORTCUTS, null)
        if (json.isNullOrBlank()) {
            _shortcuts.value = emptyList()
        } else {
            _shortcuts.value = deserialize(json)
        }
    }

    fun saveShortcut(shortcut: TaskShortcut) {
        val current = _shortcuts.value.toMutableList()
        val index = current.indexOfFirst { it.id == shortcut.id }
        if (index >= 0) {
            current[index] = shortcut
        } else {
            current.add(shortcut)
        }
        persist(current)
    }

    fun deleteShortcut(id: String) {
        val current = _shortcuts.value.filter { it.id != id }
        persist(current)
    }

    fun reorderShortcuts(list: List<TaskShortcut>) {
        persist(list)
    }

    private fun persist(list: List<TaskShortcut>) {
        _shortcuts.value = list
        prefs.edit().putString(KEY_SHORTCUTS, serialize(list)).apply()
    }

    private fun serialize(shortcuts: List<TaskShortcut>): String {
        val array = JSONArray()
        for (s in shortcuts) {
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("name", s.name)
            obj.put("iconName", s.iconName)
            obj.put("showInBubble", s.showInBubble)
            obj.put("showInDroidbar", s.showInDroidbar)
            obj.put("automationEnabled", s.automationEnabled)

            val triggersArray = JSONArray()
            for (trigger in s.triggers) {
                val tObj = JSONObject()
                tObj.put("id", trigger.id)
                when (trigger) {
                    is TaskTrigger.TimeSchedule -> {
                        tObj.put("type", "time_schedule")
                        tObj.put("hour", trigger.hour)
                        tObj.put("minute", trigger.minute)
                        val daysArr = JSONArray()
                        trigger.daysOfWeek.sorted().forEach { daysArr.put(it) }
                        tObj.put("daysOfWeek", daysArr)
                    }
                    is TaskTrigger.SpecificDate -> {
                        tObj.put("type", "specific_date")
                        tObj.put("year", trigger.year)
                        tObj.put("month", trigger.month)
                        tObj.put("dayOfMonth", trigger.dayOfMonth)
                        tObj.put("hour", trigger.hour)
                        tObj.put("minute", trigger.minute)
                    }
                    is TaskTrigger.DeviceEvent -> {
                        tObj.put("type", "device_event")
                        tObj.put("eventType", trigger.eventType.name)
                        tObj.put("batteryThreshold", trigger.batteryThreshold)
                    }
                }
                triggersArray.put(tObj)
            }
            obj.put("triggers", triggersArray)

            val actionsArray = JSONArray()
            for (action in s.actions) {
                val aObj = JSONObject()
                when (action) {
                    is TaskAction.OpenApp -> {
                        aObj.put("type", "open_app")
                        aObj.put("packageName", action.packageName)
                        aObj.put("appLabel", action.appLabel)
                    }
                    is TaskAction.Wifi -> {
                        aObj.put("type", "wifi")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.MobileData -> {
                        aObj.put("type", "mobile_data")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.Flashlight -> {
                        aObj.put("type", "flashlight")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.LowPower -> {
                        aObj.put("type", "low_power")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.AirplaneMode -> {
                        aObj.put("type", "airplane_mode")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.Bluetooth -> {
                        aObj.put("type", "bluetooth")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.RingerMode -> {
                        aObj.put("type", "ringer_mode")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.SetBrightness -> {
                        aObj.put("type", "brightness")
                        aObj.put("level", action.level)
                    }
                    is TaskAction.SetVolume -> {
                        aObj.put("type", "volume")
                        aObj.put("level", action.level)
                    }
                    is TaskAction.TakeScreenshot -> {
                        aObj.put("type", "screenshot")
                    }
                    is TaskAction.GoHome -> {
                        aObj.put("type", "go_home")
                    }
                    is TaskAction.MediaControls -> {
                        aObj.put("type", "media_controls")
                        aObj.put("op", action.type.name)
                    }
                    is TaskAction.SendNotification -> {
                        aObj.put("type", "notification")
                        aObj.put("title", action.title)
                        aObj.put("text", action.text)
                    }
                    is TaskAction.SetVariable -> {
                        aObj.put("type", "set_variable")
                        aObj.put("name", action.name)
                        aObj.put("value", action.value)
                        aObj.put("scope", action.scope.name)
                    }
                    is TaskAction.AddToVariable -> {
                        aObj.put("type", "add_to_variable")
                        aObj.put("name", action.name)
                        aObj.put("amount", action.amount)
                        aObj.put("scope", action.scope.name)
                    }
                    is TaskAction.MathOperation -> {
                        aObj.put("type", "math_operation")
                        aObj.put("targetVar", action.targetVar)
                        aObj.put("targetScope", action.targetScope.name)
                        aObj.put("leftOperand", action.leftOperand)
                        aObj.put("operator", action.operator.name)
                        aObj.put("rightOperand", action.rightOperand)
                    }
                    is TaskAction.RepeatStart -> {
                        aObj.put("type", "repeat_start")
                        aObj.put("countExpression", action.countExpression)
                        aObj.put("blockId", action.blockId)
                    }
                    is TaskAction.RepeatEnd -> {
                        aObj.put("type", "repeat_end")
                        aObj.put("blockId", action.blockId)
                    }
                    is TaskAction.LiveProgressNotification -> {
                        aObj.put("type", "live_progress_notification")
                        aObj.put("notificationId", action.notificationId)
                        aObj.put("title", action.title)
                        aObj.put("text", action.text)
                        aObj.put("currentValue", action.currentValue)
                        aObj.put("maxValue", action.maxValue)
                        aObj.put("dismissWhenComplete", action.dismissWhenComplete)
                    }
                    is TaskAction.RemoveNotification -> {
                        aObj.put("type", "remove_notification")
                        aObj.put("targetTagOrTitle", action.targetTagOrTitle)
                        aObj.put("removeAll", action.removeAll)
                    }
                    is TaskAction.WaitBlock -> {
                        aObj.put("type", "wait_block")
                        aObj.put("durationValue", action.durationValue)
                        aObj.put("isMilliseconds", action.isMilliseconds)
                    }
                }
                actionsArray.put(aObj)
            }
            obj.put("actions", actionsArray)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserialize(json: String): List<TaskShortcut> {
        val list = mutableListOf<TaskShortcut>()
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", java.util.UUID.randomUUID().toString())
                val name = obj.optString("name", "Untitled")
                val iconName = obj.optString("iconName", "Bolt")
                val showInBubble = obj.optBoolean("showInBubble", true)
                val showInDroidbar = obj.optBoolean("showInDroidbar", false)
                val automationEnabled = obj.optBoolean("automationEnabled", true)

                val triggersList = mutableListOf<TaskTrigger>()
                val triggersArray = obj.optJSONArray("triggers")
                if (triggersArray != null) {
                    for (t in 0 until triggersArray.length()) {
                        val tObj = triggersArray.getJSONObject(t)
                        val tId = tObj.optString("id", java.util.UUID.randomUUID().toString())
                        val trigger = when (tObj.optString("type")) {
                            "time_schedule" -> {
                                val daysArr = tObj.optJSONArray("daysOfWeek")
                                val daysSet = mutableSetOf<Int>()
                                if (daysArr != null) {
                                    for (d in 0 until daysArr.length()) {
                                        daysSet.add(daysArr.getInt(d))
                                    }
                                } else {
                                    daysSet.addAll(1..7)
                                }
                                TaskTrigger.TimeSchedule(
                                    id = tId,
                                    hour = tObj.optInt("hour", 8),
                                    minute = tObj.optInt("minute", 0),
                                    daysOfWeek = if (daysSet.isEmpty()) setOf(1, 2, 3, 4, 5, 6, 7) else daysSet
                                )
                            }
                            "specific_date" -> {
                                TaskTrigger.SpecificDate(
                                    id = tId,
                                    year = tObj.optInt("year", 2026),
                                    month = tObj.optInt("month", 1),
                                    dayOfMonth = tObj.optInt("dayOfMonth", 1),
                                    hour = tObj.optInt("hour", 9),
                                    minute = tObj.optInt("minute", 0)
                                )
                            }
                            "device_event" -> {
                                val evType = runCatching {
                                    DeviceEventType.valueOf(tObj.optString("eventType", "WIFI_CONNECTED"))
                                }.getOrDefault(DeviceEventType.WIFI_CONNECTED)
                                TaskTrigger.DeviceEvent(
                                    id = tId,
                                    eventType = evType,
                                    batteryThreshold = tObj.optInt("batteryThreshold", 20)
                                )
                            }
                            else -> null
                        }
                        if (trigger != null) triggersList.add(trigger)
                    }
                }

                val actionsList = mutableListOf<TaskAction>()
                val actionsArray = obj.optJSONArray("actions")
                if (actionsArray != null) {
                    for (j in 0 until actionsArray.length()) {
                        val aObj = actionsArray.getJSONObject(j)
                        val type = aObj.optString("type")
                        val op = aObj.optString("op")
                        val action = when (type) {
                            "open_app" -> TaskAction.OpenApp(
                                packageName = aObj.optString("packageName"),
                                appLabel = aObj.optString("appLabel")
                            )
                            "wifi" -> TaskAction.Wifi(runCatching { ToggleActionType.valueOf(op) }.getOrDefault(ToggleActionType.TOGGLE))
                            "mobile_data" -> TaskAction.MobileData(runCatching { ToggleActionType.valueOf(op) }.getOrDefault(ToggleActionType.TOGGLE))
                            "flashlight" -> TaskAction.Flashlight(runCatching { ToggleActionType.valueOf(op) }.getOrDefault(ToggleActionType.TOGGLE))
                            "low_power" -> TaskAction.LowPower(runCatching { ToggleActionType.valueOf(op) }.getOrDefault(ToggleActionType.TOGGLE))
                            "airplane_mode" -> TaskAction.AirplaneMode(runCatching { ToggleActionType.valueOf(op) }.getOrDefault(ToggleActionType.TOGGLE))
                            "bluetooth" -> TaskAction.Bluetooth(runCatching { ToggleActionType.valueOf(op) }.getOrDefault(ToggleActionType.TOGGLE))
                            "ringer_mode" -> TaskAction.RingerMode(runCatching { RingerActionType.valueOf(op) }.getOrDefault(RingerActionType.TOGGLE))
                            "brightness" -> TaskAction.SetBrightness(aObj.optInt("level", 50))
                            "volume" -> TaskAction.SetVolume(aObj.optInt("level", 50))
                            "screenshot" -> TaskAction.TakeScreenshot
                            "go_home" -> TaskAction.GoHome
                            "media_controls" -> TaskAction.MediaControls(runCatching { MediaActionType.valueOf(op) }.getOrDefault(MediaActionType.TOGGLE_PLAYBACK))
                            "notification" -> TaskAction.SendNotification(
                                title = aObj.optString("title", "Task Notification"),
                                text = aObj.optString("text", "Shortcut executed")
                            )
                            "set_variable" -> TaskAction.SetVariable(
                                name = aObj.optString("name", "count"),
                                value = aObj.optString("value", "0"),
                                scope = runCatching { VariableScope.valueOf(aObj.optString("scope", "LOCAL")) }.getOrDefault(VariableScope.LOCAL)
                            )
                            "add_to_variable" -> TaskAction.AddToVariable(
                                name = aObj.optString("name", "count"),
                                amount = aObj.optString("amount", "1"),
                                scope = runCatching { VariableScope.valueOf(aObj.optString("scope", "LOCAL")) }.getOrDefault(VariableScope.LOCAL)
                            )
                            "math_operation" -> TaskAction.MathOperation(
                                targetVar = aObj.optString("targetVar", "result"),
                                targetScope = runCatching { VariableScope.valueOf(aObj.optString("targetScope", "LOCAL")) }.getOrDefault(VariableScope.LOCAL),
                                leftOperand = aObj.optString("leftOperand", "0"),
                                operator = runCatching { MathOperator.valueOf(aObj.optString("operator", "ADD")) }.getOrDefault(MathOperator.ADD),
                                rightOperand = aObj.optString("rightOperand", "1")
                            )
                            "repeat_start" -> TaskAction.RepeatStart(
                                countExpression = aObj.optString("countExpression", "5"),
                                blockId = aObj.optString("blockId", java.util.UUID.randomUUID().toString())
                            )
                            "repeat_end" -> TaskAction.RepeatEnd(
                                blockId = aObj.optString("blockId", "")
                            )
                            "live_progress_notification" -> TaskAction.LiveProgressNotification(
                                notificationId = aObj.optString("notificationId", "progress_1"),
                                title = aObj.optString("title", "Progress"),
                                text = aObj.optString("text", "{count}%"),
                                currentValue = aObj.optString("currentValue", "{count}"),
                                maxValue = aObj.optString("maxValue", "100"),
                                dismissWhenComplete = aObj.optBoolean("dismissWhenComplete", false)
                            )
                            "remove_notification" -> TaskAction.RemoveNotification(
                                targetTagOrTitle = aObj.optString("targetTagOrTitle", "progress_1"),
                                removeAll = aObj.optBoolean("removeAll", false)
                            )
                            "wait_block" -> TaskAction.WaitBlock(
                                durationValue = aObj.optString("durationValue", "1"),
                                isMilliseconds = aObj.optBoolean("isMilliseconds", false)
                            )
                            else -> null
                        }
                        if (action != null) actionsList.add(action)
                    }
                }
                list.add(
                    TaskShortcut(
                        id = id,
                        name = name,
                        iconName = iconName,
                        showInBubble = showInBubble,
                        showInDroidbar = showInDroidbar,
                        automationEnabled = automationEnabled,
                        triggers = triggersList,
                        actions = actionsList
                    )
                )
            }
        }
        return list
    }

    companion object {
        private const val KEY_SHORTCUTS = "tasks_shortcuts_list"
        private const val KEY_GLOBAL_VARS = "tasks_global_variables"

        @Volatile
        private var instance: TasksRepository? = null

        fun getInstance(context: Context): TasksRepository {
            return instance ?: synchronized(this) {
                instance ?: TasksRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
