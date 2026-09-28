package com.theopadilha.falaagenda.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.SecondaryButton

@Composable
fun WriteTaskScreen(
    heading: String,
    help: String,
    placeholder: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    understanding: Boolean = false,
    saving: Boolean = false,
    externalError: String? = null,
    onTextChanged: () -> Unit = {},
) {
    // O que foi ditado ou digitado não pode sumir ao girar o aparelho: o campo volta
    // preenchido, com o mesmo texto que a pessoa escreveu.
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Com IA o parse leva até ~20 s: sem isto o toque não tem resposta nenhuma, e o
    // recado já entregue seria entregue de novo. Um por vez, como na home. A gravação
    // (o "Daqui N min") tem o mesmo problema com o tempo do alarme e do banco, e o mesmo
    // tratamento: o botão sai da mão dela e a tela diz que está trabalhando.
    val busy = understanding || saving
    val submit = {
        val value = text.trim()
        if (value.isBlank()) {
            error = "Escreva o recado neste campo."
        } else if (!busy) {
            onConfirm(value)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                heading,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(help, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                    onTextChanged()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .focusRequester(focus),
                label = { Text("O que precisa ser feito") },
                placeholder = { Text(placeholder) },
                minLines = 2,
                maxLines = 5,
                isError = (externalError ?: error) != null,
                supportingText = (externalError ?: error)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            if (busy) {
                // Ela apertou e o app está pensando: dizer isso é a diferença entre
                // esperar e achar que o toque não pegou.
                Text(
                    if (understanding) "Entendendo o recado…" else "Salvando…",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            PrimaryButton(confirmLabel, enabled = !busy, onClick = submit)
            // Sair no meio da gravação deixaria o aviso salvo sem ninguém para anunciá-lo:
            // o desfecho chega quando a tela já não existe e ela nunca sabe se valeu.
            SecondaryButton("Cancelar", enabled = !saving, onClick = onCancel)
        }
    }
}
