package com.theopadilha.falaagenda.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val CardShape = RoundedCornerShape(16.dp)

@Composable
fun QuietCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .semantics { role = Role.Button },
            shape = CardShape,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            content = content,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = CardShape,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            content = content,
        )
    }
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
            .defaultMinSize(minHeight = 56.dp)
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            // Desabilitado, o M3 deixa o texto a 38% de opacidade — 2,4:1 sobre o branco.
            // Ela tocava num "Salvar" que não respondia e não enxergava que o botão existia.
            // O cinza neutro fica em 5,0:1 (claro) e 5,3:1 (escuro), e não se confunde com
            // o verde do botão ligado.
            disabledContainerColor = MaterialTheme.colorScheme.outline,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(text)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            // Mesmo motivo do PrimaryButton: 38% de opacidade sobre o fundo não se lê (5,2:1
            // ou mais contra qualquer superfície do diálogo, nos dois temas). O contorno,
            // porém, fica sem preenchimento: com "Salvar" e "Cancelar" desabilitados ao mesmo
            // tempo (gravação em voo), os dois ficavam idênticos — e um deles é o caminho de
            // sair da caixa.
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text)
    }
}
