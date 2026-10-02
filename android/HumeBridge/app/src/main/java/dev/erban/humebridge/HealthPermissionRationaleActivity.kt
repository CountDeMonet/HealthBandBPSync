package dev.erban.humebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class HealthPermissionRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("HumeBridge Blood Pressure Access", fontWeight = FontWeight.SemiBold)
                        Text(
                            "HumeBridge requests blood-pressure write access to save Hume/J2208-derived BP estimates from your band into Health Connect. Read access is used only to verify records written by HumeBridge and avoid duplicates."
                        )
                    }
                }
            }
        }
    }
}
