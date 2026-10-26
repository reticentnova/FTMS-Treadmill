package com.example.ftmstreadmill

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.example.ftmstreadmill.ui.nav.AppShell
import com.example.ftmstreadmill.ui.nav.AppViewModel
import com.example.ftmstreadmill.ui.theme.FtmsTheme
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
            FtmsTheme {
                AppShell(appViewModel)
            }
        }
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
