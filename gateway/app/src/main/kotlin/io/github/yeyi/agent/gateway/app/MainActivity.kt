package io.github.yeyi.agent.gateway.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var isRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column {
                Text(if (isRunning) "Gateway: running" else "Gateway: stopped")
                Button(onClick = ::toggleService) {
                    Text(if (isRunning) "Stop" else "Start")
                }
            }
        }
    }

    private fun toggleService() {
        val intent = Intent(GatewayService.START).apply {
            setClassName(this@MainActivity.packageName, GatewayService::class.java.name)
        }
        if (isRunning) {
            stopService(intent)
            isRunning = false
        } else {
            startForegroundService(intent)
            isRunning = true
        }
    }
}
