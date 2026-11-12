package tv.homeshuffle.wallpaper

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import java.io.File

class WallpaperProviderService : Service() {
    private val prefs get() = PreferencesManager.preferences

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        requestShuffleOnReturn()
        return false
    }

    private fun requestShuffleOnReturn() {
        prefs.edit().putBoolean(PENDING_KEY, true).apply()
        sendBroadcast(Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
            `package` = PROJECTIVY
            putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, getString(R.string.plugin_uuid))
            putExtra(WallpaperProviderContract.EXTRA_UPDATE_REASON, WallpaperProviderContract.UpdateReason.DATA_CHANGED)
        })
        Log.i(TAG, "home left: refresh requested")
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
        override fun getWallpapers(event: Event?): List<Wallpaper> = when (event) {
            is Event.TimeElapsed -> listOfNotNull(if (prefs.getBoolean(PENDING_KEY, false)) next() else current())
            else -> emptyList()
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
    }
}
