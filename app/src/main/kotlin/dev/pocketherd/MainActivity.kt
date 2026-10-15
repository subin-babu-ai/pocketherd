package dev.pocketherd

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.AndroidEntryPoint
import dev.pocketherd.core.ui.theme.PocketHerdTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketHerdTheme {
                RequestLocalNetworkAccess()
                PocketHerdNavHost()
            }
        }
    }
}

/**
 * Asks for local network access on Android 16+.
 *
 * Every machine this app talks to is reachable by IP on a network the phone is already on,
 * so without this permission the app is useless: connections fail as ordinary socket
 * timeouts with nothing in the app to explain them. Denial is not fatal, since a host
 * reached over a VPN or the public internet still works.
 */
@Composable
private fun RequestLocalNetworkAccess() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Either way the connection attempt proceeds and reports what happened. */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 36) return@LaunchedEffect
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(permission)
        }
    }
}
