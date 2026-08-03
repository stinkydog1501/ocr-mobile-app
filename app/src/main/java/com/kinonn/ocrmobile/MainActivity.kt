package com.kinonn.ocrmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kinonn.ocrmobile.ui.navigation.AppNavHost
import com.kinonn.ocrmobile.ui.theme.OcrMobileTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OcrMobileTheme {
                AppNavHost()
            }
        }
    }
}
