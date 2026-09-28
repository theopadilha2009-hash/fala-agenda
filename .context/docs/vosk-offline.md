# Fala offline (Vosk) — o motor que não depende do aparelho

Registro da implementação do motor de fala offline. Escrito em 2026-09-28.

## O problema

A transcrição era 100% delegada ao `SpeechRecognizer` do Android, em três estágios:
in-app padrão → on-device (só API ≥ 31) → tela de reconhecimento do sistema. Com
`minSdk 26`, boa parte da base cai direto na tela do sistema, que é uma tela de outra
pessoa no meio do caminho. E o motor in-app pode ser de rede — o erro
"A fala precisa de um reconhecimento do aparelho" vinha daí.

Nenhum byte de áudio era guardado: o `onBufferReceived` estava neutro e não havia
`AudioRecord` no projeto. Áudio que não é capturado não pode ser reprocessado por um
motor melhor depois.

## O que foi feito

- **`SpeechSource`** (`speech/SpeechSource.kt`): a costura que faltava. Interface com
  `start/cancel/destroy` e um listener com os mesmos eventos do `RecognitionListener`.
  Os códigos de erro continuam sendo os do `SpeechRecognizer` (`VoiceRetry.CLIENT`,
  `SPEECH_TIMEOUT`…), então `VoiceRetry` e `VoiceEngine` não precisaram mudar de ideia —
  o Vosk traduz os erros dele para cá.
- **`SystemSpeechSource`**: o motor do aparelho, extraído do controller sem mudança de
  comportamento. Os 7 testes de `VoiceCaptureControllerTest` passaram sem edição — é a
  prova de que a extração preservou o que existia.
- **`VoskSpeechSource`**: microfone cru (16 kHz, mono, PCM 16 bits) direto no Vosk, em
  thread própria porque o `AudioRecord` bloqueia. `acceptWaveForm` devolve `true` quando
  o Vosk entende que a fala acabou — não há "parar" da nossa parte, e é por isso que o
  fim do recado é mais rápido que os 2800 ms de silêncio do motor do sistema.
- **`VoskModel`** + **`VoskOfflineSpeech`**: o modelo (53 MB) não vai no APK nem no git.
  O motor offline só existe quando o diretório está completo.
- **`VoiceEngine`**: `OFFLINE_VOSK` entrou na frente de todos, e há uma regra nova — se o
  offline falhar, o app volta para o motor do sistema, não pula direto para a tela do
  celular. O que é nosso não pode ser pior que o do aparelho.

## Como liga

O app baixa o modelo sozinho, em segundo plano, na primeira abertura em que houver
rede (`OfflineModelInstaller`, disparado pelo `FalaAgendaApplication`). Enquanto não
chega, quem ouve é o motor do sistema — a fala funciona desde o primeiro segundo, e a
escuta seguinte já usa o offline. A consulta é feita a cada escuta, não uma vez só, para
o modelo que chega com o app aberto não ficar esperando a próxima abertura.

O download segue as mesmas regras do instalador de APK: host em allowlist
(`alphacephei.com`, a fonte oficial dos modelos do Vosk), sha256 fixo no código,
teto de tamanho, extração que recusa caminho para fora da pasta, e nada pela metade
ficando no lugar — falhou, apaga e tenta de novo na próxima abertura.

Para quem preferir provisionar antes, sem depender do primeiro uso:

```bash
bash scripts/vosk-model.sh          # baixa e envia para o aparelho por USB
bash scripts/vosk-model.sh --baixar # só baixa (sem aparelho)
```

No aparelho, o modelo fica em `filesDir/vosk-model-small-pt-0.3`. Reinstalar o app não
apaga `files/`, então o envio é uma vez só. O pacote de debug tem sufixo `.debug` — o
script usa `VOSK_PACOTE` para isso.

## O que NÃO está provado

**Nada disto rodou em aparelho.** Não há `adb`, emulador nem SDK de emulador nesta
máquina. O que a suíte prova é o estado e a política: que o motor offline é escolhido
quando existe, que o parcial e o final chegam na tela, que a falha dele devolve o
controle ao motor do sistema, e que o modelo pela metade não passa por instalado.

Fica por provar, em aparelho: se o `libvosk.so` carrega sob `useLegacyPackaging = true`
(a razão dessa flag é justamente o JNA achar a lib dele), o comportamento do endpointer
do Vosk com a fala real dela, e a precisão do `vosk-model-small-pt-0.3` comparada ao
motor do sistema.

## Pendências

- **O download do modelo não foi exercitado contra a rede de verdade.** O teste roda
  contra servidor local (MockWebServer) e prova allowlist, soma, extração e instalação;
  o que falta provar é o download real de 31 MB com a rede do aparelho, incluindo o
  que acontece se ela cair no meio.
- **Tamanho do APK**: as .so do Vosk somam ~17 MB nas duas ABIs de celular (mais ~19 MB
  em x86/x86_64, que só servem a emulador). APK debug: 32,4 MB. Vale decidir sobre
  ABI splits antes de distribuir.
- **WER não medido.** A escolha do Vosk small-pt foi por encaixe (31 MB, streaming
  nativo, Apache-2.0), não por precisão medida. Antes de virar o motor padrão, vale
  comparar com o motor do sistema em áudios reais dela.
