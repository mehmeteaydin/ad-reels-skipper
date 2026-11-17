package com.adreels.skipper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adreels.skipper.data.SkipperSettingsRepositoryProvider
import com.adreels.skipper.ui.DashboardViewModel
import com.adreels.skipper.ui.HomeScreen

/** Queries Android accessibility settings cleanly without depending on service components. */
internal object AccessibilityServiceStatusChecker {
    private const val SERVICE_RELATIVE_NAME = ".service.SkipperAccessibilityService"

    @Volatile
    private var testingOverride: (() -> Boolean)? = null

    fun isEnabled(context: Context): Boolean {
        testingOverride?.let { return it() }
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val fullServiceName = "${context.packageName}$SERVICE_RELATIVE_NAME"
        val enabledInManager = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == context.packageName &&
                    serviceInfo.name == fullServiceName
            } == true
        if (enabledInManager) return true

        val enabledSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val expectedShortEntry = "${context.packageName}/$SERVICE_RELATIVE_NAME"
        val expectedFullEntry = "${context.packageName}/$fullServiceName"
        return enabledSetting
            .split(':')
            .any { entry -> entry == expectedShortEntry || entry == expectedFullEntry }
    }

    fun setForTesting(checker: (() -> Boolean)?) {
        testingOverride = checker
    }
}

class MainActivity : ComponentActivity() {

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.factory(
            repository = SkipperSettingsRepositoryProvider.get(applicationContext),
            initialServiceEnabled = AccessibilityServiceStatusChecker.isEnabled(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
            HomeScreen(
                uiState = uiState,
                onOpenAccessibilitySettings = ::openAccessibilitySettings,
                onToggleMaster = dashboardViewModel::setMasterEnabled,
                onSkipDelayChange = dashboardViewModel::setSkipDelayMs,
                onKeywordInputChange = dashboardViewModel::onKeywordInputChange,
                onAddKeyword = dashboardViewModel::submitKeyword,
                onRemoveKeyword = dashboardViewModel::removeCustomKeyword,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        dashboardViewModel.refreshServiceStatus(
            AccessibilityServiceStatusChecker.isEnabled(applicationContext),
        )
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Device lacks a standalone Accessibility Settings activity; stay safely on dashboard.
        }
    }
}
