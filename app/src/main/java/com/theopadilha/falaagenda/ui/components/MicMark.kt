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
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val listening = state == VoiceState.LISTENING
    val idle = state == VoiceState.IDLE || state == VoiceState.PREPARING
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
    val scale = if (idle) idleScale else 1f
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
            .background(MaterialTheme.colorScheme.primary)
            .semantics { this.contentDescription = contentDescription }
        if (onClick != null) {
            IconButton(onClick = onClick, modifier = buttonMod) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            Box(modifier = buttonMod, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
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
