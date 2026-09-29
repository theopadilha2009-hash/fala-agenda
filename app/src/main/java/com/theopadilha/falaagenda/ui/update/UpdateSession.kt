package com.theopadilha.falaagenda.ui.update

import com.theopadilha.falaagenda.platform.UpdateCheck
import com.theopadilha.falaagenda.platform.UpdateRefused
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Tudo que a tela de atualização mostra. */
data class UpdateUiState(
    val checking: Boolean = true,
    val downloading: Boolean = false,
    val info: UpdateCheck? = null,
    val apk: File? = null,
    val message: String? = null,
) {
    val working: Boolean get() = checking || downloading
}

/** O que fazer quando ela toca no botão principal com o instalador já baixado. */
sealed interface InstallStep {
    /** O arquivo baixado não está mais no aparelho. */
    data object ApkGone : InstallStep

    /** Falta liberar a instalação de fontes desconhecidas. */
    data object AllowInstall : InstallStep

    data class OpenInstaller(val apk: File) : InstallStep
}

const val APK_GONE_MESSAGE =
    "O arquivo que tinha sido baixado não está mais no aparelho (o Android limpa o cache " +
        "quando falta espaço). Toque em \"Baixar e instalar\" para baixar de novo."

/** O que já foi recusado, para não oferecer de novo: o endereço do arquivo e o recado. */
private data class Refusal(val apkUrl: String, val reason: String)

/**
 * Instalar um arquivo que já não existe faz o instalador falhar com "não foi possível
 * analisar o pacote" e deixa a tela presa nesse botão para sempre. Por isso o arquivo
 * é conferido antes, e antes também de mandar ela liberar a permissão à toa.
 */
fun installStepFor(apk: File?, canInstall: Boolean): InstallStep = when {
    apk == null || !apk.isFile -> InstallStep.ApkGone
    !canInstall -> InstallStep.AllowInstall
    else -> InstallStep.OpenInstaller(apk)
}

/**
 * O download da atualização não pertence à tela. Sair dela ou girar o aparelho não pode
 * cancelar os ~20 MB no meio nem apagar a barra de progresso — por isso o estado mora
 * aqui, num escopo que a composição não controla.
 */
class UpdateSession(
    private val scope: CoroutineScope,
    private val lookUp: suspend () -> UpdateCheck,
    private val fetch: suspend (url: String, sha256Url: String?) -> File,
) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var started = false
    private var refusal: Refusal? = null

    /** Chamada quando a tela abre. Se ela já esteve aqui, o estado guardado é o que vale. */
    fun start() {
        if (started) return
        refresh()
    }

    /** Procura versão nova. Continua rodando mesmo se ela sair da tela no meio. */
    fun refresh() {
        if (_state.value.downloading) return
        started = true
        _state.value = _state.value.copy(checking = true, message = null)
        scope.launch {
            val found = try {
                lookUp()
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(checking = false)
                throw cancelled
            } catch (failed: Exception) {
                _state.value = _state.value.copy(
                    checking = false,
                    message = failed.message ?: "Não consegui procurar atualização.",
                )
                return@launch
            }
            _state.value = _state.value.copy(checking = false, info = comRecusa(found))
        }
    }

    /**
     * A release que já foi recusada continua recusada enquanto for a mesma: mesma URL, mesmo
     * veredito. Sem isto, o "Procurar de novo" traria a mesma oferta da checagem e o botão
     * voltaria a convidar o download que acabou de ser negado. Release nova é outro endereço,
     * e essa volta a ser oferecida.
     */
    private fun comRecusa(found: UpdateCheck): UpdateCheck {
        val recusa = refusal ?: return found
        if (found.apkUrl != recusa.apkUrl) {
            refusal = null
            return found
        }
        return found.copy(newer = false, message = recusa.reason)
    }

    /** Baixa o instalador. Nada aqui depende de quem está olhando a tela. */
    fun downloadNow() {
        val current = _state.value
        val info = current.info ?: return
        val url = info.apkUrl ?: return
        if (current.working || current.apk != null) return
        if (refusal?.apkUrl == url) return
        _state.value = current.copy(downloading = true, message = null)
        scope.launch {
            val apk = try {
                fetch(url, info.sha256Url)
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(downloading = false)
                throw cancelled
            } catch (refused: UpdateRefused) {
                _state.value = _state.value.copy(
                    downloading = false,
                    info = registrarRecusa(refused, url),
                )
                return@launch
            } catch (failed: Exception) {
                _state.value = _state.value.copy(
                    downloading = false,
                    message = failed.message ?: "Não deu para baixar.",
                )
                return@launch
            }
            _state.value = _state.value.copy(downloading = false, apk = apk, message = null)
        }
    }

    /**
     * A recusa entra no `info` (e não só no `message` da tela) para sobreviver ao "Procurar de
     * novo": a checagem seguinte devolveria a mesma oferta, e o botão voltaria a convidar o
     * download que acabou de ser negado. `newer = false` é o que a tela lê para oferecer o
     * download — é o mesmo caminho que a release sem `.sha256` já usava.
     */
    private fun registrarRecusa(refused: UpdateRefused, url: String): UpdateCheck? {
        val recusa = Refusal(url, refused.reason)
        refusal = recusa
        return _state.value.info?.copy(newer = false, message = recusa.reason)
    }

    /** Recado na tela sem mexer no resto do estado. */
    fun report(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    /**
     * Decide o toque em "Instalar agora". Se o arquivo sumiu, a tela volta para o estado
     * de baixar com uma mensagem que explica, em vez de tentar instalar o nada.
     */
    fun installStep(canInstall: Boolean): InstallStep {
        val step = installStepFor(_state.value.apk, canInstall)
        if (step is InstallStep.ApkGone) {
            _state.value = _state.value.copy(apk = null, message = APK_GONE_MESSAGE)
        }
        return step
    }
}
