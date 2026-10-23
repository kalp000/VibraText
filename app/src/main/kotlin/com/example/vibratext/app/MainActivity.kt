package com.example.vibratext.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.vibratext.app.ui.VibraTextRoot
import com.example.vibratext.app.ui.theme.VibraTextTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibraTextTheme {
                VibraTextRoot()
            }
        }
    }
}
