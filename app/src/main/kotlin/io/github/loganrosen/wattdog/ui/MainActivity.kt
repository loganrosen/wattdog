package io.github.loganrosen.wattdog.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.loganrosen.wattdog.WattdogApplication
import io.github.loganrosen.wattdog.alerts.NotificationRoute
import io.github.loganrosen.wattdog.ui.theme.WattdogTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel((application as WattdogApplication).container) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WattdogTheme { WattdogApp(viewModel) } }
        // On recreation the intent is the same one we already routed.
        if (savedInstanceState == null) route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent) {
        NotificationRoute.read(intent)?.let(viewModel::openAction)
    }

    override fun onResume() {
        super.onResume()
        // Picks up Shizuku being started, or a DUMP grant made over adb while we were backgrounded.
        viewModel.recheckAccess()
    }
}
