package tv.homeshuffle.wallpaper

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

class SettingsFragment : GuidedStepSupportFragment() {
    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(
            getString(R.string.plugin_name),
            "v${BuildConfig.VERSION_NAME}\n\n${getString(R.string.plugin_description)}",
            getString(R.string.settings),
            AppCompatResources.getDrawable(requireActivity(), R.drawable.ic_plugin)
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val dir = requireContext().getExternalFilesDir("wallpapers")
        actions.add(
            GuidedAction.Builder(context)
                .title(R.string.setting_folder_title)
                .description("${dir?.absolutePath} (${dir?.listFiles()?.size ?: 0} files)")
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(USAGE_ACTION)
                .title(R.string.setting_usage_title)
                .description(usageDescription())
                .multilineDescription(true)
                .build()
        )
    }

    override fun onResume() {
        super.onResume()
        findActionById(USAGE_ACTION)?.description = usageDescription()
        notifyActionChanged(findActionPositionById(USAGE_ACTION))
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == USAGE_ACTION && WallpaperProviderService.usageSupported()) runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun usageDescription() = getString(
        when {
            !WallpaperProviderService.usageSupported() -> R.string.setting_usage_unsupported
            WallpaperProviderService.hasUsageAccess(requireContext()) -> R.string.setting_usage_granted
            else -> R.string.setting_usage_missing
        }
    )

    companion object {
        private const val USAGE_ACTION = 1L
    }
}
