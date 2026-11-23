package tv.homeshuffle.wallpaper

import android.Manifest
import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import java.io.File

class WallpaperProviderService : Service() {
    private val prefs get() = PreferencesManager.preferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastPoll = 0L
    private var lastStop = 0L
    private val lock = Any()
    private var lastRequestElapsed = Long.MIN_VALUE / 2

    private val poll = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now < lastPoll) lastStop = minOf(lastStop, now - LOOKBACK_MS)
            if (usageSupported() && hasUsageAccess(this@WallpaperProviderService)) {
                val stop = latestHomeStop(lastPoll - LOOKBACK_MS, now)
                if (stop > lastStop) {
                    lastStop = stop
                    requestShuffleOnReturn("usage")
                }
            }
            lastPoll = now
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
        lastPoll = System.currentTimeMillis()
        lastStop = lastPoll
        if (usageSupported()) handler.postDelayed(poll, POLL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        if (SystemClock.elapsedRealtime() - lastRequestElapsed > DEDUP_MS) requestShuffleOnReturn("unbind")
        return false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun latestHomeStop(from: Long, to: Long): Long {
        val events = runCatching { getSystemService(UsageStatsManager::class.java)?.queryEvents(from, to) }.getOrNull() ?: return 0L
        val event = UsageEvents.Event()
        var latest = 0L
        while (events.getNextEvent(event)) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_STOPPED && event.packageName == PROJECTIVY && event.className == PROJECTIVY_HOME) latest = maxOf(latest, event.timeStamp)
        }
        return latest
    }

    private fun requestShuffleOnReturn(detector: String) {
        lastRequestElapsed = SystemClock.elapsedRealtime()
        synchronized(lock) { prefs.edit().putBoolean(PENDING_KEY, true).apply() }
        sendBroadcast(Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
            `package` = PROJECTIVY
            putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, getString(R.string.plugin_uuid))
            putExtra(WallpaperProviderContract.EXTRA_UPDATE_REASON, WallpaperProviderContract.UpdateReason.DATA_CHANGED)
        })
        Log.i(TAG, "home left ($detector): refresh requested")
    }

    private fun images(): List<File> {
        val dir = getExternalFilesDir("wallpapers") ?: return emptyList()
        return dir.listFiles { f: File -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty()
    }

    private fun wallpaperFor(name: String) = Wallpaper(
        Uri.parse("content://${ImageProvider.AUTHORITY}/${Uri.encode(name)}").toString(),
        WallpaperType.IMAGE, WallpaperDisplayMode.CROP, title = name.substringBeforeLast('.'))

    private fun current(): Wallpaper? {
        val name = prefs.getString(LAST_KEY, null)?.takeIf { n -> images().any { it.name == n } } ?: return next()
        return wallpaperFor(name)
    }

    private fun next(): Wallpaper? {
        val files = images()
        if (files.isEmpty()) return null
        val names = files.map { it.name }.toSet()
        val last = prefs.getString(LAST_KEY, null)
        var seen = prefs.getStringSet(SEEN_KEY, emptySet())!!.intersect(names)
        var pool = names - seen
        if (pool.isEmpty()) { seen = emptySet(); pool = names - setOfNotNull(last) }
        if (pool.isEmpty()) pool = names
        val pick = pool.random()
        prefs.edit().putString(LAST_KEY, pick).putStringSet(SEEN_KEY, seen + pick).putBoolean(PENDING_KEY, false).apply()
        Log.i(TAG, "serving $pick (${seen.size + 1}/${names.size} this round)")
        return wallpaperFor(pick)
    }

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> = synchronized(lock) {
            val pending = prefs.getBoolean(PENDING_KEY, false)
            when (event) {
                is Event.TimeElapsed -> listOfNotNull(if (pending) next() else current())
                is Event.LauncherIdleModeChanged -> if (pending && !event.isIdle) listOfNotNull(next()) else emptyList()
                else -> emptyList()
            }
        }

        override fun getPreferences(): String = PreferencesManager.export()

        override fun setPreferences(params: String) { PreferencesManager.import(params) }
    }

    companion object {
        private const val TAG = "HomeShuffle"
        private const val LAST_KEY = "last_served"
        private const val PENDING_KEY = "shuffle_pending"
        private const val SEEN_KEY = "seen_this_round"
        private const val PROJECTIVY = "com.spocky.projengmenu"
        private const val PROJECTIVY_HOME = "com.spocky.projengmenu.ui.home.MainActivity"
        private const val POLL_MS = 1000L
        private const val DEDUP_MS = 3000L
        private const val LOOKBACK_MS = 5000L

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
        fun usageSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun hasUsageAccess(context: Context): Boolean {
            if (!usageSupported()) return false
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            return when (appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT -> context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
                else -> false
            }
        }
    }
}
