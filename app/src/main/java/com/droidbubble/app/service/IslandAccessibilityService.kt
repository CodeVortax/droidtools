package com.droidbubble.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.droidbubble.app.island.IslandStateManager
import com.droidbubble.app.tools.SystemToolsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class IslandAccessibilityService : AccessibilityService() {

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected = _isConnected.asStateFlow()

        var instance: IslandAccessibilityService? = null
            private set

        fun openNotificationShade(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
        }

        fun openQuickSettings(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false
        }

        fun toggleSplitScreen(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) ?: false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null) {
            IslandStateManager.setDeviceLocked(km.isKeyguardLocked)
        }
        IslandOverlayService.onAccessibilityServiceChanged(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km != null) {
                IslandStateManager.setDeviceLocked(km.isKeyguardLocked)
            }
            val pkg = event.packageName?.toString() ?: return
            if (pkg == IslandStateManager.dismissedMediaPackage) {
                IslandStateManager.unDismissMedia(pkg)
            }
            NotificationReceiverService.instance?.refreshMedia()
            SystemToolsManager.getInstance(applicationContext).onForegroundAppChanged(pkg)
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    SystemToolsManager.getInstance(applicationContext)
                        .onVolumeKeyPressed(AudioManager.ADJUST_RAISE)
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    SystemToolsManager.getInstance(applicationContext)
                        .onVolumeKeyPressed(AudioManager.ADJUST_LOWER)
                }
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isConnected.value = false
        IslandOverlayService.onAccessibilityServiceChanged(null)
    }
}
