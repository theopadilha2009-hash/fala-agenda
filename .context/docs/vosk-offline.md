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
  o endpointer do Kaldi acha silêncio — mas isso é um **aviso**, não uma ordem: o
  recognizer segue decodificando, e quem fecha o recado é o `EndOfSpeechPause`.

  Até o PR #72 o aviso era aceito na hora, e aí sim o fim do recado era mais rápido que
  os 2800 ms de silêncio do motor do sistema — rápido demais. A regra que dispara primeiro
  (`--endpoint.rule2.min-trailing-silence`) fecha com 0,5 s de silêncio depois de uma
  palavra que parece final, e "tomar… remédio… de pressão" pausado entregava "tomar" como
  recado inteiro. Agora o recado só fecha quando o silêncio já dura `MINIMUM_PAUSE_MS`
  (2,5 s) contados do primeiro aviso, e o total depende de qual regra disparou (fonte em
  `kaldi/src/online2/online-endpoint.h:141-148`): ~3,0 s para fala confiante (rule2, 0,5 s),
  ~3,5 s para a hesitante (rule3, 1,0 s) e até ~4,5 s quando nada foi reconhecido como
  final (rule4, 2,0 s).

  Ou seja: **o offline agora é mais lento que o motor do sistema** para fechar o recado, e
  isso é a correção, não uma regressão. Os 2800 ms do `SystemSpeechSource` são o mesmo
  limiar de silêncio no meio da frase, aplicado pelo motor do aparelho — o Vosk não tinha
  equivalente, e o app não pode ser pior no motor que é o preferido.
- **`VoskModel`** + **`VoskOfflineSpeech`**: o modelo (53 MB) não vai no APK nem no git.
  O motor offline só existe quando o diretório está completo.
- **`VoiceEngine`**: `OFFLINE_VOSK` entrou na frente de todos, e há uma regra nova — se o
  offline falhar, o app volta para o motor do sistema, não pula direto para a tela do
  celular. O que é nosso não pode ser pior que o do aparelho.

## Como liga

O app baixa o modelo sozinho, em segundo plano, quando ela pede voz — o toque no
microfone (`OfflineModelInstaller.request`, chamado pelo `VoiceCaptureController.start`,
por onde passam o botão da home, o `ACTION_SPEAK`, o "Falar" do widget e o atalho do
lançador). Abrir o app não pede nada: era 31 MB baixados em toda abertura, calados, na
conta dela. Enquanto o modelo não chega, quem ouve é o motor do sistema — a fala funciona
desde o primeiro segundo, e a escuta seguinte já usa o offline. A consulta é feita a cada
escuta, não uma vez só, para o modelo que chega com o app aberto não ficar esperando o
próximo toque.

Regras do pedido: uma tentativa por pedido de voz (sem laço — quem decide insistir é ela,
ao pedir voz de novo) e uma de cada vez (dois toques seguidos não baixam 62 MB). O tipo de
rede não entra na conta: barrar a rede medida deixaria um celular só com dados móveis sem
a fala offline para sempre, e a fala é o motivo de o app existir. A falha vai para o log
com o motivo: o modelo que não chega tem que contar por quê, senão a fala fica no motor do
sistema sem ninguém saber que era para ser offline.

O download segue as mesmas regras do instalador de APK: host em allowlist
(`alphacephei.com`, a fonte oficial dos modelos do Vosk), sha256 fixo no código,
teto de tamanho, extração que recusa caminho para fora da pasta, e nada pela metade
ficando no lugar — falhou, apaga e tenta de novo no próximo pedido de voz.

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
do Vosk com a fala real dela — incluindo **qual** das regras do Kaldi dispara na fala
devagar e pausada, que é o que decide se a espera total é 3,0 s ou 4,5 s (ver o bloco do
`VoskSpeechSource` acima) — e a precisão do `vosk-model-small-pt-0.3` comparada ao
motor do sistema.

## Pendências

- **O download do modelo não foi exercitado contra a rede de verdade.** O teste roda
  contra servidor local (MockWebServer) e prova allowlist, soma, extração e instalação;
  o que falta provar é o download real de 31 MB com a rede do aparelho, incluindo o
  que acontece se ela cair no meio.
- **Tamanho do APK**: o `abiFilters` cortou x86, x86_64, mips, mips64 e armeabi — ~19 MiB
  de lib nativa que só existia para emulador ou para arquitetura morta (os 19 MiB são o
  tamanho em disco; no APK as `.so` vão comprimidas, a 36% do original). Ficaram
  arm64-v8a e armeabi-v7a, porque não se sabe qual é a do aparelho dela. APK debug:
  32,41 MB → 25,20 MB.
- **WER não medido.** A escolha do Vosk small-pt foi por encaixe (31 MB, streaming
  nativo, Apache-2.0), não por precisão medida. Antes de virar o motor padrão, vale
  comparar com o motor do sistema em áudios reais dela.
