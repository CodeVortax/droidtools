package com.droidbubble.app.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.droidbubble.app.MainActivity
import com.droidbubble.app.R
import com.droidbubble.app.droidbar.BarAppInfo
import com.droidbubble.app.droidbar.MostUsedAppsHelper
import com.droidbubble.app.ui.main.AppPickerDialog
import com.droidbubble.app.ui.main.CookieIconBox
import com.droidbubble.app.ui.main.GroupPosition
import com.droidbubble.app.ui.main.ProgressiveTopBlurBox
import com.droidbubble.app.ui.main.SectionHeader
import com.droidbubble.app.ui.main.SettingsCardItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class TrackedGithubApp(
    val id: String,
    val owner: String,
    val repo: String,
    val displayName: String,
    val notifyNewReleases: Boolean = true,
    val detectedPackageName: String = "",
    val latestTag: String = "",
    val latestVersion: String = "",
    val latestReleaseName: String = "",
    val latestChangelog: String = "",
    val apkDownloadUrl: String = "",
    val apkFileName: String = "",
    val apkSizeBytes: Long = 0L,
    val lastNotifiedTag: String = "",
    val lastCheckedTimeMs: Long = 0L
)

data class GithubReleaseFetchResult(
    val isValidAndroidRelease: Boolean,
    val errorMessage: String? = null,
    val tagName: String = "",
    val version: String = "",
    val releaseName: String = "",
    val changelog: String = "",
    val apkDownloadUrl: String = "",
    val apkFileName: String = "",
    val apkSizeBytes: Long = 0L
)

class GithubAppsRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("github_apps_prefs", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow(loadApps())
    val apps: StateFlow<List<TrackedGithubApp>> = _apps.asStateFlow()

    private fun loadApps(): List<TrackedGithubApp> {
        val raw = prefs.getString("tracked_github_apps_json", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<TrackedGithubApp>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TrackedGithubApp(
                        id = obj.optString("id"),
                        owner = obj.optString("owner"),
                        repo = obj.optString("repo"),
                        displayName = obj.optString("displayName").ifBlank { obj.optString("repo") },
                        notifyNewReleases = obj.optBoolean("notifyNewReleases", true),
                        detectedPackageName = obj.optString("detectedPackageName", ""),
                        latestTag = obj.optString("latestTag", ""),
                        latestVersion = obj.optString("latestVersion", ""),
                        latestReleaseName = obj.optString("latestReleaseName", ""),
                        latestChangelog = obj.optString("latestChangelog", ""),
                        apkDownloadUrl = obj.optString("apkDownloadUrl", ""),
                        apkFileName = obj.optString("apkFileName", ""),
                        apkSizeBytes = obj.optLong("apkSizeBytes", 0L),
                        lastNotifiedTag = obj.optString("lastNotifiedTag", ""),
                        lastCheckedTimeMs = obj.optLong("lastCheckedTimeMs", 0L)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<TrackedGithubApp>) {
        val arr = JSONArray()
        list.forEach { app ->
            arr.put(
                JSONObject().apply {
                    put("id", app.id)
                    put("owner", app.owner)
                    put("repo", app.repo)
                    put("displayName", app.displayName)
                    put("notifyNewReleases", app.notifyNewReleases)
                    put("detectedPackageName", app.detectedPackageName)
                    put("latestTag", app.latestTag)
                    put("latestVersion", app.latestVersion)
                    put("latestReleaseName", app.latestReleaseName)
                    put("latestChangelog", app.latestChangelog)
                    put("apkDownloadUrl", app.apkDownloadUrl)
                    put("apkFileName", app.apkFileName)
                    put("apkSizeBytes", app.apkSizeBytes)
                    put("lastNotifiedTag", app.lastNotifiedTag)
                    put("lastCheckedTimeMs", app.lastCheckedTimeMs)
                }
            )
        }
        prefs.edit().putString("tracked_github_apps_json", arr.toString()).apply()
        _apps.value = list
    }

    fun addOrUpdateApp(app: TrackedGithubApp) {
        val current = _apps.value.toMutableList()
        val index = current.indexOfFirst {
            it.id == app.id || (it.owner.equals(app.owner, ignoreCase = true) && it.repo.equals(app.repo, ignoreCase = true))
        }
        if (index >= 0) {
            current[index] = app
        } else {
            current.add(0, app)
        }
        persist(current)
    }

    fun removeApp(id: String) {
        val current = _apps.value.filterNot { it.id == id }
        persist(current)
    }

    fun parseGithubUrl(input: String): Pair<String, String>? {
        val trimmed = input.trim().removeSuffix("/").removeSuffix(".git")
        if (trimmed.isBlank()) return null

        val regex = Regex("""(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+).*""", RegexOption.IGNORE_CASE)
        val match = regex.matchEntire(trimmed)
        if (match != null) {
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            if (owner.isNotBlank() && repo.isNotBlank()) return owner to repo
        }

        val slashParts = trimmed.split("/").filter { it.isNotBlank() }
        if (slashParts.size == 2 && !slashParts[0].contains(".") && !slashParts[1].contains(" ")) {
            return slashParts[0] to slashParts[1]
        }
        return null
    }

    suspend fun fetchLatestAndroidRelease(owner: String, repo: String): GithubReleaseFetchResult = withContext(Dispatchers.IO) {
        // 1. Try GitHub Releases API
        val apiResult = fetchViaGithubApi(owner, repo)
        if (apiResult != null) return@withContext apiResult

        // 2. Fallback to Atom feed + expanded_assets
        val atomResult = fetchViaAtomAndExpandedAssets(owner, repo)
        if (atomResult != null) return@withContext atomResult

        GithubReleaseFetchResult(
            isValidAndroidRelease = false,
            errorMessage = "Could not reach GitHub or repository '$owner/$repo' was not found."
        )
    }

    private fun fetchViaGithubApi(owner: String, repo: String): GithubReleaseFetchResult? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.github.com/repos/$owner/$repo/releases?per_page=10")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Droidtools-GitHubUpdater")
            }
            val code = connection.responseCode
            if (code == 404) {
                return GithubReleaseFetchResult(
                    isValidAndroidRelease = false,
                    errorMessage = "GitHub repository '$owner/$repo' was not found."
                )
            }
            if (code != HttpURLConnection.HTTP_OK) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            if (releases.length() == 0) {
                return GithubReleaseFetchResult(
                    isValidAndroidRelease = false,
                    errorMessage = "This GitHub repository has no published releases."
                )
            }

            val deviceAbis = Build.SUPPORTED_ABIS.map { it.lowercase() }

            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                if (rel.optBoolean("draft", false)) continue

                val tagName = rel.optString("tag_name", "").trim()
                val version = tagName.removePrefix("v").removePrefix("V").ifBlank { tagName }
                val relName = rel.optString("name", "").ifBlank { tagName }
                val changelog = rel.optString("body", "").trim()
                val assets = rel.optJSONArray("assets")

                var bestApkUrl = ""
                var bestApkName = ""
                var bestApkSize = 0L
                var bestScore = -1

                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val name = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        val size = asset.optLong("size", 0L)
                        if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                            val lower = name.lowercase()
                            var score = 10
                            if (deviceAbis.isNotEmpty() && lower.contains(deviceAbis[0])) {
                                score += 50
                            } else if (deviceAbis.any { lower.contains(it) }) {
                                score += 30
                            } else if (lower.contains("universal") || lower.contains("all") || (!lower.contains("arm") && !lower.contains("x86"))) {
                                score += 20
                            }
                            if (!lower.contains("debug")) score += 5
                            if (score > bestScore) {
                                bestScore = score
                                bestApkUrl = downloadUrl
                                bestApkName = name
                                bestApkSize = size
                            }
                        }
                    }
                }

                if (bestApkUrl.isNotBlank()) {
                    return GithubReleaseFetchResult(
                        isValidAndroidRelease = true,
                        tagName = tagName,
                        version = version,
                        releaseName = relName,
                        changelog = changelog,
                        apkDownloadUrl = bestApkUrl,
                        apkFileName = bestApkName,
                        apkSizeBytes = bestApkSize
                    )
                }
            }

            GithubReleaseFetchResult(
                isValidAndroidRelease = false,
                errorMessage = "Rejected: '$owner/$repo' does not contain an Android (.apk) release."
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchViaAtomAndExpandedAssets(owner: String, repo: String): GithubReleaseFetchResult? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://github.com/$owner/$repo/releases.atom")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Droidtools-GitHubUpdater")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val xml = connection.inputStream.bufferedReader().use { it.readText() }

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inEntry = false
            var currentTag = ""
            var entryTitle = ""
            var entryLink = ""
            var entryContent = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        if (currentTag == "entry") {
                            inEntry = true
                            entryTitle = ""
                            entryLink = ""
                            entryContent = ""
                        } else if (inEntry && currentTag == "link") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) entryLink = href
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inEntry) {
                            when (currentTag) {
                                "title" -> entryTitle += parser.text
                                "content" -> entryContent += parser.text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        if (name == "entry" && inEntry) {
                            val tagFromLink = entryLink.substringAfterLast("/tag/", "").trim()
                            if (tagFromLink.isNotBlank()) {
                                val apkPair = findApkInExpandedAssets(owner, repo, tagFromLink)
                                if (apkPair != null) {
                                    val cleanNotes = entryContent
                                        .replace(Regex("<[^>]*>"), " ")
                                        .replace("&amp;", "&")
                                        .replace("&lt;", "<")
                                        .replace("&gt;", ">")
                                        .replace("&quot;", "\"")
                                        .replace(Regex("\\s+"), " ")
                                        .trim()
                                    return GithubReleaseFetchResult(
                                        isValidAndroidRelease = true,
                                        tagName = tagFromLink,
                                        version = tagFromLink.removePrefix("v").removePrefix("V").ifBlank { tagFromLink },
                                        releaseName = entryTitle.trim().ifBlank { tagFromLink },
                                        changelog = cleanNotes,
                                        apkDownloadUrl = apkPair.first,
                                        apkFileName = apkPair.second
                                    )
                                }
                            }
                            inEntry = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }

            GithubReleaseFetchResult(
                isValidAndroidRelease = false,
                errorMessage = "Rejected: '$owner/$repo' does not contain an Android (.apk) release."
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun findApkInExpandedAssets(owner: String, repo: String, tagName: String): Pair<String, String>? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://github.com/$owner/$repo/releases/expanded_assets/$tagName")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Droidtools-GitHubUpdater")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val regex = Regex("""href=["'](/[^"']*?/releases/download/[^"']*?\.apk)["']""", RegexOption.IGNORE_CASE)
            val match = regex.find(html) ?: return null
            val path = match.groupValues[1]
            val fullUrl = "https://github.com$path"
            val fileName = path.substringAfterLast("/")
            fullUrl to fileName
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun detectInstalledPackage(app: TrackedGithubApp): Pair<String, String>? {
        val pm = appContext.packageManager
        // 1. Check explicitly saved package name first
        if (app.detectedPackageName.isNotBlank()) {
            val installedVer = getInstalledVersionName(pm, app.detectedPackageName)
            if (installedVer != null) {
                return app.detectedPackageName to installedVer
            }
        }

        // 2. Check cached APK archive if previously downloaded for this app
        val cachedArchivePkg = inspectCachedApkPackageName(app)
        if (!cachedArchivePkg.isNullOrBlank()) {
            val installedVer = getInstalledVersionName(pm, cachedArchivePkg)
            if (installedVer != null) {
                return cachedArchivePkg to installedVer
            }
        }

        // 3. Heuristic match against all installed & launcher apps by repo name, owner, display name, or APK filename
        val repoNorm = app.repo.lowercase().replace(Regex("[^a-z0-9]"), "")
        val ownerNorm = app.owner.lowercase().replace(Regex("[^a-z0-9]"), "")
        val displayNorm = app.displayName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val apkStemNorm = app.apkFileName
            .substringBeforeLast(".")
            .lowercase()
            .replace(Regex("v?\\d+(\\.\\d+)+.*"), "")
            .replace(Regex("(universal|arm64|v8a|armeabi|v7a|x86|64|release|debug|signed|unsigned|apk)"), "")
            .replace(Regex("[^a-z0-9]"), "")

        if (repoNorm.isEmpty() && displayNorm.isEmpty()) return null

        return runCatching {
            val candidatePackages = LinkedHashMap<String, String>() // packageName -> label
            // Gather via getInstalledApplications
            runCatching {
                val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }
                for (appInfo in apps) {
                    val pkg = appInfo.packageName ?: continue
                    val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault("")
                    candidatePackages[pkg] = label
                }
            }
            // Also gather via CATEGORY_LAUNCHER queryIntentActivities as a fallback
            runCatching {
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(launcherIntent, 0)
                }
                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (!candidatePackages.containsKey(pkg)) {
                        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
                        candidatePackages[pkg] = label
                    }
                }
            }

            var bestMatchPkg: String? = null
            var bestMatchScore = 0

            for ((pkg, rawLabel) in candidatePackages) {
                val pkgLower = pkg.lowercase()
                val pkgSegments = pkgLower.split(".")
                val pkgNorm = pkgLower.replace(Regex("[^a-z0-9]"), "")
                val labelNorm = rawLabel.lowercase().replace(Regex("[^a-z0-9]"), "")

                var score = 0
                if (labelNorm.isNotEmpty()) {
                    if (labelNorm == repoNorm || labelNorm == displayNorm || (apkStemNorm.length >= 3 && labelNorm == apkStemNorm)) {
                        score += 100
                    } else if (repoNorm.length >= 4 && (labelNorm.contains(repoNorm) || repoNorm.contains(labelNorm)) && labelNorm.length >= 3) {
                        score += 65
                    }
                }

                if (repoNorm.length >= 3) {
                    if (pkgSegments.any { it.replace(Regex("[^a-z0-9]"), "") == repoNorm }) {
                        score += 90
                    } else if (pkgLower.endsWith(".$repoNorm") || pkgLower.contains(".$repoNorm.")) {
                        score += 85
                    } else if (ownerNorm.length >= 3 && pkgNorm.contains(ownerNorm) && pkgNorm.contains(repoNorm)) {
                        score += 80
                    } else if (repoNorm.length >= 5 && pkgNorm.endsWith(repoNorm)) {
                        score += 70
                    }
                }

                if (apkStemNorm.length >= 4 && pkgSegments.any { it.replace(Regex("[^a-z0-9]"), "") == apkStemNorm }) {
                    score += 75
                }

                if (score > bestMatchScore && score >= 65) {
                    bestMatchScore = score
                    bestMatchPkg = pkg
                }
            }

            if (bestMatchPkg != null) {
                val ver = getInstalledVersionName(pm, bestMatchPkg)
                if (ver != null) {
                    return@runCatching bestMatchPkg to ver
                }
            }
            null
        }.getOrNull()
    }

    private fun inspectCachedApkPackageName(app: TrackedGithubApp): String? {
        return runCatching {
            val updatesDir = File(appContext.cacheDir, "github_apks")
            if (!updatesDir.exists()) return null
            val safeName = app.apkFileName.ifBlank { "${app.repo}-${app.latestVersion}.apk" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val exactFile = File(updatesDir, safeName)
            val targetFile = when {
                exactFile.exists() -> exactFile
                else -> updatesDir.listFiles()?.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true) &&
                            it.name.contains(app.repo.replace(Regex("[^A-Za-z0-9._-]"), "_"), ignoreCase = true)
                }
            } ?: return null

            val pm = appContext.packageManager
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(targetFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(targetFile.absolutePath, 0)
            }
            archiveInfo?.packageName?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun getInstalledVersionName(pm: PackageManager, packageName: String): String? {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            info.versionName ?: ""
        }.getOrNull()
    }

    fun syncDetectedPackageNames() {
        val current = _apps.value
        var changed = false
        val updated = current.map { app ->
            val detected = detectInstalledPackage(app)
            val pkg = detected?.first ?: inspectCachedApkPackageName(app)
            val installedVer = detected?.second
            if (installedVer != null && !isUpdateAvailable(installedVer, app.latestVersion)) {
                cancelReleaseNotification(app)
            }
            val resolvedLabel = if (!pkg.isNullOrBlank() && app.displayName.equals(app.repo, ignoreCase = true)) {
                runCatching {
                    val pm = appContext.packageManager
                    val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }
                    pm.getApplicationLabel(ai).toString().takeIf { it.isNotBlank() }
                }.getOrNull() ?: app.displayName
            } else {
                app.displayName
            }
            if ((!pkg.isNullOrBlank() && pkg != app.detectedPackageName) || resolvedLabel != app.displayName) {
                changed = true
                app.copy(
                    detectedPackageName = if (!pkg.isNullOrBlank()) pkg else app.detectedPackageName,
                    displayName = resolvedLabel
                )
            } else {
                app
            }
        }
        if (changed) {
            persist(updated)
        }
    }

    fun isUpdateAvailable(installedVersion: String, latestVersion: String): Boolean {
        return com.droidbubble.app.util.UpdateManager.isNewerVersion(latestVersion, installedVersion)
    }

    @Volatile
    private var pendingApkFileToInstall: File? = null

    fun resumePendingInstallIfPermitted(): Boolean {
        val pending = pendingApkFileToInstall ?: return false
        if (!pending.exists()) {
            pendingApkFileToInstall = null
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            return false
        }
        pendingApkFileToInstall = null
        launchPackageInstaller(pending)
        return true
    }

    suspend fun downloadAndInstallApk(
        app: TrackedGithubApp,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (app.apkDownloadUrl.isBlank()) return@withContext false
        var connection: HttpURLConnection? = null
        try {
            val updatesDir = File(appContext.cacheDir, "github_apks").apply { mkdirs() }
            val safeName = app.apkFileName.ifBlank { "${app.repo}-${app.latestVersion}.apk" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val targetFile = File(updatesDir, safeName)
            if (targetFile.exists()) targetFile.delete()

            var currentUrl = app.apkDownloadUrl
            var redirects = 0
            while (redirects < 6) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Droidtools-GitHubUpdater")
                }
                val code = connection.responseCode
                if (code in 301..308) {
                    val loc = connection.getHeaderField("Location") ?: break
                    connection.disconnect()
                    currentUrl = loc
                    redirects++
                } else {
                    break
                }
            }

            if (connection?.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
            val totalLength = connection?.contentLengthLong?.takeIf { it > 0 } ?: app.apkSizeBytes

            connection?.inputStream?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalLength > 0) {
                            onProgress((downloaded.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }

            // Inspect downloaded APK archive to learn exact packageName and app label!
            var archiveLabel: String? = null
            val archivePkg = runCatching {
                val pm = appContext.packageManager
                val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(targetFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(targetFile.absolutePath, 0)
                }
                archiveInfo?.applicationInfo?.let { appInfo ->
                    appInfo.sourceDir = targetFile.absolutePath
                    appInfo.publicSourceDir = targetFile.absolutePath
                    archiveLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull()
                }
                archiveInfo?.packageName
            }.getOrNull()

            if (!archivePkg.isNullOrBlank()) {
                val newDisplayName = if (app.displayName.equals(app.repo, ignoreCase = true) && !archiveLabel.isNullOrBlank()) {
                    archiveLabel!!
                } else {
                    app.displayName
                }
                if (archivePkg != app.detectedPackageName || newDisplayName != app.displayName) {
                    addOrUpdateApp(app.copy(detectedPackageName = archivePkg, displayName = newDisplayName))
                }
            }

            withContext(Dispatchers.Main) {
                launchPackageInstaller(targetFile)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun launchPackageInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            pendingApkFileToInstall = apkFile
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(permIntent)
            Toast.makeText(appContext, "Allow installing apps to complete installation", Toast.LENGTH_LONG).show()
            return
        }

        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(installIntent)
    }

    suspend fun checkAllTrackedAppsForUpdates(sendNotifications: Boolean = true) = withContext(Dispatchers.IO) {
        val currentList = _apps.value
        if (currentList.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        for (app in currentList) {
            val res = fetchLatestAndroidRelease(app.owner, app.repo)
            if (res.isValidAndroidRelease && res.tagName.isNotBlank()) {
                val isNewRelease = app.latestTag.isNotBlank() && res.tagName != app.latestTag
                val detected = detectInstalledPackage(app)
                val installedVer = detected?.second
                val hasUpdate = if (installedVer != null) {
                    isUpdateAvailable(installedVer, res.version)
                } else {
                    isNewRelease
                }

                val shouldNotify = sendNotifications &&
                        app.notifyNewReleases &&
                        res.tagName != app.lastNotifiedTag &&
                        (isNewRelease || hasUpdate)

                val updatedApp = app.copy(
                    detectedPackageName = detected?.first ?: app.detectedPackageName,
                    latestTag = res.tagName,
                    latestVersion = res.version,
                    latestReleaseName = res.releaseName,
                    latestChangelog = res.changelog,
                    apkDownloadUrl = res.apkDownloadUrl,
                    apkFileName = res.apkFileName,
                    apkSizeBytes = res.apkSizeBytes,
                    lastNotifiedTag = if (shouldNotify) res.tagName else app.lastNotifiedTag,
                    lastCheckedTimeMs = now
                )
                addOrUpdateApp(updatedApp)

                if (shouldNotify) {
                    postReleaseNotification(updatedApp)
                } else if (installedVer != null && !hasUpdate) {
                    cancelReleaseNotification(updatedApp)
                }
            }
        }
    }

    private fun cancelReleaseNotification(app: TrackedGithubApp) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(92000 + (app.id.hashCode() and 0x7FFF))
    }

    private fun postReleaseNotification(app: TrackedGithubApp) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "github_app_updates_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "GitHub App Releases",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when tracked GitHub Android apps publish a new release"
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            appContext,
            app.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${app.displayName} ${app.latestTag} Released")
            .setContentText(app.latestReleaseName.ifBlank { "A new Android APK release is available on GitHub." })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(92000 + (app.id.hashCode() and 0x7FFF), notif)
    }

    companion object {
        @Volatile
        private var INSTANCE: GithubAppsRepository? = null

        fun getInstance(context: Context): GithubAppsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GithubAppsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GithubUpdatesTabContent(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp)
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val repo = remember { GithubAppsRepository.getInstance(context) }
    val apps by repo.apps.collectAsState()

    var showAddSheet by remember { mutableStateOf(false) }
    var selectedAppForDetails by remember { mutableStateOf<TrackedGithubApp?>(null) }
    var appToLinkPackage by remember { mutableStateOf<TrackedGithubApp?>(null) }
    var isRefreshingAll by remember { mutableStateOf(false) }
    val downloadingProgressMap = remember { mutableStateMapOf<String, Float>() }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, context) {
        repo.syncDetectedPackageNames()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                repo.resumePendingInstallIfPermitted()
                repo.syncDetectedPackageNames()
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val pkgReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                repo.syncDetectedPackageNames()
                refreshTrigger++
            }
        }
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(pkgReceiver, pkgFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(pkgReceiver, pkgFilter)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(pkgReceiver) }
        }
    }

    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 6
        }
    }

    ProgressiveTopBlurBox(
        isScrolled = isScrolled,
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 18.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Updates",
                            fontSize = 34.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(
                            onClick = {
                                if (!isRefreshingAll && apps.isNotEmpty()) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    isRefreshingAll = true
                                    coroutineScope.launch {
                                        repo.checkAllTrackedAppsForUpdates(sendNotifications = true)
                                        refreshTrigger++
                                        isRefreshingAll = false
                                        Toast.makeText(context, "Checked all GitHub apps", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isRefreshingAll && apps.isNotEmpty()
                        ) {
                            if (isRefreshingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh releases")
                            }
                        }

                        Button(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                showAddSheet = true
                            },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add App", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (apps.isEmpty()) {
                item {
                    SettingsCardItem(position = GroupPosition.SINGLE) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CookieIconBox(icon = Icons.Default.CloudDownload)
                            Text(
                                text = "No GitHub Apps Added Yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap 'Add App' and paste a GitHub repository link. Repositories without an Android (.apk) release are automatically rejected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FilledTonalButton(
                                onClick = { showAddSheet = true },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add GitHub App", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                item {
                    SectionHeader(title = "Tracked Repositories (${apps.size})")
                }

                item {
                    val pm = context.packageManager
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        apps.forEachIndexed { index, app ->
                            val pos = when {
                                apps.size == 1 -> GroupPosition.SINGLE
                                index == 0 -> GroupPosition.TOP
                                index == apps.lastIndex -> GroupPosition.BOTTOM
                                else -> GroupPosition.MIDDLE
                            }

                            val detectedPair = remember(app, refreshTrigger) {
                                repo.detectInstalledPackage(app)
                            }
                            val isInstalled = detectedPair != null
                            val installedVersion = detectedPair?.second ?: ""
                            val hasUpdate = remember(isInstalled, installedVersion, app.latestVersion) {
                                isInstalled && repo.isUpdateAvailable(installedVersion, app.latestVersion)
                            }
                            val installedAppIcon: Bitmap? = remember(detectedPair?.first, refreshTrigger) {
                                val pkg = detectedPair?.first
                                if (!pkg.isNullOrBlank()) {
                                    runCatching {
                                        MostUsedAppsHelper.drawableToBitmap(pm.getApplicationIcon(pkg))
                                    }.getOrNull()
                                } else null
                            }
                            val downloadProgress = downloadingProgressMap[app.id]

                            SettingsCardItem(
                                position = pos,
                                isActive = hasUpdate
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedAppForDetails = app }
                                ) {
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        leadingContent = {
                                            if (installedAppIcon != null) {
                                                Image(
                                                    bitmap = installedAppIcon.asImageBitmap(),
                                                    contentDescription = app.displayName,
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                )
                                            } else {
                                                CookieIconBox(
                                                    icon = Icons.Default.Android,
                                                    isChecked = isInstalled
                                                )
                                            }
                                        },
                                        headlineContent = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = app.displayName,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (app.notifyNewReleases) {
                                                    Icon(
                                                        imageVector = Icons.Default.NotificationsActive,
                                                        contentDescription = "Notifications enabled",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }
                                            }
                                        },
                                        supportingContent = {
                                            val statusText = when {
                                                !isInstalled -> "${app.owner}/${app.repo} • Latest: ${app.latestTag.ifBlank { "v${app.latestVersion}" }} • Not installed"
                                                hasUpdate -> "Update available: v$installedVersion → ${app.latestTag.ifBlank { "v${app.latestVersion}" }}"
                                                else -> "Installed (v${installedVersion.ifBlank { app.latestVersion }}) • Up to date"
                                            }
                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        trailingContent = {
                                            if (downloadProgress != null) {
                                                Box(
                                                    modifier = Modifier.size(40.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        progress = { downloadProgress },
                                                        modifier = Modifier.size(28.dp),
                                                        strokeWidth = 3.dp
                                                    )
                                                }
                                            } else if (!isInstalled) {
                                                // Explicitly show a download icon to the right if the GitHub app isn't detected on the phone!
                                                FilledTonalIconButton(
                                                    onClick = {
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        downloadingProgressMap[app.id] = 0.01f
                                                        coroutineScope.launch {
                                                            val ok = repo.downloadAndInstallApk(app) { p ->
                                                                downloadingProgressMap[app.id] = p
                                                            }
                                                            downloadingProgressMap.remove(app.id)
                                                            if (!ok) {
                                                                Toast.makeText(context, "Failed to download APK", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Download,
                                                        contentDescription = "Download APK",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            } else if (hasUpdate) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        downloadingProgressMap[app.id] = 0.01f
                                                        coroutineScope.launch {
                                                            val ok = repo.downloadAndInstallApk(app) { p ->
                                                                downloadingProgressMap[app.id] = p
                                                            }
                                                            downloadingProgressMap.remove(app.id)
                                                            if (!ok) {
                                                                Toast.makeText(context, "Failed to download update", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(12.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Update", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Installed",
                                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add GitHub App Bottom Sheet
    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var githubInput by remember { mutableStateOf("") }
        var notifyReleases by remember { mutableStateOf(true) }
        var isVerifying by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { if (!isVerifying) showAddSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Add GitHub App",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enter a GitHub repository link or owner/repo. Repositories without an Android (.apk) release will be rejected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = githubInput,
                    onValueChange = {
                        githubInput = it
                        errorMessage = null
                    },
                    label = { Text("GitHub Repository Link") },
                    placeholder = { Text("https://github.com/owner/repo") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )

                if (errorMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text("Notify for New Releases", fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text(
                                "Receive a notification when a new Android APK release is published",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = {
                            CookieIconBox(
                                icon = Icons.Default.NotificationsActive,
                                isChecked = notifyReleases
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = notifyReleases,
                                onCheckedChange = { notifyReleases = it }
                            )
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showAddSheet = false },
                        enabled = !isVerifying,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val parsed = repo.parseGithubUrl(githubInput)
                            if (parsed == null) {
                                errorMessage = "Please enter a valid GitHub link (e.g. https://github.com/owner/repo)."
                                return@Button
                            }
                            isVerifying = true
                            errorMessage = null
                            coroutineScope.launch {
                                val (owner, repoName) = parsed
                                val result = repo.fetchLatestAndroidRelease(owner, repoName)
                                isVerifying = false
                                if (!result.isValidAndroidRelease) {
                                    errorMessage = result.errorMessage ?: "Rejected: No Android (.apk) release found in this repository."
                                } else {
                                    val tempApp = TrackedGithubApp(
                                        id = "${owner.lowercase()}_${repoName.lowercase()}",
                                        owner = owner,
                                        repo = repoName,
                                        displayName = repoName,
                                        notifyNewReleases = notifyReleases,
                                        latestTag = result.tagName,
                                        latestVersion = result.version,
                                        latestReleaseName = result.releaseName,
                                        latestChangelog = result.changelog,
                                        apkDownloadUrl = result.apkDownloadUrl,
                                        apkFileName = result.apkFileName,
                                        apkSizeBytes = result.apkSizeBytes,
                                        lastNotifiedTag = result.tagName,
                                        lastCheckedTimeMs = System.currentTimeMillis()
                                    )
                                    val detected = repo.detectInstalledPackage(tempApp)
                                    val detectedPkg = detected?.first ?: ""
                                    val resolvedName = if (detectedPkg.isNotBlank()) {
                                        runCatching {
                                            val pm = context.packageManager
                                            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                pm.getApplicationInfo(detectedPkg, PackageManager.ApplicationInfoFlags.of(0))
                                            } else {
                                                @Suppress("DEPRECATION")
                                                pm.getApplicationInfo(detectedPkg, 0)
                                            }
                                            pm.getApplicationLabel(ai).toString().takeIf { it.isNotBlank() }
                                        }.getOrNull() ?: repoName
                                    } else {
                                        repoName
                                    }
                                    repo.addOrUpdateApp(
                                        tempApp.copy(
                                            detectedPackageName = detectedPkg,
                                            displayName = resolvedName
                                        )
                                    )
                                    refreshTrigger++
                                    showAddSheet = false
                                    Toast.makeText(context, "Added $owner/$repoName", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isVerifying && githubInput.isNotBlank(),
                        modifier = Modifier.weight(1.4f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verifying APK...")
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add App", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Details / Manage App Bottom Sheet
    if (selectedAppForDetails != null) {
        val currentApp = apps.firstOrNull { it.id == selectedAppForDetails!!.id } ?: selectedAppForDetails!!
        val detectedPair = remember(currentApp, refreshTrigger) { repo.detectInstalledPackage(currentApp) }
        val isInstalled = detectedPair != null
        val installedVersion = detectedPair?.second ?: ""
        val hasUpdate = isInstalled && repo.isUpdateAvailable(installedVersion, currentApp.latestVersion)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val downloadProgress = downloadingProgressMap[currentApp.id]

        ModalBottomSheet(
            onDismissRequest = { selectedAppForDetails = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentApp.displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${currentApp.owner}/${currentApp.repo} • Latest: ${currentApp.latestTag}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            repo.removeApp(currentApp.id)
                            selectedAppForDetails = null
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove App",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Notification Toggle & Installed Package Link
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SettingsCardItem(position = GroupPosition.TOP, isActive = currentApp.notifyNewReleases) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Release Notifications", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Notify when a new GitHub release is published", style = MaterialTheme.typography.bodySmall) },
                            leadingContent = {
                                CookieIconBox(
                                    icon = Icons.Default.NotificationsActive,
                                    isChecked = currentApp.notifyNewReleases
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentApp.notifyNewReleases,
                                    onCheckedChange = { enabled ->
                                        val updated = currentApp.copy(notifyNewReleases = enabled)
                                        repo.addOrUpdateApp(updated)
                                        selectedAppForDetails = updated
                                    }
                                )
                            }
                        )
                    }

                    SettingsCardItem(position = GroupPosition.BOTTOM) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                appToLinkPackage = currentApp
                            },
                            headlineContent = {
                                Text(
                                    text = if (isInstalled) "Detected on Device (${detectedPair?.first})" else "Not Detected on Device",
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = if (isInstalled) "Installed v$installedVersion • Tap to change linked app" else "Tap to manually link to an installed app if package name differs",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = {
                                CookieIconBox(
                                    icon = if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Link,
                                    isChecked = isInstalled
                                )
                            }
                        )
                    }
                }

                if (currentApp.latestChangelog.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Release Notes (${currentApp.latestTag})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = currentApp.latestChangelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (downloadProgress != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Downloading ${currentApp.apkFileName}...", fontSize = 12.sp)
                            Text("${(downloadProgress * 100).roundToInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${currentApp.owner}/${currentApp.repo}/releases")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GitHub")
                    }

                    Button(
                        onClick = {
                            downloadingProgressMap[currentApp.id] = 0.01f
                            coroutineScope.launch {
                                val ok = repo.downloadAndInstallApk(currentApp) { p ->
                                    downloadingProgressMap[currentApp.id] = p
                                }
                                downloadingProgressMap.remove(currentApp.id)
                                if (!ok) {
                                    Toast.makeText(context, "Failed to download APK", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = downloadProgress == null && currentApp.apkDownloadUrl.isNotBlank(),
                        modifier = Modifier.weight(1.4f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when {
                                !isInstalled -> "Download APK"
                                hasUpdate -> "Install Update"
                                else -> "Reinstall APK"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (appToLinkPackage != null) {
        val targetApp = appToLinkPackage!!
        AppPickerDialog(
            onDismiss = { appToLinkPackage = null },
            onSelectApp = { selectedBarApp: BarAppInfo ->
                val updated = targetApp.copy(
                    detectedPackageName = selectedBarApp.packageName,
                    displayName = selectedBarApp.label.ifBlank { targetApp.displayName }
                )
                repo.addOrUpdateApp(updated)
                selectedAppForDetails = updated
                refreshTrigger++
                appToLinkPackage = null
            }
        )
    }
}

