package com.theopadilha.falaagenda.ui.capture

import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.home.SpeechUiState

/** A frase da tela de escrita. A da home manda "escreva a tarefa", que aqui não serve. */
const val WRITE_UNDERSTAND_FAILED_MESSAGE = "Não consegui entender o recado. Tente de novo."

/** O que a tela de escrita faz com o que voltou do parse. */
sealed interface WriteStep {
    /** Ainda entendendo (com IA são até ~20 s): a tela fica onde está, com o texto no campo. */
    data object Waiting : WriteStep

    /** Entendeu: sai para a confirmação com o rascunho. */
    data class Ready(val draft: ParsedTaskDraft) : WriteStep

    /** Não entendeu: a frase aparece na própria tela, que continua aberta. */
    data class Failed(val message: String) : WriteStep
}

/**
 * Quem decide o destino da tela de escrita é esta função, e não a composição: o rascunho
 * tem que mandar para a confirmação mesmo que chegue depois de a tela ser recriada (giro
 * do aparelho no meio dos ~20 s do parse com IA), e a falha tem que aparecer no campo em
 * vez de sumir junto com a tela que a pediu.
 *
 * O rascunho vem antes do erro porque é o desfecho que interessa a ela; enquanto não há
 * nem um nem outro, não há o que mostrar.
 */
fun writeStepFor(state: SpeechUiState): WriteStep = when {
    state.draft != null -> WriteStep.Ready(state.draft)
    state.error != null -> WriteStep.Failed(WRITE_UNDERSTAND_FAILED_MESSAGE)
    else -> WriteStep.Waiting
}
