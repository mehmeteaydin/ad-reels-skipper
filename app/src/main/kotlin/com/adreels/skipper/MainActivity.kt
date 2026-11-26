package com.adreels.skipper

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.adreels.skipper.data.SkipperSettingsRepositoryProvider
import com.adreels.skipper.domain.ServiceConnectionState
import com.adreels.skipper.service.SkipperAccessibilityService
import com.adreels.skipper.ui.DashboardClock
import com.adreels.skipper.ui.DashboardViewModel
import com.adreels.skipper.ui.HomeScreen
import kotlinx.coroutines.launch

/** Queries Android accessibility settings and live in-process service connection state (`AC-2`). */
internal object AccessibilityServiceStatusChecker {
    private const val SERVICE_RELATIVE_NAME = ".service.SkipperAccessibilityService"

    @Volatile
    private var testingOverride: (() -> Boolean)? = null

    @Volatile
    private var testingStateOverride: (() -> ServiceConnectionState)? = null

    fun resolveConnectionState(
        context: Context,
        isConnectedInProcess: Boolean = SkipperAccessibilityService.isConnectedFlow.value,
    ): ServiceConnectionState {
        testingStateOverride?.let { return it() }
        testingOverride?.let { override ->
            return if (override()) {
                ServiceConnectionState.ACTIVE
            } else {
                ServiceConnectionState.PERMISSION_REQUIRED
            }
        }
        if (isConnectedInProcess) return ServiceConnectionState.ACTIVE
        return if (isListedAsEnabledInSettings(context)) {
            ServiceConnectionState.STOPPED
        } else {
            ServiceConnectionState.PERMISSION_REQUIRED
        }
    }

    private fun isListedAsEnabledInSettings(context: Context): Boolean {
        return try {
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
            enabledSetting
                .split(':')
                .any { entry -> entry == expectedShortEntry || entry == expectedFullEntry }
        } catch (_: RuntimeException) {
            false
        }
    }

    fun setForTesting(checker: (() -> Boolean)?) {
        testingOverride = checker
    }

    fun setStateForTesting(checker: (() -> ServiceConnectionState)?) {
        testingStateOverride = checker
    }
}

/** Queries Android battery optimization exemption state for unrestricted background operation (`AC-3`). */
internal object BatteryOptimizationStatusChecker {
    @Volatile
    private var testingOverride: (() -> Boolean)? = null

    fun isUnrestricted(context: Context): Boolean {
        testingOverride?.let { return it() }
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun setForTesting(checker: (() -> Boolean)?) {
        testingOverride = checker
    }
}

/** Queries whether silent keep-alive notifications are enabled and permitted (`AC-3`). */
internal object NotificationPermissionStatusChecker {
    @Volatile
    private var testingOverride: (() -> Boolean)? = null

    fun isEnabled(context: Context): Boolean {
        testingOverride?.let { return it() }
        return try {
            val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!notificationsEnabled) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    fun setForTesting(checker: (() -> Boolean)?) {
        testingOverride = checker
    }
}

class MainActivity : ComponentActivity() {

    internal var activityStarter: (Intent) -> Unit = ::startActivity
    internal var notificationPermissionRequester: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshPlatformStatuses()
        if (
            !granted &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openNotificationSettings()
        }
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.factory(
            repository = SkipperSettingsRepositoryProvider.get(applicationContext),
            initialConnectionState = AccessibilityServiceStatusChecker.resolveConnectionState(applicationContext),
            initialBatteryUnrestricted = BatteryOptimizationStatusChecker.isUnrestricted(applicationContext),
            initialKeepAliveNotificationEnabled = NotificationPermissionStatusChecker.isEnabled(applicationContext),
            todayProvider = DashboardClock::today,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SkipperAccessibilityService.isConnectedFlow.collect {
                    refreshPlatformStatuses()
                }
            }
        }
        setContent {
            val uiState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
            HomeScreen(
                uiState = uiState,
                onOpenAccessibilitySettings = ::openAccessibilitySettings,
                onOpenBatterySettings = ::openBatteryOptimizationSettings,
                onEnableKeepAliveNotification = ::enableKeepAliveNotification,
                onOpenAutostartSettings = ::openAutostartSettings,
                onToggleMaster = dashboardViewModel::setMasterEnabled,
                onSkipDelayChange = dashboardViewModel::setSkipDelayMs,
                onKeywordInputChange = dashboardViewModel::onKeywordInputChange,
                onAddKeyword = dashboardViewModel::submitKeyword,
                onRemoveKeyword = dashboardViewModel::removeCustomKeyword,
                onOpenHistory = dashboardViewModel::openHistory,
                onCloseHistory = dashboardViewModel::closeHistory,
                onSelectArchivedWeek = dashboardViewModel::selectArchivedWeek,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPlatformStatuses()
    }

    private fun refreshPlatformStatuses() {
        dashboardViewModel.refreshPlatformStatus(
            connectionState = AccessibilityServiceStatusChecker.resolveConnectionState(applicationContext),
            isBatteryUnrestricted = BatteryOptimizationStatusChecker.isUnrestricted(applicationContext),
            isKeepAliveNotificationEnabled = NotificationPermissionStatusChecker.isEnabled(applicationContext),
        )
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        try {
            activityStarter(intent)
        } catch (_: ActivityNotFoundException) {
            // Device lacks a standalone Accessibility Settings activity; stay safely on dashboard.
        }
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            activityStarter(intent)
        } catch (_: ActivityNotFoundException) {
            openAppInfoSettings()
        }
    }

    private fun enableKeepAliveNotification() {
        val customRequester = notificationPermissionRequester
        if (customRequester != null) {
            customRequester()
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            activityStarter(intent)
        } catch (_: ActivityNotFoundException) {
            openAppInfoSettings()
        }
    }

    private fun openAutostartSettings() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        }
        try {
            activityStarter(intent)
        } catch (_: ActivityNotFoundException) {
            openAppInfoSettings()
        } catch (_: SecurityException) {
            openAppInfoSettings()
        }
    }

    private fun openAppInfoSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            activityStarter(intent)
        } catch (_: ActivityNotFoundException) {
            // Device lacks an App Info settings activity; stay safely on dashboard.
        }
    }
}
