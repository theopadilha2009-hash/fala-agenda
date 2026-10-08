# Caçada — A VOZ DE SAÍDA: o que de fato chega ao `TextToSpeech` (2026-10-08)

Décima nona leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

A 18ª mediu o **texto** da resposta ao usuário no ViewModel. Ninguém tinha medido **o que chega ao
motor de voz**: a string exata do `speak()`, o `QUEUE_*`, o `utteranceId`, o idioma, o ciclo
`onInit`, e se o que é falado é pronunciável em pt-BR por uma idosa. A queixa literal e repetida
dela — **"o áudio nunca funciona"** — nunca foi auditada como superfície própria.

**Base:** `origin/main` @ `47f6500`. Cópia descartável em `/tmp/fala-caca-tts`, **motor real**
(`VozDoAparelho`) sobre um `ShadowTextToSpeech` **espião** escrito para esta leva — ele grava a
chamada exata do `speak()` (texto, `queueMode`, `utteranceId`) em vez de só aceitar a frase. Corpus
escrito **antes** de abrir o código. Parser real (`LocalTaskParser`) para o título de cada fala.

## A manchete: existe UMA superfície que fala, e ela é o lembrete

Grep exaustivo de `TextToSpeech`/`speak(`/`QUEUE_` em `app/src` e `domain/src`: **um único call
site de `speak()`**, `VozDoAparelho.kt:200`. Todo o resto do app é **entrada** (`SpeechRecognizer`,
Vosk). Isto fecha a pergunta do briefing e reduz o escopo:

| Superfície | Fala? | Onde |
|---|---|---|
| Recap / resumo da home | **não** | — |
| Confirmação de ação ("Feito.", "Tarefa excluída.") | **não** | só snackbar |
| Pergunta/resposta ("o que tenho hoje?") | **não** | só snackbar (já medido na 7ª leva) |
| Notificação de lembrete | **não** | som de alarme do canal, sem voz |
| **Alarme do lembrete** | **sim** | `LembreteFaladoService` → `VozDoAparelho.speak()` |
| Erro do motor de voz | **não** | só `Log.w` |

A voz existe em **1 de 1** lembretes (o app não tem tipo de tarefa) e **0** das outras superfícies.
O `#80` fez o lembrete falar e mais nada falar — a pergunta "o que ela ouve?" tem **uma** resposta,
e é a que este doc mede.

## P0 — o app se declara saudável com a voz muda; o cartão que existe para isso não sabe de voz

**Este é o defeito que responde à queixa dela.** O aparelho **não tem voz em pt-BR instalada** — o
motor existe, o `onInit` responde `SUCCESS`, e o `setLanguage` devolve `LANG_MISSING_DATA`. O
remédio das 08:00 vai tocar o plim e **não vai falar uma palavra**.

Medido, com o motor real (output cru colado ao lado):

```
=== APARELHO SEM VOZ EM pt-BR ===
voz.sabeFalar (quandoPronto) = false      ← a voz sabe que não vai falar
reminderAlerts               = OK         ← e o app se declara saudável
reminderAlertCard            = null       ← nenhum cartão aparece
remindersWillBeSilent        = false
---
O app diz que a voz nao sai? NAO

=== CONTROLE: CANAL MUDO (o mesmo instrumento, outro eixo) ===
reminderAlerts    = QUIET
reminderAlertCard = ReminderAlertCard(title=Os avisos estão sem som, ...)
```

**O controle prova que o instrumento não é cego**: o cartão aparece para o canal mudo. Ele só não
enxerga a voz. `reminderAlerts` (`NotificationHelper.kt:184-194`) lê permissão, importância do
canal, `sound`/`shouldVibrate` e o volume do alarme — **nada de voz**. E a pergunta "o aparelho sabe
falar?" já está respondida no `AvisoFalado` (`quandoPronto { sabeFalar -> ... }`, `AvisoFalado.kt:128`):
`false` **encerra o aviso em silêncio**. O app **sabe** e não conta.

**Cenário:** o celular dela não tem a voz em português do Brasil instalada (ou o motor de TTS está
desabilitado — comum em aparelho de entrada). O lembrete toca o plim, a notificação diz "Avisando em
voz alta" não aparece, e a home **não mostra cartão nenhum**. Ela reclama "o áudio nunca funciona" e
o aplicativo responde que está tudo bem. É a mesma família do "avisos OK com o canal mudo" que o
#81 fechou — agora no eixo da voz, o único que o app nunca olhou.

**Cobertura:** `VozDoAparelhoTest.semVozEmPortuguesOMotorNaoFicaPronto` prende o `quandoPronto(false)`
do motor; `ReminderAlertCardTest` prende os 4 vereditos do cartão. **Nenhum teste cruza os dois** —
é a combinação que falta, e ela é o defeito (lição de `o-defeito-mora-na-combinacao-de-elos`).

**Prova de invisibilidade:** MUT5 (neutralizar a checagem `falavel < LANG_AVAILABLE`,
`VozDoAparelho.kt:97`) → **`774, 0 falhas`**. A detecção de idioma do motor não é exercitada por
nenhum teste da suíte.

## P1 — a notificação afirma "Está na hora" para a tarefa que não é

O texto falado e o texto da notificação são **frases diferentes**, e a da notificação não diz de
que tarefa se trata:

| Superfície | String exata | Arquivo |
|---|---|---|
| **Falado** | `"Está na hora. Tomar remédio pressão."` | `strings.xml:54` (`reminder_spoken`) |
| **Notificação (corpo)** | `"Está na hora. Pode concluir ou adiar daqui, sem abrir o aplicativo."` | `NotificationHelper.kt:285` |

O corpo da notificação é uma **string fixa**. O título (`setContentTitle(title)`) traz o nome da
tarefa — então a notificação ainda diz qual é. O problema é o **falado**: `reminder_spoken` é
`"Está na hora. %1$s."`, e `%1$s` é o título **já mutilado pelo parser**.

Medido, com o parser real (corpus escrito antes do código):

```
FALA   : ir ao médico dia 07/10/2026
TITULO : «Ir médico dia»                    ← "ao" e a data sumiram; "dia" órfão ficou
SPEAK  : «Está na hora. Ir médico dia.»

FALA   : tomar o remédio do coração às 8h30
TITULO : «Tomar remédio coração»            ← "o" e "do" sumiram
SPEAK  : «Está na hora. Tomar remédio coração.»

FALA   : fazer 30 minutos de caminhada
TITULO : «Fazer 30 caminhada»               ← "minutos de" sumiram; "30" órfão
SPEAK  : «Está na hora. Fazer 30 caminhada.»
```

**Cenário:** ela ouve *"Está na hora. Ir médico dia."* — uma frase que não é português. Ela não sabe
se é hoje, se é o médico ou o dentista, e a única coisa que poderia desambiguar (a data) foi comida
pelo parser. O defeito do título já é conhecido (8ª leva, `cacada-fala-2026-10-07-titulo.md`); **o
que esta leva acrescenta é que o título mutilado não é lido — é FALADO**, e uma frase falada
quebrada custa mais que um rótulo feio na tela: ela não pode reler.

## P1 — a voz só fala se a notificação "saiu", mas a notificação pode sair muda

`deveFalarOlembrete(entrega)` (`AvisoFalado.kt:228`) condiciona a voz a
`entrega == POSTED`. `POSTED` significa "o `notify` não lançou" — **não** significa "ela ouviu".

Medido: com o **canal em `IMPORTANCE_LOW`** (o `QUIET` do cartão), `showReminder` devolve
`POSTED` e a voz é pedida normalmente. O veredito do app é `QUIET`, o cartão aparece — e a voz
fala. Ou seja: **o app avisa que o canal está sem som e fala em voz alta no mesmo estado**. Se ela
consertou o canal mas o aparelho continua sem voz, o caminho inverso também vale: `OK` + voz muda.

Isto não é bug de uma linha só — é o acoplamento "entrega ⇒ voz" tratando duas coisas independentes
(som do canal × voz do motor) como uma. A 11ª leva já registrou o resíduo disto como `PENDENTE`
(volume 0 × voz pedida, `cacada-fala-2026-10-07-cadeia-de-audio.md`); aqui ele é medido pelo lado
da **voz muda**, que é o estado que ela vive.

## P2 — o orçamento da fala ignora a velocidade e não tem margem

`duracaoFaladaMs(frase) = 80ms × frase.length` (`AvisoFalado.kt:61-63`) é o **teto** que decide
quando o aviso se encerra. Dois problemas medidos:

1. **O orçamento ignora `VELOCIDADE_DA_VOZ`.** A fala sai a `0.85f` (`VozDoAparelho.kt:110`), isto
   é, **17,6% mais devagar** que a velocidade nominal — e o orçamento é calculado para 100%. A
   constante que fala e a constante que conta o tempo não se conhecem.
2. **Não há margem no fim.** `AvisoFalado.kt:170` agenda `encerrar()` a exatamente
   `duracaoFaladaMs(frase)` depois de a última fala ser **pedida**, e `encerrar()` chama
   `voz.parar()` (`:197`). Falar mais devagar que o orçado ⇒ o `stop()` corta a segunda repetição no
   meio. O comentário de `FALAS_POR_AVISO` diz que a segunda fala existe porque *"uma vez só, com o
   celular na sala, não se ouve"* — a metade que conserta a queixa é justamente a que está em risco.

**Prova de invisibilidade:** MUT1 (`MS_POR_CARACTERE` 80 → 800, dez vezes o orçamento — a fala seria
cortada muito antes de acabar em qualquer frase real) → **`774 / 538, 0 falhas`**. O orçamento
inteiro está sem cobertura.

## P2 — o `QUEUE_*` não tem cobertura nenhuma

Medido: `tts.speak(texto, TextToSpeech.QUEUE_ADD, Bundle(), id)` — **`QUEUE_ADD`, um único call
site**. O KDoc da classe justifica a escolha ("o aviso substitui a fala anterior por fora… uma troca
no meio da frase não pode engolir a repetição que ainda vai sair").

A justificativa é verdadeira e o desfecho dela é o oposto do que ela descreve: o substituto por fora
é `voz.parar()` → `motor.stop()` (chamado no `abandonar()` do aviso anterior,
`LembreteFaladoService.kt:131`), então o `QUEUE_ADD` **nunca é usado para enfileirar** — a fila é
sempre esvaziada antes. Com `QUEUE_FLUSH` o desfecho seria idêntico, porque o `stop()` já limpou.
A decisão é defensável, mas **o comentário descreve uma função que o código não usa**.

**Prova de invisibilidade:** MUT2 (`QUEUE_ADD` → `QUEUE_FLUSH`) → **`774, 0 falhas`**. O modo da
fila não é afirmado por teste nenhum.

## P2 — a velocidade da voz é uma decisão de acessibilidade sem teste

`VELOCIDADE_DA_VOZ = 0.85f` (`VozDoAparelho.kt:22`) tem um KDoc explícito: *"quem ouve aqui é uma
pessoa idosa, e a frase mais importante do aplicativo não é lugar de pressa"*. Medido que
`setSpeechRate` **é chamado** com `0.85f` (o espião registra o idioma pedido `pt_BR`).

**Prova de invisibilidade:** MUT3 (remover a linha do `setSpeechRate`) → **`774, 0 falhas`**. A
decisão de acessibilidade central do eixo não tem um teste que a prenda.

## Medido e OK (não refazer)

- **`onInit` não é pré-requisito do primeiro `speak` — e não pode ser.** A hipótese do briefing
  (C1: "se ela pede algo antes do motor inicializar, o app fala ou engole calado?") foi **testada e
  refutada**. No harness o `quandoPronto` responde `true` **sem `onInit`** e o `speak()` sai — mas
  isso é artefato do `ShadowTextToSpeech`, cujo `isLanguageAvailable` responde pelo estado estático
  do teste. No aparelho, `setLanguage`/`isLanguageAvailable` devolvem `LANG_NOT_SUPPORTED` (−2)
  enquanto o serviço não está bound (conferido na fonte do `TextToSpeech` do AOSP:
  `runAction(..., LANG_NOT_SUPPORTED, "setLanguage")`, guarda `mServiceConnection == null`), e o
  `configurar()` devolve `false` → `AvisoFalado.falar` encerra calado. **O app nunca fala antes do
  motor subir.** Não é defeito; é o caminho fechado.
- **A string falada é montada com o título e o recurso é o certo.** `LembreteFaladoService.kt:150`
  usa `getString(R.string.reminder_spoken, titulo)`; o teste `falaAFraseDoLembreteComOTituloDaTarefa`
  compara com o recurso, não com string solta.
- **`shutdown()` é chamado.** Medido: `shutdown() chamado = 1` depois de `soltar()`. `stop()` é
  chamado por `encerrar()`/`abandonar()`. O caminho de não vazar o motor existe e tem teste
  (`destruidoNoMeioDaFraseSoltaOMotor`).
- **O `utteranceId` existe e é distinto.** Medido: `«fala-0»`, `«fala-1»` — contador monotônico em
  `AvisoFalado.repetir()`, com a guarda de id que separa a fala da vez do aviso atrasado. Coberto.
- **O canal da voz é mudo de propósito** e isso é decisão certa (a voz é o áudio).
- **O alarme não é um caminho de voz separado** — é o mesmo. A memória
  `alarme-nao-fala-so-o-plim-padrao` está **desatualizada** na parte "não fala": o `#80` fez o
  lembrete falar; o que continua verdade é que **nenhuma outra superfície fala**.
- **`isLanguageAvailable` E `setLanguage` são checados** (`VozDoAparelho.kt:85`, `:97`), com o
  segundo existindo para o caso "língua instalada, nenhuma voz dela". O motor é conservador e
  correto; o defeito não está nele — está em o app **não contar** o resultado para ela.
- **A escolha de voz evita a voz de rede** (`escolherVoz`, `:152-160`, `filterNot
  isNetworkConnectionRequired`) — o remédio não depende de internet. Cobertura: zero
  (`ShadowTextToSpeech.addVoice` não é usado em teste nenhum), mas o desfecho é conservador.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

Um build por vez (`--max-workers=2`), `--rerun-tasks`.

| suíte | testes | falhas |
|---|---|---|
| pristine `origin/main` (antes dos meus arquivos) | app **767** / domain **538** | 0 / 0 |
| pristine + os 2 arquivos de medição desta leva | app **775** / domain **538** | 0 / 0 |
| **MUT1** `MS_POR_CARACTERE` 80 → 800 | app **774** / domain **538** | **0 / 0 — invisível** |
| **MUT2** `QUEUE_ADD` → `QUEUE_FLUSH` | app **774** | **0 — invisível** |
| **MUT3** remover `setSpeechRate` | app **774** | **0 — invisível** |
| **MUT5** neutralizar `falavel < LANG_AVAILABLE` | app **774** | **0 — invisível** |
| **MUT4** (controle) `FALAS_POR_AVISO` 2 → 3 | app **774** | **3 — pega** |

**4 de 4 mutações de produção sobreviveram**; o controle mata 3 testes
(`falaAFraseDuasVezesEDepoisCalaSozinha`, `aUltimaFalaFechaAContaMesmoSemOAvisoDoMotor`,
`depoisDeFalarOServicoSaiSozinho`), então a suíte não é cegamente verde — ela é cega **neste eixo**.

Cada mutação foi restaurada por `cp` de um backup refeito a cada edição, com `sha256` conferido
(`VozDoAparelho.kt` = `4483a99b…`, `AvisoFalado.kt` = `afd6d833…`). `git status` limpo ao final
fora os dois arquivos de teste.

## Instrumento (descartável, não vai no commit)

Dois arquivos de medição vivem só no worktree `/tmp/fala-caca-tts`, como os `CadeiaDeAudio*Test` da
11ª leva — eles medem, não prendem:

- `app/src/test/java/.../reminders/CacaVozDeSaidaTest.kt` — o `ShadowTextToSpeech` **espião**
  (`ShadowEspiao`) que grava texto/`queueMode`/`utteranceId` de cada `speak()`, mais o corpus do
  parser. Roda com `--tests "*CacaVozDeSaidaTest*"`; o output sai no `system-out` do XML.
- `app/src/test/java/.../reminders/CacaVozDiagnosticoTest.kt` — o oráculo do P0 (o cruzamento
  voz × veredito × cartão), com o controle do canal mudo.

O `P0` merece virar teste de regressão de verdade quando o fix existir — hoje ele só pode afirmar
o **controle**, e um teste que passa sem afirmar o defeito é o teste vacuoso que este projeto já
aprendeu a temer.

## Não confirmado

- **A pronúncia real do motor.** O Robolectric não tem motor de voz: o `speak()` medido é a
  **string**, não a onda. Se o Google TTS pt-BR lê "8h30" como "oito e trinta" ou soletra, e se "kg"
  vira "quilos", **não foi medido** — precisaria de aparelho ou emulador com TTS. O que ficou
  medido é o que entra: `«Está na hora. Tomar remédio pressão.»` (sem horário, sem valor, sem
  data — o parser os removeu antes). Comando que falta: rodar em emulador com `pt-BR` instalado e
  capturar o áudio, ou `tts.synthesizeToFile` num teste instrumentado.
- **A voz cortada de fato (P2).** O mecanismo está lido e o orçamento medido, mas a taxa de fala
  real do motor não é observável aqui — a asserção de corte não foi executada.
- **A pontuação do título vazio.** Medido `«Está na hora. .»` para título `""`; não se mediu se o
  motor lê o ponto duplo como pausa ou engasga.
- **`HomeDrawerTextoTest.comOAjusteOMenuSegueDizendoQueOsAvisosEstaoLiberados` falhou uma vez** na
  suíte completa com `UncaughtExceptionsBeforeTest` (exceção de **outra** classe vazando antes do
  teste, o padrão `flake-de-corrotina-entre-classes`), e passou verde na repetição seguinte com o
  mesmo código. Não é deste eixo e não foi perseguido.
- **A superfície de diagnóstico não foi executada em tela.** O `reminderAlertCard` foi lido como
  função real (Robolectric), não montado na home.
