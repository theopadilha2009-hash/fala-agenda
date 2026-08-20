package com.theopadilha.falaagenda.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.ui.theme.CardWhite
import com.theopadilha.falaagenda.ui.theme.DeepGreen
import com.theopadilha.falaagenda.ui.theme.Line
import com.theopadilha.falaagenda.ui.theme.OffWhite

val CardShape = RoundedCornerShape(16.dp)

@Composable
fun QuietCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = CardWhite,
        border = BorderStroke(1.dp, Line),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DeepGreen, contentColor = OffWhite),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(text)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepGreen),
    ) {
        Text(text)
    }
}
