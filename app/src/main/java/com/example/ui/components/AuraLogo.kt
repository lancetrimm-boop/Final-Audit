package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import com.example.R
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.DiscoveryGradient
import com.example.ui.theme.DiscoveryViolet

/**
 * Authoritative Brand Name.
 * "Aura"
 */
@Composable
fun AuraBrandName(
    modifier: Modifier = Modifier,
    fontSize: Float = 20f, // Slightly larger base
    letterSpacing: Float = -0.5f,
    useGradient: Boolean = true // Default to gradient for "Authoritative" look
) {
    val baseStyle = MaterialTheme.typography.headlineMedium
    val style = if (useGradient) {
        baseStyle.copy(
            brush = DiscoveryGradient,
            fontWeight = FontWeight.Black, // Stronger
            fontSize = fontSize.sp,
            letterSpacing = letterSpacing.sp
        )
    } else {
        baseStyle.copy(
            color = AuraMidnight,
            fontWeight = FontWeight.Black,
            fontSize = fontSize.sp,
            letterSpacing = letterSpacing.sp
        )
    }

    Text(
        text = stringResource(id = R.string.brand_name_full),
        style = style,
        modifier = modifier
    )
}

/**
 * Aura Unified Visual Identity — Mark Only.
 * Geometric A + centered Aura gradient circle.
 */
@Composable
fun AuraLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, DiscoveryGradient, CircleShape)
            .background(AuraMidnight),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = R.drawable.aura_logo_mark,
            contentDescription = "Aura Logo",
            modifier = Modifier.fillMaxSize().padding(6.dp), // More breathing room for mark
            error = androidx.compose.ui.graphics.vector.rememberVectorPainter(image = Icons.Outlined.AutoAwesome),
            fallback = androidx.compose.ui.graphics.vector.rememberVectorPainter(image = Icons.Outlined.AutoAwesome)
        )
    }
}

/**
 * Compatibility alias for AuraLogoMark.
 */
@Composable
fun AuraLogoIcon(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
    AuraLogoMark(modifier, size)
}

/**
 * Full Aura Branding Lockup.
 * "Aura" + "Media Player"
 */
@Composable
fun AuraFullLockup(
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp,
    fontSize: Float = 24f
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        AuraLogoMark(size = iconSize)
        Spacer(modifier = Modifier.height(AuraSpacing.S))
        Text(
            text = stringResource(id = R.string.brand_name_full),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Black,
                fontSize = fontSize.sp,
                letterSpacing = (-0.5).sp,
                brush = DiscoveryGradient
            )
        )
        Text(
            text = stringResource(id = R.string.brand_description).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = AuraMutedSlate,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        )
    }
}

/**
 * Horizontal Aura Branding Wordmark.
 */
@Composable
fun AuraWordmark(
    modifier: Modifier = Modifier,
    fontSize: Float = 20f
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        AuraLogoMark(size = (fontSize * 1.2f).dp)
        Spacer(modifier = Modifier.width(AuraSpacing.S))
        Text(
            text = stringResource(id = R.string.brand_name_full),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Black,
                fontSize = fontSize.sp,
                letterSpacing = (-0.25).sp,
                brush = DiscoveryGradient
            )
        )
    }
}

/**
 * Full Product Category Lockup.
 */
@Composable
fun AuraProductLockup(
    modifier: Modifier = Modifier,
    fontSize: Float = 20f
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        AuraLogoMark(size = (fontSize * 1.3f).dp)
        Spacer(modifier = Modifier.width(AuraSpacing.S))
        Column {
            Text(
                text = stringResource(id = R.string.brand_name_full),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = fontSize.sp,
                    letterSpacing = (-0.25).sp,
                    brush = DiscoveryGradient
                )
            )
            Text(
                text = stringResource(id = R.string.brand_description).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = AuraMutedSlate,
                    fontWeight = FontWeight.Black,
                    fontSize = (fontSize * 0.45f).sp,
                    letterSpacing = 1.sp
                )
            )
        }
    }
}
