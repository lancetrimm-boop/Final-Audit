package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.DiscoveryGradient

/**
 * Standardized Aura Section Header.
 * Refined for density and consistent spacing.
 */
@Composable
fun AuraSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable (RowScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = AuraSpacing.ScreenHorizontal, 
                end = AuraSpacing.XS, 
                top = AuraSpacing.XS, 
                bottom = AuraSpacing.XXS
            )
    ) {
        AuraBrandName(
            fontSize = 18f,
            modifier = Modifier.padding(bottom = 0.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        brush = DiscoveryGradient,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraMutedSlate,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp
                    )
                }
            }
            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.padding(start = AuraSpacing.XS)
                ) {
                    actions()
                }
            }
        }
    }
}
