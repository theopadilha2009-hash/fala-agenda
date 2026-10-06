package com.theopadilha.falaagenda.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.speech.VoiceState
import kotlinx.coroutines.delay

@Composable
fun PulsingMic(
    state: VoiceState,
    // Sem descrição este microfone é ilustração: fica fora da leitura de tela, em vez de
    // virar uma parada de foco que anuncia e não responde ao toque duplo.
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val listening = state == VoiceState.LISTENING
    val idle = state == VoiceState.IDLE || state == VoiceState.PREPARING
    val actionable = onClick != null
    val description = contentDescription
    // Sem ação o microfone não é o mesmo botão verde cheio: durante os ~20 s de
    // "Entendendo o recado…" ele parecia clicável e o toque não devolvia nada. O disco fica
    // no `outlineVariant` do tema — a linha clara, não o contorno do cartão: um cinza neutro,
    // pouco acima ou abaixo do fundo (#E4DFD4 sobre o creme no claro, 1,2:1; #3A4742 sobre o
    // verde-escuro no escuro, 1,7:1), então quem sustenta a leitura é o ícone — 5,0:1 no claro
    // e 5,3:1 no escuro. O pulso para junto.
    //
    // Não pode ser o `outline`: ele escureceu para o contorno do cartão se enxergar sobre o
    // fundo (3,3:1), e com ele aqui o ícone cairia para 1,8:1 — o disco vira uma bola chapada
    // sem o desenho dentro, que é justamente a leitura que este estado precisa dar.
    val fill =
        if (actionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val iconTint =
        if (actionable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val idleScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle-scale",
    )
    val wave = rememberInfiniteTransition(label = "mic-wave")
    val waveProgress by wave.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave",
    )
    var shake by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state) {
        if (state == VoiceState.ERROR) {
            for (delta in listOf(-10f, 10f, -8f, 8f, -4f, 4f, 0f)) {
                shake = delta
                delay(35)
            }
        } else {
            shake = 0f
        }
    }
    val scale = if (idle && actionable) idleScale else 1f
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(120.dp)
            .offset(x = shake.dp),
    ) {
        if (listening) {
            WaveRing(progress = waveProgress)
            WaveRing(progress = (waveProgress + 0.5f) % 1f)
        }
        val buttonMod = Modifier
            .size(88.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill)
            .semantics {
                if (description != null) {
                    this.contentDescription = description
                }
            }
        if (onClick != null) {
            IconButton(onClick = onClick, modifier = buttonMod) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            Box(modifier = buttonMod, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

@Composable
private fun WaveRing(progress: Float) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .graphicsLayer {
                val grown = 1f + progress * 0.75f
                scaleX = grown
                scaleY = grown
                alpha = (1f - progress) * 0.4f
            }
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
    )
}
