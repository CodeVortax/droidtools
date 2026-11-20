package com.droidbubble.app.droidbar

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import java.util.concurrent.ConcurrentHashMap

data class BarAppInfo(
    val packageName: String,
    val label: String,
    val iconBitmap: Bitmap?
)

object MostUsedAppsHelper {

    private val appInfoCache = ConcurrentHashMap<String, BarAppInfo>()
    @Volatile
    private var cachedMostUsedApps: List<BarAppInfo>? = null
    @Volatile
    private var lastMostUsedFetchTimeMs: Long = 0L
    private const val MOST_USED_CACHE_TTL_MS = 30_000L

    private fun getOrLoadBarAppInfo(pm: android.content.pm.PackageManager, pkg: String): BarAppInfo? {
        appInfoCache[pkg]?.let { cached ->
            if (cached.iconBitmap == null || !cached.iconBitmap.isRecycled) {
                return cached
            }
        }
        return try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val info = BarAppInfo(
                packageName = pkg,
                label = pm.getApplicationLabel(appInfo)?.toString() ?: pkg,
                iconBitmap = drawableToBitmap(pm.getApplicationIcon(appInfo))
            )
            appInfoCache[pkg] = info
            info
        } catch (_: Throwable) {
            null
        }
    }

    fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        val maxIconSize = 96
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && !bmp.isRecycled) {
                val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE
                if (!isHardware && bmp.width in 1..maxIconSize && bmp.height in 1..maxIconSize) {
                    return bmp
                }
                return try {
                    val softwareBmp = if (isHardware) {
                        bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
                    } else {
                        bmp
                    }
                    val targetW = softwareBmp.width.coerceIn(1, maxIconSize)
                    val targetH = softwareBmp.height.coerceIn(1, maxIconSize)
                    if (softwareBmp.width != targetW || softwareBmp.height != targetH) {
                        Bitmap.createScaledBitmap(softwareBmp, targetW, targetH, true)
                    } else {
                        softwareBmp
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }
        val w = (drawable.intrinsicWidth.takeIf { it > 0 } ?: maxIconSize).coerceIn(1, maxIconSize)
        val h = (drawable.intrinsicHeight.takeIf { it > 0 } ?: maxIconSize).coerceIn(1, maxIconSize)
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getMostUsedApps(context: Context, limit: Int = 6): List<BarAppInfo> {
        val now = System.currentTimeMillis()
        val cached = cachedMostUsedApps
        if (cached != null && cached.size >= limit && (now - lastMostUsedFetchTimeMs) < MOST_USED_CACHE_TTL_MS) {
            return cached.take(limit)
        }

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        val launcherPackages = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()

        if (hasUsageStatsPermission(context)) {
            try {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 1000L * 60 * 60 * 24 * 7 // past 7 days
                val stats = usageStatsManager?.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, startTime, endTime)

                if (!stats.isNullOrEmpty()) {
                    val usageByPackage = mutableMapOf<String, Long>()
                    for (stat in stats) {
                        val pkg = stat.packageName
                        if (pkg in launcherPackages && pkg != context.packageName) {
                            val current = usageByPackage[pkg] ?: 0L
                            usageByPackage[pkg] = current + stat.totalTimeInForeground
                        }
                    }
                    val sorted = usageByPackage.entries
                        .filter { it.value > 0 }
                        .sortedByDescending { it.value }
                        .map { it.key }

                    if (sorted.isNotEmpty()) {
                        val result = sorted.distinct().take(limit).mapNotNull { pkg ->
                            getOrLoadBarAppInfo(pm, pkg)
                        }
                        if (result.isNotEmpty()) {
                            cachedMostUsedApps = result
                            lastMostUsedFetchTimeMs = now
                            return result
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Fallback: deduplicated list of installed launcher apps (excluding own app)
        val seenPackages = HashSet<String>()
        val fallbackResult = resolveInfos
            .mapNotNull { res ->
                val pkg = res.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName || !seenPackages.add(pkg)) return@mapNotNull null
                appInfoCache[pkg]?.takeIf { it.iconBitmap == null || !it.iconBitmap.isRecycled } ?: runCatching {
                    BarAppInfo(
                        packageName = pkg,
                        label = res.loadLabel(pm)?.toString() ?: pkg,
                        iconBitmap = drawableToBitmap(res.loadIcon(pm))
                    ).also { appInfoCache[pkg] = it }
                }.getOrNull()
            }
            .take(limit)
        if (fallbackResult.isNotEmpty()) {
            cachedMostUsedApps = fallbackResult
            lastMostUsedFetchTimeMs = now
        }
        return fallbackResult
    }

    fun getAllInstalledApps(context: Context): List<BarAppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        val seenPackages = HashSet<String>()
        return resolveInfos
            .mapNotNull { res ->
                val pkg = res.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName || !seenPackages.add(pkg)) return@mapNotNull null
                appInfoCache[pkg]?.takeIf { it.iconBitmap == null || !it.iconBitmap.isRecycled } ?: runCatching {
                    BarAppInfo(
                        packageName = pkg,
                        label = res.loadLabel(pm)?.toString() ?: pkg,
                        iconBitmap = drawableToBitmap(res.loadIcon(pm))
                    ).also { appInfoCache[pkg] = it }
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    fun getAppsByPackages(context: Context, packages: List<String>): List<BarAppInfo> {
        val pm = context.packageManager
        return packages.distinct().mapNotNull { pkg ->
            getOrLoadBarAppInfo(pm, pkg)
        }
    }
}
