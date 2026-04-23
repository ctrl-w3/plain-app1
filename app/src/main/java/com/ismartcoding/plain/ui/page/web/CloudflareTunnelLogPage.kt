package com.ismartcoding.plain.ui.page.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ismartcoding.plain.R
import com.ismartcoding.plain.services.TunnelLogger
import com.ismartcoding.plain.ui.base.BottomSpace
import com.ismartcoding.plain.ui.base.PFilledButton
import com.ismartcoding.plain.ui.base.PScaffold
import com.ismartcoding.plain.ui.base.PTopAppBar
import com.ismartcoding.plain.ui.base.VerticalSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareTunnelLogPage(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        while (true) {
            text = withContext(Dispatchers.IO) { TunnelLogger.read(context) }
            scroll.animateScrollTo(scroll.maxValue)
            delay(2000)
        }
    }

    PScaffold(
        topBar = { PTopAppBar(navController = navController, title = stringResource(R.string.cloudflare_tunnel_log_title)) },
        content = { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PFilledButton(
                        text = stringResource(R.string.cloudflare_tunnel_copy_full_log),
                        onClick = {
                            scope.launch {
                                val full = withContext(Dispatchers.IO) { TunnelLogger.readAll(context) }
                                copyToClip(context, full)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.cloudflare_tunnel_log_copied, full.length),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PFilledButton(
                        text = stringResource(R.string.clear),
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                TunnelLogger.clear(context)
                                text = ""
                            }
                        },
                    )
                }
                Text(
                    text = if (text.isEmpty()) stringResource(R.string.cloudflare_tunnel_log_empty) else text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp),
                )
                BottomSpace(padding)
            }
        },
    )
}

private fun copyToClip(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("tunnel-log", text))
}
