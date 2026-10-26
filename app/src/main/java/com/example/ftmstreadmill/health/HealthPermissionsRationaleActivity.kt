package com.example.ftmstreadmill.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ui.theme.FtmsTheme

/** Shown by Health Connect when the user asks why the app wants access. */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FtmsTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.hc_rationale_title)) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.hc_rationale_body), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.hc_rationale_data), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
