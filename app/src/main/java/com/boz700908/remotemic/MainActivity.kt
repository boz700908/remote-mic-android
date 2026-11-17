package com.boz700908.remotemic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.boz700908.remotemic.ui.App
import com.boz700908.remotemic.ui.RemoteMicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemoteMicTheme {
                App()
            }
        }
    }
}
