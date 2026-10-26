package com.example.ftmstreadmill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ftmstreadmill.ui.control.ControlScreen
import com.example.ftmstreadmill.ui.theme.FtmsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FtmsTheme {
                ControlScreen()
            }
        }
    }
}
