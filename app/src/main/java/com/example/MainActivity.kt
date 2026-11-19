package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.navigation.LyricVaultApp
import com.example.ui.theme.LyricVaultTheme
import com.example.ui.viewmodel.LyricVaultViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: LyricVaultViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            LyricVaultTheme(themeMode = themeMode) {
                LyricVaultApp(viewModel = viewModel)
            }
        }
    }
}
