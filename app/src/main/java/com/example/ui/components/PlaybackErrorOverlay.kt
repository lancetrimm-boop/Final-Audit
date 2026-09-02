package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.playback.PlaybackErrorClassification
import com.example.playback.PlaybackRecoveryAction
import com.example.ui.theme.AuraBorder
import com.example.ui.theme.AuraSurface
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraOnSurface
import com.example.ui.theme.AuraOnSurfaceVariant

@Composable
fun PlaybackErrorOverlay(
    classification: PlaybackErrorClassification,
    onRetry: () -> Unit,
    onConvert: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(24.dp))
                .background(AuraSurface)
                .border(1.dp, AuraBorder, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFEF4444),
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = "Playback Error",
                style = MaterialTheme.typography.titleLarge,
                color = AuraOnSurface,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = classification.message,
                style = MaterialTheme.typography.bodyMedium,
                color = AuraOnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Text(
                text = classification.technicalDetails,
                style = MaterialTheme.typography.labelSmall,
                color = AuraOnSurfaceVariant.copy(alpha = 0.6f),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 10.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary Recommended Action
                when (classification.recommendedAction) {
                    PlaybackRecoveryAction.RETRY -> {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4338CA))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry Playback")
                        }
                    }
                    PlaybackRecoveryAction.CONVERT -> {
                        Button(
                            onClick = onConvert,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4338CA))
                        ) {
                            Icon(Icons.Default.Transform, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Convert Media")
                        }
                    }
                    PlaybackRecoveryAction.SKIP -> {
                        Button(
                            onClick = onSkip,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4338CA))
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Skip to Next")
                        }
                    }
                    PlaybackRecoveryAction.DISMISS -> {}
                }

                // Secondary Actions
                if (classification.recommendedAction != PlaybackRecoveryAction.RETRY && classification.isRecoverable) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try Again Anyway")
                    }
                }

                if (classification.recommendedAction != PlaybackRecoveryAction.SKIP) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip Media", color = AuraOnSurfaceVariant)
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Dismiss", color = AuraOnSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
    }
}
