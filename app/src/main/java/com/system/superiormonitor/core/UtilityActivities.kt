package com.system.superiormonitor.core

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.superiormonitor.theme.AccentGreen
import com.system.superiormonitor.theme.OuterCardSurface
import com.system.superiormonitor.theme.SuperiorMonitorTheme
import com.system.superiormonitor.theme.TextPrimary

class CamouflageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        } catch (e: Exception) {
            com.system.superiormonitor.core.LogManager.log(
                com.system.superiormonitor.core.LogCategory.SYSTEM, 
                "CamouflageActivity error: ${e.message}", 
                com.system.superiormonitor.core.LogLevel.ERROR
            )
        }
        finish()
    }
}

class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = intent.getStringExtra("POPUP_MESSAGE") ?: "No message provided."

        setContent {
            SuperiorMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    PopupDialog(message = message, onDismiss = {
                        val serviceIntent = Intent(this@PopupActivity, com.system.superiormonitor.bot.BotService::class.java).apply {
                            action = "ACTION_POPUP_ACKNOWLEDGED"
                        }
                        startService(serviceIntent)
                        finish()
                    })
                }
            }
        }
    }
}

@Composable
fun PopupDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* Prevent outside touch dismissal */ },
        title = {
            Text(
                "Message",
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                message,
                color = TextPrimary
            )
        },
        confirmButton = {
            Button(
                onClick = { onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("OK", color = Color.White)
            }
        },
        containerColor = OuterCardSurface,
        shape = RoundedCornerShape(24.dp)
    )
}
