package com.voiceping.offlinettseval

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.voiceping.offlinettseval.ui.TtsEvalScreen
import com.voiceping.offlinettseval.ui.theme.OfflineTtsEvalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OfflineTtsEvalTheme {
                TtsEvalScreen()
            }
        }
    }
}

