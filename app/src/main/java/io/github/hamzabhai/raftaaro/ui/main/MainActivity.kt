package io.github.hamzabhai.raftaaro.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RaftaaroTheme {
                Surface(Modifier.fillMaxSize()) {
                    Text("Raftaaro", Modifier.safeDrawingPadding().padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}
