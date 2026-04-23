package com.ismartcoding.plain.ui.page.web

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ismartcoding.plain.BuildConfig
import com.ismartcoding.plain.R
import com.ismartcoding.plain.preferences.KeepAliveDeviceAdminEnabledPreference
import com.ismartcoding.plain.preferences.KeepAliveVpnEnabledPreference
import com.ismartcoding.plain.preferences.KeepAliveWatchdogEnabledPreference
import com.ismartcoding.plain.receivers.KeepAliveWatchdogReceiver
import com.ismartcoding.plain.receivers.PlainDeviceAdminReceiver
import com.ismartcoding.plain.services.KeepAliveVpnService
import com.ismartcoding.plain.ui.base.BottomSpace
import com.ismartcoding.plain.ui.base.PCard
import com.ismartcoding.plain.ui.base.PListItem
import com.ismartcoding.plain.ui.base.PScaffold
import com.ismartcoding.plain.ui.base.PSwitch
import com.ismartcoding.plain.ui.base.PTopAppBar
import com.ismartcoding.plain.ui.base.Subtitle
import com.ismartcoding.plain.ui.base.Tips
import com.ismartcoding.plain.ui.base.TopSpace
import com.ismartcoding.plain.ui.base.VerticalSpace
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlwaysOnPage(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOpt(context)) }
    var accessibilityOk by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var vpnEnabled by remember { mutableStateOf(false) }
    var deviceAdminOn by remember { mutableStateOf(isDeviceAdminActive(context)) }
    var watchdogEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        vpnEnabled = KeepAliveVpnEnabledPreference.getAsync(context)
        watchdogEnabled = KeepAliveWatchdogEnabledPreference.getAsync(context)
        // Re-check live state in case user came back from settings
        batteryOk = isIgnoringBatteryOpt(context)
        accessibilityOk = isAccessibilityEnabled(context)
        deviceAdminOn = isDeviceAdminActive(context)
    }

    val vpnPrepLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            scope.launch {
                KeepAliveVpnEnabledPreference.putAsync(context, true)
                vpnEnabled = true
                ContextCompat.startForegroundService(
                    context, Intent(context, KeepAliveVpnService::class.java)
                )
            }
        }
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        deviceAdminOn = isDeviceAdminActive(context)
        scope.launch { KeepAliveDeviceAdminEnabledPreference.putAsync(context, deviceAdminOn) }
    }

    PScaffold(topBar = {
        PTopAppBar(navController = navController, title = stringResource(R.string.always_on_title))
    }, content = { paddingValues ->
        LazyColumn(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
            item {
                TopSpace()
                Tips(stringResource(R.string.always_on_intro))
                VerticalSpace(dp = 8.dp)
            }

            item { Subtitle(text = stringResource(R.string.always_on_section_app)) }

            // 1. Battery optimization
            item {
                PCard {
                    PListItem(
                        modifier = Modifier.clickable {
                            try {
                                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                i.data = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                                context.startActivity(i)
                            } catch (_: Throwable) {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        title = stringResource(R.string.always_on_battery_opt),
                        subtitle = if (batteryOk) stringResource(R.string.always_on_battery_opt_done)
                                   else stringResource(R.string.always_on_battery_opt_desc),
                        showMore = !batteryOk,
                    )
                }
                VerticalSpace(dp = 12.dp)
            }

            // 2. Accessibility
            item {
                PCard {
                    PListItem(
                        modifier = Modifier.clickable {
                            try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
                        },
                        title = stringResource(R.string.always_on_accessibility),
                        subtitle = if (accessibilityOk) stringResource(R.string.always_on_accessibility_done)
                                   else stringResource(R.string.always_on_accessibility_desc),
                        showMore = !accessibilityOk,
                    )
                }
                VerticalSpace(dp = 12.dp)
            }

            // 3. Keep-Alive VPN
            item {
                PCard {
                    PListItem(
                        title = stringResource(R.string.always_on_vpn),
                        subtitle = if (vpnEnabled) stringResource(R.string.always_on_vpn_done)
                                   else stringResource(R.string.always_on_vpn_desc),
                    ) {
                        PSwitch(activated = vpnEnabled) { enable ->
                            if (enable) {
                                val prep = VpnService.prepare(context)
                                if (prep == null) {
                                    scope.launch {
                                        KeepAliveVpnEnabledPreference.putAsync(context, true)
                                        vpnEnabled = true
                                        ContextCompat.startForegroundService(
                                            context, Intent(context, KeepAliveVpnService::class.java)
                                        )
                                    }
                                } else {
                                    vpnPrepLauncher.launch(prep)
                                }
                            } else {
                                scope.launch {
                                    KeepAliveVpnEnabledPreference.putAsync(context, false)
                                    vpnEnabled = false
                                    try { context.stopService(Intent(context, KeepAliveVpnService::class.java)) } catch (_: Throwable) {}
                                }
                            }
                        }
                    }
                    if (vpnEnabled) {
                        PListItem(
                            modifier = Modifier.clickable {
                                try { context.startActivity(Intent("android.net.vpn.SETTINGS")) }
                                catch (_: Throwable) {
                                    try { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } catch (_: Throwable) {}
                                }
                            },
                            title = stringResource(R.string.always_on_vpn_open_settings),
                            showMore = true,
                        )
                    }
                }
                VerticalSpace(dp = 12.dp)
            }

            // 4. Device Admin
            item {
                PCard {
                    PListItem(
                        modifier = Modifier.clickable {
                            if (!deviceAdminOn) {
                                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                i.putExtra(
                                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    ComponentName(context, PlainDeviceAdminReceiver::class.java)
                                )
                                i.putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Helps PlainApp stay running 24/7."
                                )
                                deviceAdminLauncher.launch(i)
                            } else {
                                try { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Throwable) {}
                            }
                        },
                        title = stringResource(R.string.always_on_device_admin),
                        subtitle = if (deviceAdminOn) stringResource(R.string.always_on_device_admin_done)
                                   else stringResource(R.string.always_on_device_admin_desc),
                        showMore = !deviceAdminOn,
                    )
                }
                VerticalSpace(dp = 12.dp)
            }

            // 5. Watchdog
            item {
                PCard {
                    PListItem(
                        title = stringResource(R.string.always_on_watchdog),
                        subtitle = stringResource(R.string.always_on_watchdog_desc),
                    ) {
                        PSwitch(activated = watchdogEnabled) { enable ->
                            scope.launch {
                                KeepAliveWatchdogEnabledPreference.putAsync(context, enable)
                                watchdogEnabled = enable
                                if (enable) KeepAliveWatchdogReceiver.schedule(context)
                                else KeepAliveWatchdogReceiver.cancel(context)
                            }
                        }
                    }
                }
                VerticalSpace(dp = 16.dp)
            }

            item { Subtitle(text = stringResource(R.string.always_on_section_oem)) }

            item {
                ExpandableCard(R.string.always_on_oem_xiaomi, R.string.always_on_oem_xiaomi_steps)
                ExpandableCard(R.string.always_on_oem_realme_oppo, R.string.always_on_oem_realme_oppo_steps)
                ExpandableCard(R.string.always_on_oem_vivo, R.string.always_on_oem_vivo_steps)
                ExpandableCard(R.string.always_on_oem_oneplus, R.string.always_on_oem_oneplus_steps)
                ExpandableCard(R.string.always_on_oem_samsung, R.string.always_on_oem_samsung_steps)
                ExpandableCard(R.string.always_on_oem_stock, R.string.always_on_oem_stock_steps)
                VerticalSpace(dp = 16.dp)
            }

            item {
                Subtitle(text = stringResource(R.string.always_on_what_you_get_title))
                Tips(stringResource(R.string.always_on_what_you_get))
            }

            item { BottomSpace(paddingValues) }
        }
    })
}

@Composable
private fun ExpandableCard(titleRes: Int, bodyRes: Int) {
    var open by remember { mutableStateOf(false) }
    PCard {
        PListItem(
            modifier = Modifier.clickable { open = !open },
            title = stringResource(titleRes),
        ) {
            Icon(
                imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }
        if (open) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    VerticalSpace(dp = 8.dp)
}

private fun isIgnoringBatteryOpt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    return try {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.contains("${context.packageName}/")
    } catch (_: Throwable) { false }
}

private fun isDeviceAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
    val cn = ComponentName(context, PlainDeviceAdminReceiver::class.java)
    return dpm.isAdminActive(cn)
}
