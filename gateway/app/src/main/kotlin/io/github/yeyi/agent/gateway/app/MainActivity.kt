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
    private var isRunning by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startGatewayService()

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
        if (isRunning) {
            Intent(GatewayService.START)
                .apply { setClassName(packageName, GatewayService::class.java.name) }
                .run { stopService(this) }
            isRunning = false
        } else {
            startGatewayService()
            isRunning = true
        }
    }

    private fun startGatewayService() {
        val intent = Intent().apply {
            setPackage("io.github.yeyi.agent.gateway.app")
            setAction("io.github.yeyi.agent.gateway.app.START")
        }
        startForegroundService(intent)
    }
}
