package com.droidbubble.app.tools

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidbubble.app.droidbar.BarAppInfo
import com.droidbubble.app.droidbar.MostUsedAppsHelper
import com.droidbubble.app.service.BatteryUtils
import com.droidbubble.app.service.IslandOverlayService
import com.droidbubble.app.ui.main.AppPickerDialog
import com.droidbubble.app.ui.main.CookieIconBox
import com.droidbubble.app.ui.main.GroupPosition
import com.droidbubble.app.ui.main.PermissionHelper
import com.droidbubble.app.ui.main.ProgressiveTopBlurBox
import com.droidbubble.app.ui.main.SectionHeader
import com.droidbubble.app.ui.main.SettingsCardItem
import com.droidbubble.app.ui.main.SliderSettingItem
import com.droidbubble.app.ui.main.getGroupedShape
import com.droidbubble.app.ui.main.getHorizontalSegmentShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private val LIGHTING_PRESET_COLORS = listOf(
    0xFF64D2FFL, // Electric Cyan
    0xFFBF5AF2L, // Neon Violet
    0xFFFF375FL, // Cyber Rose
    0xFF30D158L, // Matrix Emerald
    0xFFFF9F0AL, // Solar Amber
    0xFFFFD60AL, // Laser Gold
    0xFF0A84FFL, // Cobalt Blue
    0xFFFF453AL, // Crimson Red
    0xFF5E5CE6L, // Indigo Pulse
    0xFFFFFFFFL  // Pure White
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryAndPowerSavingPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val toolsManager = remember { SystemToolsManager.getInstance(context) }
    val batteryConfig by toolsManager.batteryPowerConfig.collectAsState()

    BackHandler(onBack = onBack)

    var batteryInfo by remember { mutableStateOf(BatteryUtils.getDetailedBatteryInfo(context)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            batteryInfo = withContext(Dispatchers.IO) {
                BatteryUtils.getDetailedBatteryInfo(context)
            }
            delay(2000L)
        }
    }

    val tempFahrenheit = remember(batteryInfo.temperatureC) {
        (((batteryInfo.temperatureC * 9.0 / 5.0) + 32.0) * 10.0).roundToInt() / 10.0
    }

    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 6
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Battery and power saving",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        ProgressiveTopBlurBox(
            isScrolled = isScrolled,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Minimalist Battery Info Grouped List
                item {
                    SectionHeader("Battery Status")
                }

                item {
                    val voltageStr = batteryInfo.voltageVolts?.let { String.format(Locale.US, "%.2f V", it) } ?: "—"
                    val currentStr = batteryInfo.currentMa?.let { "${it} mA" } ?: "—"
                    val wattageSuffix = batteryInfo.wattage?.let { String.format(Locale.US, " • %.1f W", it) } ?: ""
                    val capValue = when {
                        batteryInfo.chargeCounterMah != null && batteryInfo.designCapacityMah != null ->
                            "${batteryInfo.chargeCounterMah} / ${batteryInfo.designCapacityMah} mAh"
                        batteryInfo.designCapacityMah != null -> "${batteryInfo.designCapacityMah} mAh"
                        batteryInfo.chargeCounterMah != null -> "${batteryInfo.chargeCounterMah} mAh"
                        batteryInfo.cycleCount != null -> "${batteryInfo.cycleCount} cycles"
                        else -> batteryInfo.technology
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SettingsCardItem(
                            position = GroupPosition.TOP,
                            isActive = batteryInfo.isCharging
                        ) {
                            MinimalistBatteryInfoRow(
                                label = "Level & State",
                                subtitle = "${batteryInfo.statusText} (${batteryInfo.pluggedSource})$wattageSuffix",
                                value = "${batteryInfo.levelPercent}%",
                                icon = if (batteryInfo.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                isHighlighted = batteryInfo.isCharging
                            )
                        }

                        SettingsCardItem(position = GroupPosition.MIDDLE) {
                            MinimalistBatteryInfoRow(
                                label = "Temperature",
                                value = String.format(Locale.US, "%.1f°C  •  %.1f°F", batteryInfo.temperatureC, tempFahrenheit),
                                icon = Icons.Default.Thermostat
                            )
                        }

                        SettingsCardItem(position = GroupPosition.MIDDLE) {
                            MinimalistBatteryInfoRow(
                                label = "Voltage & Current",
                                value = "$voltageStr  •  $currentStr",
                                icon = Icons.Default.ElectricalServices
                            )
                        }

                        SettingsCardItem(position = GroupPosition.MIDDLE) {
                            MinimalistBatteryInfoRow(
                                label = "Health & Chemistry",
                                value = "${batteryInfo.healthText}  •  ${batteryInfo.technology}",
                                icon = Icons.Default.Favorite
                            )
                        }

                        SettingsCardItem(position = GroupPosition.BOTTOM) {
                            MinimalistBatteryInfoRow(
                                label = if (batteryInfo.cycleCount != null && batteryInfo.chargeCounterMah == null && batteryInfo.designCapacityMah == null) "Charge Cycles" else "Capacity",
                                subtitle = if (batteryInfo.cycleCount != null && (batteryInfo.chargeCounterMah != null || batteryInfo.designCapacityMah != null)) "${batteryInfo.cycleCount} cycles" else null,
                                value = capValue,
                                icon = Icons.Default.BatteryStd
                            )
                        }
                    }
                }

                // 2. Power Saving Controls (No descriptions per user request)
                item {
                    SectionHeader("Power Saving")
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Low Power Mode Toggle
                        SettingsCardItem(
                            position = GroupPosition.TOP,
                            isActive = batteryConfig.lowPowerModeEnabled
                        ) {
                            NoSubtitleToggleRow(
                                title = "Low Power Mode",
                                icon = Icons.Default.BatterySaver,
                                checked = batteryConfig.lowPowerModeEnabled,
                                onCheckedChange = { enabled ->
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    toolsManager.updateBatteryPowerConfig { it.copy(lowPowerModeEnabled = enabled) }
                                }
                            )
                        }

                        // Automatic Low Power Mode Toggle + Threshold Slider
                        SettingsCardItem(
                            position = GroupPosition.MIDDLE,
                            isActive = batteryConfig.autoLowPowerEnabled
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                NoSubtitleToggleRow(
                                    title = "Automatic Low Power Mode",
                                    icon = Icons.Default.AutoMode,
                                    checked = batteryConfig.autoLowPowerEnabled,
                                    onCheckedChange = { enabled ->
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        toolsManager.updateBatteryPowerConfig { it.copy(autoLowPowerEnabled = enabled) }
                                    }
                                )
                                SliderSettingItem(
                                    label = "Automatic Low Power Threshold",
                                    value = batteryConfig.autoLowPowerThreshold.toFloat(),
                                    unit = "%",
                                    valueRange = 5f..50f,
                                    onValueChange = { v ->
                                        toolsManager.updateBatteryPowerConfig {
                                            it.copy(autoLowPowerThreshold = v.roundToInt().coerceIn(5, 50))
                                        }
                                    }
                                )
                            }
                        }

                        // Automatic Brightness Reduction Toggle + Threshold Slider + Reduction Level Slider
                        SettingsCardItem(
                            position = GroupPosition.BOTTOM,
                            isActive = batteryConfig.brightnessReductionEnabled
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                NoSubtitleToggleRow(
                                    title = "Automatic Brightness Reduction",
                                    icon = Icons.Default.BrightnessLow,
                                    checked = batteryConfig.brightnessReductionEnabled,
                                    onCheckedChange = { enabled ->
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        if (enabled && !toolsManager.canWriteSystemSettings() && !toolsManager.hasPrivilegedAccess()) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                                            Uri.parse("package:${context.packageName}")
                                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                                    )
                                                }
                                            }
                                        } else {
                                            toolsManager.updateBatteryPowerConfig { it.copy(brightnessReductionEnabled = enabled) }
                                        }
                                    }
                                )
                                SliderSettingItem(
                                    label = "Battery Percentage Threshold",
                                    value = batteryConfig.brightnessReductionThreshold.toFloat(),
                                    unit = "%",
                                    valueRange = 5f..50f,
                                    onValueChange = { v ->
                                        toolsManager.updateBatteryPowerConfig {
                                            it.copy(brightnessReductionThreshold = v.roundToInt().coerceIn(5, 50))
                                        }
                                    }
                                )
                                SliderSettingItem(
                                    label = "Brightness Reduction Level",
                                    value = batteryConfig.brightnessReductionPercent.toFloat(),
                                    unit = "%",
                                    valueRange = 5f..60f,
                                    onValueChange = { v ->
                                        toolsManager.updateBatteryPowerConfig {
                                            it.copy(brightnessReductionPercent = v.roundToInt().coerceIn(5, 60))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. 1-Pixel Black Grid Overlay Section
                item {
                    SectionHeader("OLED Pixel Dimming")
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SettingsCardItem(
                            position = if (batteryConfig.pixelGridOverlayEnabled) GroupPosition.TOP else GroupPosition.SINGLE,
                            isActive = batteryConfig.pixelGridOverlayEnabled
                        ) {
                            NoSubtitleToggleRow(
                                title = "1-Pixel Black Grid Overlay",
                                icon = Icons.Default.GridOn,
                                checked = batteryConfig.pixelGridOverlayEnabled,
                                onCheckedChange = { enabled ->
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    if (enabled && !PermissionHelper.isOverlayGranted(context)) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                            )
                                        }
                                    } else {
                                        toolsManager.updateBatteryPowerConfig { it.copy(pixelGridOverlayEnabled = enabled) }
                                        IslandOverlayService.start(context)
                                    }
                                }
                            )
                        }

                        if (batteryConfig.pixelGridOverlayEnabled) {
                            SettingsCardItem(position = GroupPosition.BOTTOM) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "1px Grid Pattern",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    val modes = listOf(
                                        3 to "Sparse (1px / 3×3)",
                                        2 to "Standard (1px / 2×2)",
                                        4 to "Dense (1px Checker)"
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        modes.forEachIndexed { idx, (modeVal, label) ->
                                            val isSelected = batteryConfig.pixelGridSpacing == modeVal
                                            val segShape = getHorizontalSegmentShape(idx, modes.size, isSelected)
                                            Surface(
                                                shape = segShape,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(40.dp)
                                                    .clip(segShape)
                                                    .clickable {
                                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                        toolsManager.updateBatteryPowerConfig {
                                                            it.copy(pixelGridSpacing = modeVal)
                                                        }
                                                    }
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = label,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Additional Functional Power-Saving Toggles (No descriptions)
                item {
                    SectionHeader("System Power Optimizations")
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SettingsCardItem(
                            position = GroupPosition.TOP,
                            isActive = batteryConfig.shortScreenTimeoutEnabled
                        ) {
                            NoSubtitleToggleRow(
                                title = "15-Second Screen Timeout",
                                icon = Icons.Default.Timer,
                                checked = batteryConfig.shortScreenTimeoutEnabled,
                                onCheckedChange = { enabled ->
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    if (enabled && !toolsManager.canWriteSystemSettings() && !toolsManager.hasPrivilegedAccess()) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                                        Uri.parse("package:${context.packageName}")
                                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                                )
                                            }
                                        }
                                    } else {
                                        toolsManager.updateBatteryPowerConfig { it.copy(shortScreenTimeoutEnabled = enabled) }
                                    }
                                }
                            )
                        }

                        SettingsCardItem(
                            position = GroupPosition.MIDDLE,
                            isActive = batteryConfig.darkModeOnPowerSave
                        ) {
                            NoSubtitleToggleRow(
                                title = "Dark Mode",
                                icon = Icons.Default.DarkMode,
                                checked = batteryConfig.darkModeOnPowerSave,
                                onCheckedChange = { enabled ->
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    toolsManager.updateBatteryPowerConfig { it.copy(darkModeOnPowerSave = enabled) }
                                }
                            )
                        }

                        SettingsCardItem(
                            position = GroupPosition.MIDDLE,
                            isActive = batteryConfig.disableAodOnPowerSave
                        ) {
                            NoSubtitleToggleRow(
                                title = "Disable Always-On Display",
                                icon = Icons.Default.ScreenLockPortrait,
                                checked = batteryConfig.disableAodOnPowerSave,
                                onCheckedChange = { enabled ->
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    toolsManager.updateBatteryPowerConfig { it.copy(disableAodOnPowerSave = enabled) }
                                }
                            )
                        }

                        SettingsCardItem(
                            position = GroupPosition.MIDDLE,
                            isActive = batteryConfig.disableAnimationsOnPowerSave
                        ) {
                            NoSubtitleToggleRow(
                                title = "Disable System Animations",
                                icon = Icons.Default.Animation,
                                checked = batteryConfig.disableAnimationsOnPowerSave,
                                onCheckedChange = { enabled ->
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    toolsManager.updateBatteryPowerConfig { it.copy(disableAnimationsOnPowerSave = enabled) }
                                }
                            )
                        }

                        SettingsCardItem(
                            position = GroupPosition.BOTTOM,
                            isActive = batteryConfig.restrictAutoSyncOnPowerSave
                        ) {
                            NoSubtitleToggleRow(
                                title = "Pause Background Auto-Sync",
                                icon = Icons.Default.SyncDisabled,
                                checked = batteryConfig.restrictAutoSyncOnPowerSave,
                                onCheckedChange = { enabled ->
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    toolsManager.updateBatteryPowerConfig { it.copy(restrictAutoSyncOnPowerSave = enabled) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalistBatteryInfoRow(
    label: String,
    value: String,
    icon: ImageVector,
    subtitle: String? = null,
    isHighlighted: Boolean = false
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = label,
                fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = if (subtitle != null) {
            {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else null,
        leadingContent = {
            CookieIconBox(
                icon = icon,
                contentDescription = label,
                isChecked = isHighlighted
            )
        },
        trailingContent = {
            Text(
                text = value,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    )
}

@Composable
private fun NoSubtitleToggleRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold
            )
        },
        leadingContent = {
            CookieIconBox(
                icon = icon,
                contentDescription = title,
                isChecked = checked
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

// =============================================================================
// NOTIFICATIONS & NOTIFICATION LIGHTING PAGE
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val toolsManager = remember { SystemToolsManager.getInstance(context) }
    val lightingConfig by toolsManager.notificationLightingConfig.collectAsState()

    var showAddAppPicker by remember { mutableStateOf(false) }
    var editingAppRule by remember { mutableStateOf<PerAppLightingConfig?>(null) }

    val allAppsMap = remember { mutableStateMapOf<String, BarAppInfo>() }
    LaunchedEffect(lightingConfig.perAppConfigs) {
        if (lightingConfig.perAppConfigs.isNotEmpty() && allAppsMap.isEmpty()) {
            val loaded = withContext(Dispatchers.IO) {
                MostUsedAppsHelper.getAllInstalledApps(context)
            }
            loaded.forEach { allAppsMap[it.packageName] = it }
        }
    }

    BackHandler {
        when {
            editingAppRule != null -> editingAppRule = null
            showAddAppPicker -> showAddAppPicker = false
            else -> onBack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Notifications",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (!PermissionHelper.isOverlayGranted(context)) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    )
                                }
                            } else {
                                IslandOverlayService.start(context)
                                toolsManager.triggerNotificationLighting(isPreview = true)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Fullscreen", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    ) { innerPadding ->
        val notifListState = rememberLazyListState()
        val notifIsScrolled by remember {
            derivedStateOf {
                notifListState.firstVisibleItemIndex > 0 || notifListState.firstVisibleItemScrollOffset > 6
            }
        }
        ProgressiveTopBlurBox(
            isScrolled = notifIsScrolled,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
        LazyColumn(
            state = notifListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Interactive Live Preview Phone Card
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp)
                                .clip(RoundedCornerShape(lightingConfig.cornerRadiusDp.dp.coerceIn(4.dp, 48.dp)))
                                .background(Color(0xFF090A0F))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(lightingConfig.cornerRadiusDp.dp.coerceIn(4.dp, 48.dp))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            // Live animated canvas preview of the currently selected lighting style
                            NotificationLightingEffectCanvas(
                                style = lightingConfig.style,
                                colorArgb = lightingConfig.customColorArgb,
                                isRainbow = lightingConfig.colorMode == LightingColorMode.RAINBOW,
                                cornerRadiusDp = lightingConfig.cornerRadiusDp,
                                thicknessDp = lightingConfig.thicknessDp,
                                animationSpeed = lightingConfig.animationSpeed,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Mock notification pill in the center of the preview screen
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White.copy(alpha = 0.10f),
                                modifier = Modifier
                                    .width(210.dp)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color(lightingConfig.customColorArgb.toInt())),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.NotificationsActive,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = lightingConfig.style.displayName,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Radius ${lightingConfig.cornerRadiusDp}dp • ${lightingConfig.durationSeconds}s",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 10.5.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Master Toggle & Trigger Conditions
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SettingsCardItem(
                        position = GroupPosition.TOP,
                        isActive = lightingConfig.enabled
                    ) {
                        NoSubtitleToggleRow(
                            title = "Notification Lighting",
                            icon = Icons.Default.Flare,
                            checked = lightingConfig.enabled,
                            onCheckedChange = { enabled ->
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                if (enabled && !PermissionHelper.isOverlayGranted(context)) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        )
                                    }
                                } else {
                                    toolsManager.updateNotificationLightingConfig { it.copy(enabled = enabled) }
                                    if (enabled) {
                                        IslandOverlayService.start(context)
                                    }
                                }
                            }
                        )
                    }

                    SettingsCardItem(
                        position = GroupPosition.MIDDLE,
                        isActive = lightingConfig.showWhenScreenOn
                    ) {
                        NoSubtitleToggleRow(
                            title = "Show While Screen is On",
                            icon = Icons.Default.Smartphone,
                            checked = lightingConfig.showWhenScreenOn,
                            onCheckedChange = { v ->
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                toolsManager.updateNotificationLightingConfig { it.copy(showWhenScreenOn = v) }
                            }
                        )
                    }

                    SettingsCardItem(
                        position = GroupPosition.BOTTOM,
                        isActive = lightingConfig.showOnLockScreen
                    ) {
                        NoSubtitleToggleRow(
                            title = "Show on Lock Screen",
                            icon = Icons.Default.Lock,
                            checked = lightingConfig.showOnLockScreen,
                            onCheckedChange = { v ->
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                toolsManager.updateNotificationLightingConfig { it.copy(showOnLockScreen = v) }
                            }
                        )
                    }
                }
            }

            // 3. Lighting Styles (10 Functional Styles!)
            item {
                SectionHeader("Lighting Style (${NotificationLightingStyle.entries.size} Styles)")
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val styles = NotificationLightingStyle.entries
                    styles.forEachIndexed { index, styleOption ->
                        val pos = when {
                            styles.size == 1 -> GroupPosition.SINGLE
                            index == 0 -> GroupPosition.TOP
                            index == styles.lastIndex -> GroupPosition.BOTTOM
                            else -> GroupPosition.MIDDLE
                        }
                        val isSelected = lightingConfig.style == styleOption
                        SettingsCardItem(
                            position = pos,
                            isActive = isSelected
                        ) {
                            ListItem(
                                modifier = Modifier.clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    toolsManager.updateNotificationLightingConfig { it.copy(style = styleOption) }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        text = styleOption.displayName,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                },
                                leadingContent = {
                                    CookieIconBox(
                                        icon = getStyleIcon(styleOption),
                                        contentDescription = styleOption.displayName,
                                        isChecked = isSelected
                                    )
                                },
                                trailingContent = {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            toolsManager.updateNotificationLightingConfig { it.copy(style = styleOption) }
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 4. Color Mode & Palette
            item {
                SectionHeader("Color & Appearance")
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SettingsCardItem(position = GroupPosition.TOP) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Color Mode",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val modes = LightingColorMode.entries
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                modes.forEachIndexed { idx, mode ->
                                    val isSelected = lightingConfig.colorMode == mode
                                    val segShape = getHorizontalSegmentShape(idx, modes.size, isSelected)
                                    Surface(
                                        shape = segShape,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp)
                                            .clip(segShape)
                                            .clickable {
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                toolsManager.updateNotificationLightingConfig {
                                                    it.copy(colorMode = mode)
                                                }
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = mode.displayName,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            if (lightingConfig.colorMode != LightingColorMode.RAINBOW) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    LIGHTING_PRESET_COLORS.forEach { colorLong ->
                                        val isChosen = lightingConfig.customColorArgb == colorLong
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(colorLong.toInt()))
                                                .border(
                                                    width = if (isChosen) 3.dp else 1.dp,
                                                    color = if (isChosen) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.3f),
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                    toolsManager.updateNotificationLightingConfig {
                                                        it.copy(customColorArgb = colorLong)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isChosen) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Corner Radius Slider
                    SettingsCardItem(position = GroupPosition.MIDDLE) {
                        SliderSettingItem(
                            label = "Screen Corner Radius",
                            value = lightingConfig.cornerRadiusDp.toFloat(),
                            unit = "dp",
                            valueRange = 0f..60f,
                            onValueChange = { v ->
                                toolsManager.updateNotificationLightingConfig {
                                    it.copy(cornerRadiusDp = v.roundToInt().coerceIn(0, 60))
                                }
                            }
                        )
                    }

                    // Thickness Slider
                    SettingsCardItem(position = GroupPosition.MIDDLE) {
                        SliderSettingItem(
                            label = "Glow / Stroke Thickness",
                            value = lightingConfig.thicknessDp,
                            unit = "dp",
                            valueRange = 2f..24f,
                            onValueChange = { v ->
                                toolsManager.updateNotificationLightingConfig {
                                    it.copy(thicknessDp = ((v * 2f).roundToInt() / 2f).coerceIn(2f, 24f))
                                }
                            }
                        )
                    }

                    // Duration Slider
                    SettingsCardItem(position = GroupPosition.MIDDLE) {
                        SliderSettingItem(
                            label = "Duration",
                            value = lightingConfig.durationSeconds.toFloat(),
                            unit = "s",
                            valueRange = 2f..12f,
                            onValueChange = { v ->
                                toolsManager.updateNotificationLightingConfig {
                                    it.copy(durationSeconds = v.roundToInt().coerceIn(2, 12))
                                }
                            }
                        )
                    }

                    // Speed Slider
                    SettingsCardItem(position = GroupPosition.BOTTOM) {
                        SliderSettingItem(
                            label = "Animation Speed",
                            value = lightingConfig.animationSpeed,
                            unit = "x",
                            valueRange = 0.5f..2.0f,
                            steps = 14,
                            onValueChange = { v ->
                                toolsManager.updateNotificationLightingConfig {
                                    it.copy(animationSpeed = ((v * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.0f))
                                }
                            }
                        )
                    }
                }
            }

            // 5. Per-App Color & Style Overrides
            item {
                SectionHeader("Per-App Color & Style (${lightingConfig.perAppConfigs.size})")
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    lightingConfig.perAppConfigs.forEachIndexed { idx, appConfig ->
                        val pos = if (idx == 0) GroupPosition.TOP else GroupPosition.MIDDLE
                        val shape = getGroupedShape(pos)
                        val appIcon = allAppsMap[appConfig.packageName]?.iconBitmap
                        Surface(
                            shape = shape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .clickable { editingAppRule = appConfig }
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        text = appConfig.appLabel,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = appConfig.style?.displayName ?: "Default Style (${lightingConfig.style.displayName})",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = {
                                    if (appIcon != null) {
                                        Image(
                                            bitmap = appIcon.asImageBitmap(),
                                            contentDescription = appConfig.appLabel,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                    } else {
                                        CookieIconBox(
                                            icon = Icons.Default.Apps,
                                            contentDescription = appConfig.appLabel
                                        )
                                    }
                                },
                                trailingContent = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(Color(appConfig.colorArgb.toInt()))
                                                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                        )
                                        IconButton(
                                            onClick = {
                                                toolsManager.removePerAppLighting(appConfig.packageName)
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Add App Override Item (integrated into the grouped list)
                    val addPos = if (lightingConfig.perAppConfigs.isEmpty()) GroupPosition.SINGLE else GroupPosition.BOTTOM
                    val addShape = getGroupedShape(addPos)
                    Surface(
                        shape = addShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(addShape)
                            .clickable { showAddAppPicker = true }
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = {
                                Text(
                                    text = "Add App Override",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            },
                            leadingContent = {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        )
                    }
                }
            }
        }
        }
    }

    if (showAddAppPicker) {
        AppPickerDialog(
            onDismiss = { showAddAppPicker = false },
            onSelectApp = { barApp ->
                allAppsMap[barApp.packageName] = barApp
                showAddAppPicker = false
                val existing = lightingConfig.perAppConfigs.firstOrNull { it.packageName == barApp.packageName }
                editingAppRule = existing ?: PerAppLightingConfig(
                    packageName = barApp.packageName,
                    appLabel = barApp.label,
                    enabled = true,
                    colorArgb = lightingConfig.customColorArgb,
                    style = null
                )
            }
        )
    }

    if (editingAppRule != null) {
        val rule = editingAppRule!!
        var selectedColor by remember(rule) { mutableLongStateOf(rule.colorArgb) }
        var selectedStyle by remember(rule) { mutableStateOf(rule.style) }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editingAppRule = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = rule.appLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )

                Text(
                    text = "App Lighting Color",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LIGHTING_PRESET_COLORS.forEach { colorLong ->
                        val isChosen = selectedColor == colorLong
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(colorLong.toInt()))
                                .border(
                                    width = if (isChosen) 3.dp else 1.dp,
                                    color = if (isChosen) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = colorLong },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isChosen) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "App Lighting Style",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )

                val sheetNestedScroll = remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset = available.copy(x = 0f)

                        override suspend fun onPostFling(
                            consumed: Velocity,
                            available: Velocity
                        ): Velocity = available.copy(x = 0f)
                    }
                }
                val allOptions: List<NotificationLightingStyle?> = remember { listOf(null) + NotificationLightingStyle.entries }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .nestedScroll(sheetNestedScroll),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(allOptions.size) { idx ->
                        val st = allOptions[idx]
                        val pos = when {
                            idx == 0 -> GroupPosition.TOP
                            idx == allOptions.lastIndex -> GroupPosition.BOTTOM
                            else -> GroupPosition.MIDDLE
                        }
                        val shape = getGroupedShape(pos)
                        val isSel = selectedStyle == st
                        Surface(
                            shape = shape,
                            color = if (isSel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .clickable { selectedStyle = st }
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        text = st?.displayName ?: "Use Default Style",
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                trailingContent = {
                                    RadioButton(
                                        selected = isSel,
                                        onClick = { selectedStyle = st }
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { editingAppRule = null },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            toolsManager.addOrUpdatePerAppLighting(
                                rule.copy(
                                    colorArgb = selectedColor,
                                    style = selectedStyle,
                                    enabled = true
                                )
                            )
                            editingAppRule = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun getStyleIcon(style: NotificationLightingStyle): ImageVector {
    return when (style) {
        NotificationLightingStyle.SIMPLE_EDGE_GLOW -> Icons.Default.BlurOn
        NotificationLightingStyle.OUTLINE -> Icons.Default.CropFree
        NotificationLightingStyle.TOP_FULL_PULSE -> Icons.Default.VerticalAlignBottom
        NotificationLightingStyle.DROPLET -> Icons.Default.WaterDrop
        NotificationLightingStyle.CORNER_BEAMS -> Icons.Default.FilterCenterFocus
        NotificationLightingStyle.NEON_CHASE -> Icons.Default.AllInclusive
        NotificationLightingStyle.DUAL_SIDE_WAVE -> Icons.Default.Waves
        NotificationLightingStyle.STARBURST_PARTICLES -> Icons.Default.AutoAwesome
        NotificationLightingStyle.HORIZON_RIPPLE -> Icons.Default.SurroundSound
        NotificationLightingStyle.LASER_SCAN -> Icons.Default.DocumentScanner
    }
}
