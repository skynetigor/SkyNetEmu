package com.winlator.cmod.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.telemetry.TelemetryText

/** The one question about telemetry: share anonymous usage data or not. Nothing is sent unless the answer is yes. */
@Composable
internal fun OnboardingPrivacyScreen(onChoose: (Boolean) -> Unit, back: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Insights, null, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(14.dp))
            Text(TelemetryText.TITLE, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(TelemetryText.SENT, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(TelemetryText.NOT_SENT, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(TelemetryText.CHANGE, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Share anonymous usage data") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onChoose(false) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("No thanks") }
            TextButton(onClick = back) { Text("Back") }
        }
    }
}
