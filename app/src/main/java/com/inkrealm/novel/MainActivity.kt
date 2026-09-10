package com.inkrealm.novel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.inkrealm.novel.ui.theme.InkRealmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                enableEdgeToEdge()
            }
        } catch (_: Exception) { }
        setContent {
            InkRealmTheme {
                InkRealmApp()
            }
        }
    }
}
