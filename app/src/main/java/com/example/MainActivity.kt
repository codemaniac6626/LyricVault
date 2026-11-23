package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.navigation.LyricVaultApp
import com.example.ui.theme.LyricVaultTheme
import com.example.ui.viewmodel.LyricVaultViewModel
import com.example.widget.WidgetActions

class MainActivity : ComponentActivity() {

    // The home-screen quick-capture widget launches this activity with an
    // ACTION_VIEW intent carrying the tile's lyricvault:// deep link. The
    // intent is handed to the Compose navigation layer, which resolves it
    // with NavController.handleDeepLink exactly once and then clears it.
    // launchMode is singleTop, so widget taps while the app is already open
    // arrive via onNewIntent instead of a second copy of the activity.
    private val widgetIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetIntent.value = intent?.takeIf { it.dataString in WidgetActions.URIS }
        setContent {
            val viewModel: LyricVaultViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            LyricVaultTheme(themeMode = themeMode) {
                LyricVaultApp(
                    viewModel = viewModel,
                    widgetIntent = widgetIntent.value,
                    onWidgetIntentConsumed = { widgetIntent.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetIntent.value = intent.takeIf { it.dataString in WidgetActions.URIS }
    }
}
