# Caçada — O CAMINHO DE VOLTA: o que acontece quando o alarme toca e ela responde (2026-10-07)

Sétima leva de caçadas. As anteriores mediram o app **agindo sobre ela** (o alarme sai, a voz
fala, a cadeia de áudio elo a elo). Nenhuma mediu a **resposta**: quando a notificação de remédio
aparece, o que ela pode fazer com ela, e o que o app registra.

Base: `main` @ `4a1c6e6`. Cópia descartável (`/tmp/fala-cacada-resposta`). Oráculo próprio em
`app/src/test/java/.../reminders/` (`CaminhoDeVoltaOracleTest`, `CaminhoDeVoltaTelaTest`),
Robolectric 4.14.1 sdk 34, Compose real.

**Re-verificado em `origin/main` @ `b51b092`** (o HEAD avançou durante a sessão): o caminho de
volta está **byte-idêntico** entre as duas bases — `git diff 4a1c6e6 b51b092` só toca
`HybridParser`, `NotasDoRascunho`, `SupabaseFunctions`, testes e docs, **nada** em
`reminders/`, `widget/`, `TaskRepository`, `FalaAgendaRoot`, `ui/home/`, `ReminderPolicy` ou
`OccurrenceLifecycle`. O oráculo roda verde nas duas: **10 testes, 0 violações**.

## Manchete

**10 estados medidos, 0 violações do caminho de volta.** O caminho que a queixa literal
("o áudio nunca funciona") sugere — a notificação aparece e ela não consegue responder — **está
fechado** nesta base: a notificação tem as duas ações, registra a dose em **1 toque**, insiste
**17 vezes** por dia e nunca marca como tomada a dose que ela não tomou.

O que sobra é **1 achado de UX** (a jornada pelo *corpo* da notificação, que abre o formulário de
edição em vez de registrar) e **3 limites que eu não medi** (listados no fim). O achado de UX é
real e tem `arquivo:linha`, mas **não é violação** do invariante "o app registra a dose em 1
toque": esse caminho existe e funciona.

## O oráculo e a contagem crua

O invariante é independente do código: *"ela registra a dose em N toques", "o alarme insiste mais
de uma vez no dia", "a dose nunca respondida NÃO fica marcada como tomada"*, "o adiamento de 30
min sobrevive ao reboot". Cada teste monta o cenário e conta violações.

```
### CaminhoDeVoltaOracleTest: tests=8 failures=0
### CaminhoDeVoltaTelaTest:     tests=2 failures=0

ORACULO_ACAO|titulo="Tomar Losartana"|texto="Está na hora. Pode concluir ou adiar daqui, sem abrir o aplicativo."|acoes=[Concluir, Adiar 30 min]|ongoing=true|categoria=alarm|swipe_apaga=false
ORACULO_ACAO|violacoes=0[]
ORACULO_JORNADA|via=botao_concluir_da_notificacao|toques=1|status=COMPLETED
ORACULO_JORNADA|via=corpo_da_notificacao|toques=2|status=COMPLETED|widgets_acima_do_concluir=20|exige_rolagem=true
ORACULO_JORNADA|via=icone_do_app_cartao_da_home|toques=2|status=COMPLETED
ORACULO_INSISTE|disparos=17|no_dia_da_dose=16|primeiro=08:00|ultimo=08:00|janela_min=1440
ORACULO_INSISTE|violacoes=0[]
ORACULO_IGNORA|status=MISSED|lastReminderAt=2026-10-08T11:00:00Z|motivo=NOT_DONE|snoozedUntil=null
ORACULO_IGNORA|violacoes=0[]
ORACULO_ADIAR|gravado=2026-10-07T10:30:00Z|alvo=2026-10-07T10:30:00Z|rearmado=2026-10-07T10:30:00Z|sobrevive=true
ORACULO_ADIAR|violacoes=0[]
ORACULO_WIDGET|titulo="Tomar Losartana"|quando="Hoje · 08:00"|late=false|clicaveis=[2131099738, 2131099739]
ORACULO_WIDGET|violacoes=0[]
ORACULO_TELA|raiz_altura=414.0|concluir_rect=Rect.fromLTRB(0.0, 0.0, 0.0, 0.0)|visivel_sem_rolar=false|cabecalho_top=24.0
ORACULO_TELA|antes_do_concluir=[O que precisa ser feito, Observação (opcional), Valor (opcional), Hoje, Amanhã, Depois, 8h, 12h, 18h, 20h, Só uma vez, Todo dia, Dias úteis, Toda semana, Todo mês, Todo ano, Salvar, Cancelar]|total=18
```

Suíte inteira (XMLs, nunca o "BUILD SUCCESSFUL"), com o oráculo incluído:

```
domain: tests=439 failures=0 errors=0
app:    tests=716 failures=0 errors=0   (706 da base + 10 do oráculo)
```

## O achado — jornada pelo corpo da notificação (não é violação; é atrito)

**`NotificationHelper.kt:251`** — `.setContentIntent(open)` faz o corpo da notificação abrir a
activity com `ACTION_OPEN_OCCURRENCE` (`AlarmIds.kt:38-45`). **`FalaAgendaRoot.kt:193-199`**
resolve o id com `agendaNotice` e chama **`openForEdit(pedido.item)`** (`FalaAgendaRoot.kt:170-188`),
que grava `editingItemId` e navega para `"confirm"`. A tela abre em modo edição
(`editing = editingItem != null`, `FalaAgendaRoot.kt:390-394`).

**Input:** ela toca o corpo do aviso do remédio das 08:00 (não o botão).
**O que o app faz:** abre "Editar tarefa" (`ConfirmDraftScreen.kt:163`) — o mesmo formulário de
criação, com título, observação, valor, data, horário e recorrência. Medido no Compose real:
o `Concluir` (`ConfirmDraftScreen.kt:425`) tem **rect 0×0 no viewport** — está abaixo da dobra,
com **18 rótulos interativos** antes dele, e exige rolagem.
**O que devia fazer (ou o que basta hoje):** o botão "Concluir" da própria notificação registra a
dose em **1 toque** e é o caminho compensatório — está lá e funciona. O toque no corpo, hoje,
não é o caminho de registrar; é o de editar. Não é "ação ausente": é a jornada mais longa que a
mais provável (tocar no aviso).

> Este item **não** é P0/P1 de correção: `cacada-fala-2026-10-06-novos.md` F5 já o registrou em
> base anterior (`c22b588`), e aqui ele é **re-medido na base atual** com a geometria do Compose.
> Fica como o único atrito do caminho de volta.

## O que está fechado (medido, não refazer)

- **A notificação tem as duas ações.** `NotificationHelper.kt:259-260` — `Concluir` e
  `Adiar 30 min`. Medido no `Notification.actions`: `[Concluir, Adiar 30 min]`.
- **A notificação diz o nome do remédio.** Título = o nome da série ("Tomar Losartana"),
  texto = "Está na hora. Pode concluir ou adiar daqui, sem abrir o aplicativo."
  (`NotificationHelper.kt:249-250`). Não é genérica.
- **Ela registra a dose em 1 toque, sem abrir o app.** Botão "Concluir" da notificação →
  `ReminderActionReceiver` → `TaskRepository.complete` → `COMPLETED`. Medido.
- **O alarme insiste.** `ReminderPolicy.intervalAfterStep` (`:42-46`): 0→+15min, 1→+30min,
  2+→+60min, até o fim do dia. Medido: **17 disparos**, **16 no dia da dose** (08:00→23:00), o
  último adiado pelo silêncio para 08:00 do dia seguinte. Não é um plim único.
- **A dose ignorada NÃO vira tomada.** Após a virada do dia, `OccurrenceLifecycle.advance`
  (`:138-150`) marca `MISSED`, com `lastReminderAt` preservado — a home diz "Não realizadas", não
  "Não consegui avisar" (`MissedSections.kt:25-26`). Medido: `status=MISSED motivo=NOT_DONE`.
- **O adiamento existe (30 min) e sobrevive ao reboot.** `TaskRepository.snooze` (`:551-570`)
  grava `nextReminderAt`/`snoozedUntil` no banco; `rescheduleAll` (`:694-729`) rearma a partir do
  banco no start. Medido com o registro em memória limpo entre o adiamento e o "reboot".
- **O widget mostra a próxima tarefa e o horário, e não tem ação de registrar.**
  `AgendaWidgetProvider.kt:113-114` — título + "Hoje · 08:00". Os dois alvos de toque são o corpo
  (abre o app) e "Falar" (`:175-185`). Não registra dose pelo widget — o que é correto: registrar
  remédio exige um alvo com confirmação, não um toque acidental no widget.

## Prova por mutação (uma por execução, restaurada por `cp`, sha conferido)

| # | Mutação | Arquivo | Resultado do oráculo |
|---|---------|---------|----------------------|
| 1 | Remover as duas `.addAction(...)` | `NotificationHelper.kt:259-260` | **FALHA**: `aNotificacaoTemAsDuasAcoesEDizONomeDoRemedio` → `[nao ha botao Concluir: registrar exige abrir o app, nao ha botao Adiar]` |
| 2 | `nextRepetition` devolve `ended` sempre | `ReminderPolicy.kt:80` | **FALHA**: `oAlarmeInsisteVariasVezesNoDia` → `[o alarme tocou uma vez e parou: nao insiste, a insistencia dura menos de 1h]` |
| 3 | `advance` marca a dose ignorada como `COMPLETED` | `OccurrenceLifecycle.kt:143` | **FALHA**: `aDoseNuncaRespondidaViraNaoRealizadaENaoTomada` → `[a dose nao tomada foi marcada como TOMADA, a dose nao ficou como nao realizada (COMPLETED)]` |
| 4 | `snapshotOf` devolve sempre o vazio | `AgendaWidgetProvider.kt:105` | **FALHA**: `oWidgetMostraAProximaEnaoTemAcaoDeRegistrar` → `[o widget nao mostra a proxima tarefa, o widget nao mostra o horario]` |
| 5 | `rescheduleAll` deixa de rearmar | `TaskRepository.kt:722` | **FALHA**: `oAdiamentoDeTrintaMinutosSobreviveAoReboot` → `[o adiamento nao sobreviveu ao reboot]` |

sha256 (antes = depois, restaurado por `cp` do backup em `/tmp/fala-backup/`, nunca `git checkout`):

```
140540b6bd6aa23f71fe102673d4ef838aaa5b999baa04e65201fdfafbe1bfb4  reminders/NotificationHelper.kt
6827fa9e14fa3999f8f5050e732487409bf28114d6d6f23bbaff28f741343ed6  domain/reminder/ReminderPolicy.kt
87746a77803ace9a516a3660d0cffd06f155745713fe37e74ec65a11ffa74861  domain/recurrence/OccurrenceLifecycle.kt
1c7cb18a4f3287da9a8908e3bfd0bf59a5d587082d99025837e7f6cc65705ed9  data/repo/TaskRepository.kt
839bb8463afdd932d1e1e431c34ba34c2dfe5fea4cb3889b6cbe682fbcc57843  widget/AgendaWidgetProvider.kt
```

`git status` no clone: só os dois arquivos de teste do oráculo aparecem como novos; nenhuma fonte
de produção mutada permaneceu.

## Não confirmado

- **O toque real num botão de notificação num aparelho.** O `PendingIntent` do botão é um
  `getBroadcast` para `ReminderActionReceiver` (`NotificationHelper.kt:394-411`); o receiver
  em si não roda no Robolectric (pede `FalaAgendaApplication`, Room e `AlarmManager` — infra
  inventada). O que se mediu foi a notificação (ações presentes) e o repositório
  (`complete`/`snooze` registram). A costura `Intent → receiver → repositório` não foi exercitada
  ponta a ponta; o teste que existe (`ReminderActionReceiverDecisoesTest`) cobre só as funções
  puras de decisão.
- **O corpo da notificação tocado de fato.** Medi que o `contentIntent` leva a
  `ACTION_OPEN_OCCURRENCE` e que o root chama `openForEdit` (leitura de fonte), e que a tela
  aberta em modo edição esconde o "Concluir" abaixo da dobra (Compose real). **Não** montei a
  `MainActivity` + `FalaAgendaRoot` + Room para ver o `LaunchedEffect` disparar a navegação — o
  caminho `intent → pendingOccurrenceId → agendaNotice.Open → openForEdit` é leitura de fonte.
- **A insistência contada no aparelho.** O número (17 disparos/dia) é o que o `ReminderPolicy`
  produz com o relógio avançado passo a passo; o `AlarmManager` real, o Doze e o silêncio do
  aparelho dela podem atrasar/suprimir disparos. O que se afirma é o **planejado**, não o
  **entregue**.
- **O widget tocado.** Medi os `PendingIntent` (corpo → app, "Falar" → `ACTION_SPEAK`) pela lista
  de ações do `RemoteViews`; não exercitei o launcher.
- **A voz.** `AvisoFalado.kt`/`LembreteFaladoService.kt` **não existem nesta base** (chegam com o
  PR #80, aberto). O caminho de volta medido aqui é o da notificação, não o da fala do alarme.

## Nota de método

O oráculo é independente do código: cada "esperado" é um fato do cenário, não uma segunda cópia
da regra. Onde o número de toques veio de leitura de fonte (as rotas de UI que o Robolectric não
dirige — `MainActivity.onNewIntent`, `HomeScreen` clicável), está marcado como tal; onde veio de
render real (a `ConfirmDraftScreen` no Compose, a geometria do "Concluir" abaixo da dobra), está
medido. As mutações provam que cada invariante **cai** quando o código quebra — inclusive o
`Concluir` abaixo da dobra (mutar a tela para escondê-lo quebraria `CaminhoDeVoltaTelaTest`).
