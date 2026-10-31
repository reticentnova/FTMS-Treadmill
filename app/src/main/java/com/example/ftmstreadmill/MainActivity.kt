package com.example.ftmstreadmill

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ftmstreadmill.ui.nav.AppShell
import com.example.ftmstreadmill.ui.nav.AppViewModel
import com.example.ftmstreadmill.ui.theme.FtmsTheme
import com.example.ftmstreadmill.ui.theme.isDark
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: after rotation the file has already been imported.
        if (savedInstanceState == null) handleImport(intent)
        setContent {
            val theme by appViewModel.theme.collectAsStateWithLifecycle()
            // DataStore loads in a few milliseconds; skip that frame rather than flash the default colours.
            val loaded = theme ?: return@setContent
            val dark = loaded.isDark()
            LaunchedEffect(dark) { applySystemBarStyle(dark) }
            FtmsTheme(loaded) {
                AppShell(appViewModel)
            }
        }
    }

    /** Keeps status and navigation bar icons legible when the app's light/dark mode differs from the system's. */
    private fun applySystemBarStyle(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImport(intent)
    }

    /** A workout file opened or shared from another app. */
    private fun handleImport(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        appViewModel.import(uri)
    }
}
