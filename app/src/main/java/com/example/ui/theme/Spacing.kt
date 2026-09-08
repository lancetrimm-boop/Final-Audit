package com.example.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Aura Standardized Spacing System
 * Establishes a consistent 4dp-based grid for all layout margins and gaps.
 */
object AuraSpacing {
    val XXXS = 2.dp
    val XXS = 4.dp
    val XS = 8.dp
    val S = 12.dp
    val M = 16.dp
    val L = 24.dp
    val XL = 32.dp
    val XXL = 40.dp
    val XXXL = 48.dp

    // Semantic mappings
    val ScreenHorizontal = M
    val SectionSpacing = L
    val GridGap = S
    val ControlGap = XS
    val HeaderBottom = XS
    
    // Component specific
    val CardInternal = S
    val ChipHorizontal = S
    val ChipVertical = XXS
    val ButtonHeight = 44.dp
    val CompactButtonHeight = 32.dp
    val IconSizeSmall = 16.dp
    val IconSizeMedium = 24.dp
    val CornerRadiusSmall = 8.dp
    val CornerRadiusMedium = 16.dp
    val CornerRadiusLarge = 24.dp
}
