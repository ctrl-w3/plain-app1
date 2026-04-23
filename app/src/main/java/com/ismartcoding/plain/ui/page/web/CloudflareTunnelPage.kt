package com.ismartcoding.plain.ui.page.web

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ismartcoding.plain.R
import com.ismartcoding.plain.enums.ButtonType
import com.ismartcoding.plain.preferences.CloudflareTunnelAutoStartPreference
import com.ismartcoding.plain.preferences.CloudflareTunnelEnabledPreference
import com.ismartcoding.plain.preferences.CloudflareTunnelHostnamePreference
import com.ismartcoding.plain.preferences.CloudflareTunnelTokenPreference
import com.ismartcoding.plain.services.CloudflareTunnelManager
import com.ismartcoding.plain.services.CloudflareTunnelService
import com.ismartcoding.plain.ui.base.BottomSpace
import com.ismartcoding.plain.ui.base.PCard
import com.ismartcoding.plain.ui.base.PFilledButton
import com.ismartcoding.plain.ui.base.PListItem
import com.ismartcoding.plain.ui.base.PScaffold
import com.ismartcoding.plain.ui.base.PSwitch
import com.ismartcoding.plain.ui.base.PTextField
import com.ismartcoding.plain.ui.base.PTopAppBar
import com.ismartcoding.plain.ui.base.Subtitle
import com.ismartcoding.plain.ui.base.Tips
import com.ismartcoding.plain.ui.base.TopSpace
import com.ismartcoding.plain.ui.base.VerticalSpace
import com.ismartcoding.plain.ui.helpers.WebHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareTunnelPage(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(true) }
    var token by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        enabled = CloudflareTunnelEnabledPreference.getAsync(context)
        autoStart = CloudflareTunnelAutoStartPreference.getAsync(context)
        token = CloudflareTunnelTokenPreference.getAsync(context)
        hostname = CloudflareTunnelHostnamePreference.getAsync(context)
        statusText = describeStatus(context)
    }

    PScaffold(
        topBar = {
            PTopAppBar(navController = navController, title = stringResource(R.string.cloudflare_tunnel_title))
        },
        content = { paddingValues ->
            LazyColumn(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
                item {
                    TopSpace()
                    Tips(text = stringResource(R.string.cloudflare_tunnel_intro))

                    VerticalSpace(dp = 8.dp)
                    Subtitle(text = stringResource(R.string.status))
                    PCard {
                        PListItem(
                            title = stringResource(R.string.cloudflare_tunnel_enabled),
                            subtitle = statusText,
                        ) {
                            PSwitch(activated = enabled) { newEnabled ->
                                enabled = newEnabled
                                scope.launch(Dispatchers.IO) {
                                    CloudflareTunnelEnabledPreference.putAsync(context, newEnabled)
                                    if (newEnabled) {
                                        CloudflareTunnelManager.start(context)
                                    } else {
                                        CloudflareTunnelManager.stop(context)
                                    }
                                    statusText = describeStatus(context)
                                }
                            }
                        }
                        PListItem(
                            title = stringResource(R.string.cloudflare_tunnel_auto_start),
                            subtitle = stringResource(R.string.cloudflare_tunnel_auto_start_desc),
                        ) {
                            PSwitch(activated = autoStart) { v ->
                                autoStart = v
                                scope.launch(Dispatchers.IO) {
                                    CloudflareTunnelAutoStartPreference.putAsync(context, v)
                                }
                            }
                        }
                    }

                    VerticalSpace(dp = 16.dp)
                    Subtitle(text = stringResource(R.string.cloudflare_tunnel_token))
                    PCard {
                        PTextField(
                            readOnly = false,
                            value = token,
                            onValueChange = { token = it },
                            placeholder = stringResource(R.string.cloudflare_tunnel_token_placeholder),
                            singleLine = false,
                        )
                    }
                    Tips(text = stringResource(R.string.cloudflare_tunnel_token_help))

                    VerticalSpace(dp = 16.dp)
                    Subtitle(text = stringResource(R.string.cloudflare_tunnel_hostname))
                    PCard {
                        PTextField(
                            readOnly = false,
                            value = hostname,
                            onValueChange = { hostname = it },
                            placeholder = "phone.shakti.buzz",
                        )
                    }
                    Tips(text = stringResource(R.string.cloudflare_tunnel_hostname_help))

                    VerticalSpace(dp = 24.dp)
                    PFilledButton(
                        text = stringResource(R.string.save_and_apply),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                CloudflareTunnelTokenPreference.putAsync(context, token.trim())
                                CloudflareTunnelHostnamePreference.putAsync(context, hostname.trim())
                                if (enabled) {
                                    CloudflareTunnelManager.restart(context)
                                }
                                statusText = describeStatus(context)
                            }
                        },
                    )

                    VerticalSpace(dp = 12.dp)
                    PFilledButton(
                        text = stringResource(R.string.cloudflare_tunnel_open_dashboard),
                        type = ButtonType.PRIMARY,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = {
                            WebHelper.open(context, "https://one.dash.cloudflare.com/")
                        },
                    )

                    VerticalSpace(dp = 12.dp)
                    PFilledButton(
                        text = stringResource(R.string.cloudflare_tunnel_open_url),
                        type = ButtonType.PRIMARY,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = {
                            val h = hostname.trim()
                            if (h.isNotEmpty()) WebHelper.open(context, "https://$h")
                        },
                    )

                    VerticalSpace(dp = 16.dp)
                    Tips(text = stringResource(R.string.cloudflare_tunnel_battery_tip))
                    BottomSpace(paddingValues)
                }
            }
        },
    )
}

private fun describeStatus(context: android.content.Context): String {
    val s = CloudflareTunnelService.status
    val err = CloudflareTunnelService.lastError
    val base = when (s) {
        CloudflareTunnelService.Companion.Status.RUNNING -> context.getString(R.string.cloudflare_tunnel_status_running)
        CloudflareTunnelService.Companion.Status.STARTING -> context.getString(R.string.cloudflare_tunnel_status_starting)
        CloudflareTunnelService.Companion.Status.ERROR -> context.getString(R.string.cloudflare_tunnel_status_error)
        CloudflareTunnelService.Companion.Status.STOPPED -> context.getString(R.string.cloudflare_tunnel_status_stopped)
    }
    return if (err.isNotEmpty() && s == CloudflareTunnelService.Companion.Status.ERROR) "$base: $err" else base
}
