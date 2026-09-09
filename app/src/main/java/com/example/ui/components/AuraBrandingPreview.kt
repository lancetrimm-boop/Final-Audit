package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.R

@Preview(name = "Aura PNG Asset (aura_logo_mark)", showBackground = true, backgroundColor = 0xFF0F1117)
@Composable
fun AuraPngPreview() {
    Box(
        modifier = Modifier
            .size(200.dp)
            .background(Color(0xFF0F1117)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.aura_logo_mark),
            contentDescription = null,
            modifier = Modifier.size(200.dp)
        )
    }
}

@Preview(name = "Aura Vector Asset (Current State)", showBackground = true, backgroundColor = 0xFF0F1117)
@Composable
fun AuraVectorPreview() {
    Box(
        modifier = Modifier
            .size(200.dp)
            .background(Color(0xFF0F1117)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.aura_logo_vector),
            contentDescription = null,
            modifier = Modifier.size(200.dp)
        )
    }
}
