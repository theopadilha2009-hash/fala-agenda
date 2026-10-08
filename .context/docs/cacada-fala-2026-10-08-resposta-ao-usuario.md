# Caçada — A RESPOSTA AO USUÁRIO: o que o app devolve quando não entende, não acha, não pode, ou age errado (2026-10-08)

Décima oitava leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

As dezesseis caçadas anteriores mediram a **entrada** — o que ela diz e o que o app grava.
Esta mede a **saída**: o **texto de resposta**, a única coisa que a usuária lê de volta. Não o
parser, não o banco: a frase. Se o app responde errado, ela **acredita** e não confere — e uma
resposta que confirma o que não aconteceu é pior que o silêncio.

**Base:** `origin/main` @ `c3b6bd0`. Cópia descartável (`/tmp/caca-resposta`), **pipeline
real**: `SpeechIntentClassifier` → `HybridParser`/`LocalTaskParser` → `HomeViewModel` + Room
(Robolectric), `AgendaFormat` real, `AgendaWidgetProvider.snapshotOf`/`views` real,
`NotificationHelper.reminderAlerts` real, e os textos de cartão lidos das funções reais
(`reminderAlertCard`, `alarmHealthCard`, `missedSections`). **~60 estados reais** (23 falas
coloquiais de idoso, 10 comandos, 8 perguntas, 7 parses de nota, 4 snapshots de widget, 4
alertas de canal, 3 cartões de saúde) mais **14 mutações de produção** (7 delas controles).

## P0 — "Não tem nada marcado para hoje" com o remédio de hoje na mesma tela

A caçada do recado já tinha flagrado esta linha como P1. Aqui ela é medida pelo **eixo da
resposta** e sobe a P0: a MESMA tela que mostra a seção "Não consegui avisar · Tomar remédio"
responde que não há nada.

**Estado:** uma ocorrência de **hoje** em `MISSED` com `lastReminderAt == null` (o aviso nunca
saiu), nenhuma pendente. Ela pergunta "o que tenho hoje?".

```
HOME(manchete)= Boa tarde. Nada marcado agora. Não consegui avisar 1 tarefa.
HOME(seções)=   [Não consegui avisar:[Tomar remédio]]
DIZ(resposta)=  «Não tem nada marcado para hoje.»
```

**Causa:** `HomeViewModel.kt:887-892` — `AskWhen.TODAY -> today to sections.today`;
`sections.missed` não entra na conta e `items.isEmpty()` dispara a negativa. O irmão do lado da
manchete (`AgendaFormat.headline`) conta as duas coisas de propósito (`missedCount -
naoAvisados` + `naoAvisados`); a resposta falada conta uma só.

**Cenário:** ela não tomou o remédio porque o celular estava mudo. À tarde pergunta "o que tenho
hoje?". O app diz "Não tem nada marcado para hoje." Ela conclui que está em dia — e a dose não
tomada some da cabeça dela.

**Prova de invisibilidade:** M2 (somar `sections.missed`) → **verde** (`527 / 761, 0 falhas`).
**Controle negativo do mesmo harness:** M1 (a linha do vazio) mata **3 testes**; M8
(`"Feito."`) mata **8**. A suíte enxerga esta superfície — só não enxerga o estado "tem missed
de hoje".

**Cobertura:** `FalaComandoTest.kt:98` e `:119` usam agenda **vazia** (sem `missed`); o estado
"tem missed de hoje" não é exercido em nenhum teste da resposta falada.

## P0 — "Não achei nenhuma tarefa com esse nome" para uma tarefa que EXISTE

Ela concluiu o remédio de amanhã ("já tomei o remédio" → "Feito."). Mais tarde, com a mesma
tarefa ainda na lista (marcada como feita), fala de novo.

```
1a vez  "já tomei o remédio"  → «Feito.»                              (conclui)
2a vez  "já tomei o remédio"  → «Não achei nenhuma tarefa com esse nome.»
        "cancela o remédio"   → «Não achei nenhuma tarefa com esse nome.»
tarefa concluída, então:
        "cancela a consulta"  → «Não achei nenhuma tarefa com esse nome.»
```

A tarefa existe, está na tela sob "Concluídas", com o nome exato que ela falou. A resposta diz
que o app **procurou por esse nome e não achou**. É falso: o app achou e **descartou**, porque
`lookupTarget` só considera alvo "o que ainda está de pé" (`HomeViewModel.kt:920`, `:945-946`).

**Causa:** `HomeViewModel.kt:909-910` — o desfecho `SpeechTargetResolution.None` usa a mesma
frase para "não existe" e para "existe e já foi feita".

**Cenário:** ela quer apagar a consulta e não lembra se já marcou. Fala "cancela a consulta". O
app diz "Não achei nenhuma tarefa com esse nome." Ela conclui que nunca cadastrou — e cadastra
de novo, ou fica procurando na lista o que não acha.

**Prova de invisibilidade:** M3 (frase que distingue a concluída: "Não achei nenhuma tarefa
ABERTA com esse nome; ela pode já estar concluída.") → **verde**. A caçada do recado já tinha
listado este caso em "Não confirmado" (K5); aqui ele é medido e continua sem teste.

## P1 — o widget diz "Nada marcado" enquanto a home diz "Não consegui avisar"

O widget de hoje (`AgendaWidgetProvider.snapshotOf`) filtra só `sections.today + upcoming` **com
status PENDING** (`:94-95`); a ocorrência `MISSED` de hoje não entra. A home, para o mesmo
estado, abre a seção "Não consegui avisar".

```
widget = Snapshot(title=Nada marcado, whenLabel=Toque para abrir a agenda, empty=true)
home   = Boa tarde. Nada marcado agora. Não consegui avisar 1 tarefa.
         seções = [Não consegui avisar:[Tomar remédio]]
```

**Cenário:** ela olha a tela de fora, vê "Nada marcado", e não abre o app — o remédio que o app
não conseguiu avisar fica invisível até ela abrir. O widget é justamente a superfície de quem
**não** abre.

**Prova:** M5 e M6 (os dois rótulos de vazio do widget) matam **1 teste cada** — os rótulos são
medidos; a **interação com a ocorrência missed**, não. (A caçada do widget registrou o eixo
"vazio" como OK; o que falta aqui é a missed-de-hoje.)

## P1 — o texto da notificação do alarme não está preso por teste

O corpo da notificação do lembrete — a frase que ela lê no cartão que toca
(`NotificationHelper.kt:285`) — não tem teste.

**Mutação M7:** `setContentText("Está na hora. Pode concluir ou adiar daqui, sem abrir o
aplicativo.")` → `"erro"` → **verde** (`527 / 761, 0 falhas`).

A frase existe e é boa (diz o que fazer: concluir ou adiar). O problema é a **cegueira**: o mesmo
arquivo tem os textos de "não deu para fazer isso" presos por
`PrecisaAvisarDeAcaoNaoAplicadaTest`, mas a frase do aviso que **saiu** não é medida por
ninguém. Se ela regredir, ninguém acusa.

## P1 — "Não consegui ler a sua agenda agora." e a linha por item não estão presas

Duas respostas do caminho de falha/lista não têm teste:

**M12:** as duas saídas de leitura falha (`HomeViewModel.kt:884` no `answerFor`, `:942` no
`lookupTarget`) trocadas por `"erro"` → **verde**. A distinção "não consegui ler" vs "não tem
nada" é o ponto central da resposta — e não é medida na resposta, só na manchete
(`HomeHeadlineTest`).

**M14:** a linha por item da resposta (`HomeViewModel.kt:893-895`, `"${item.series.title} às
${time}."`) sem o horário → **verde**. O horário dito é o que torna a resposta útil; some sem a
suíte acusar.

## P2 — "Não entendi qual é a tarefa" quando o app ENTENDEU e recusou

**Fala:** `"cancela o médico, espera, o dentista"` (o "espera" é conector de correção fora da
lista `CONTENT_CUE`).

```
DIZ = «Não entendi qual é a tarefa. Diga só o nome dela, ou use os botões da lista.»
```

A frase é acionável (oferece o caminho do teclado), mas **mente sobre a causa**: o app
classificou a fala como correção e recusou agir — ele entendeu que havia uma correção. Dizer
"Não entendi" põe a culpa na fala dela. O mesmo vale para "cancela o médico, calma, o dentista".

**Prova:** M4 (frase CORRECTION → `"erro"`) → **verde**. Zero cobertura do texto.

## P2 — "cancela" nu devolve "Não achei nenhuma tarefa com esse nome"

**Fala:** `"cancela"` (sem alvo).

```
DIZ = «Não achei nenhuma tarefa com esse nome.»
```

Não houve nome nenhum. A frase afirma uma busca por nome que não existiu. A decisão de **não
agir** é certa (nunca escolher no chute); a frase é que descreve um mundo que não é o dela. O
mesmo para `"desmarca"` e `"já tomei"` nus.

## P2 — a pergunta que vira tarefa e a resposta que não a desfaz

A camada de intenção só reconhece perguntas com "que/qual/tem" + "hoje/amanhã". O resto cai em
captura, e a "resposta" que ela vê é a tela de confirmação com um título-lixo:

```
"que horas são"    → Capture → CAPTURA titulo=«São»        (faltando data e hora)
"cadê a lista"     → Capture → CAPTURA titulo=«Cadê lista»
"qual é a próxima" → Capture → CAPTURA titulo=«Qual é próxima»
"oi" / "bom dia" / "sim" / "obrigada" / "aí" / "não" / "tá bom" → Capture, título = a própria fala
```

A tela mostra "Você disse: 'que horas são'" e "Pode salvar? · São". Nada explica que a fala não
era uma tarefa.

**Cenário:** ela pergunta as horas, o app oferece salvar uma tarefa "São", ela toca no botão
verde (o que sempre faz) e nasce um lembrete-lixo.

## P2 — o anúncio pós-salvar não diz QUAL tarefa foi salva

```
titulo salvo = «Pagar a conta de luz»
ANÚNCIO      = «Vai avisar amanhã às 08:00.»
```

A frase diz quando, não o quê. Com duas tarefas no mesmo horário, ela não distingue. A caixa
rápida mostra o título acima da promessa (`QuickConfirmDialog.kt:89`), mas o anúncio que fica
depois de salvar, não.

## Medido e OK (não refazer)

- **Mensagens de sucesso presas.** M8 (`"Feito."`) mata **8 testes**; M9 (`SEM_AVISO`) mata
  **2**; M10 (CHANGE/ERASE) mata **4**; M11 (alvo ambíguo) mata **2**. São as respostas mais
  cobertas da suíte.
- **A resposta do vazio de agenda** ("Não tem nada marcado para $label.") mata **3** (M1).
- **Os rótulos do widget** — "Nada marcado" (M5) e "Não consegui ler a agenda" (M6) — matam **1
  cada**; a leitura que falha não vira widget em branco.
- **Alvo ambíguo nunca age:** "Tem mais de uma tarefa com esse nome…" e as duas tarefas ficam
  (FalaComandoTest).
- **Cartões de aviso são acionáveis:** `reminderAlertCard(OFF/QUIET/APARELHO_MUDO)` e
  `alarmHealthCard(bateria/exato)` dizem o que fazer e têm botão próprio; medidos.
- **A manchete nunca diz "Nada marcado" com a leitura falhando** (`HomeHeadlineTest`,
  `AgendaFormatTest`).
- **O anúncio pós-salvar usa a data da ocorrência GRAVADA** (`HomeDraftSaveOutcomeTest`).
- **O desfazer anda dentro do recado** (`StatusMessage.Undo`), não no "último tocado".
- **A fala não entendida oferece a porta do teclado:** "Não consegui entender o recado. Tente de
  novo ou escreva a tarefa." — mas o **texto** não é preso (M13 → verde).
- **Alarme inexato:** o anúncio diz "Vai avisar" mesmo sem alarme exato, mas o snackbar "O aviso
  pode atrasar alguns minutos. Dá para deixar no horário certo." sai junto
  (`AVISO_INEXATO_SNACKBAR`, `HomeScreen.kt:120`). Cobertura: `AvisoInexatoTextoTest`.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| Run | Mutação | domain | app | Falhas |
|---|---|---|---|---|
| Baseline | — | 527 | 761 | 1 (Compose, pré-existente¹) |
| **M1** | `answerFor` vazio → `"erro"` (controle) | 527 | 761 | app **3** |
| **M2** | `answerFor` TODAY `+ sections.missed` | 527 | 761 | **0 — verde** |
| **M3** | `None` distingue alvo concluído | 527 | 761 | **0 — verde** |
| **M4** | CORRECTION → `"erro"` | 527 | 761 | **0 — verde** |
| **M5** | widget `"Nada marcado"` → `"erro"` (controle) | 527 | 761 | app **1** |
| **M6** | widget `"Não consegui ler a agenda"` → `"erro"` (controle) | 527 | 761 | app **1** |
| **M7** | notificação `setContentText` → `"erro"` | 527 | 761 | **0 — verde** |
| **M8** | `"Feito."` → `"erro"` (controle) | 527 | 761 | app **8** |
| **M9** | `SEM_AVISO` → `"erro"` (controle) | 527 | 761 | app **2** |
| **M10** | CHANGE+ERASE → `"erro"` (controle) | 527 | 761 | app **4** |
| **M11** | alvo ambíguo → `"erro"` (controle) | 527 | 761 | app **2** |
| **M12** | "Não consegui ler a sua agenda" (2×) → `"erro"` | 527 | 761 | **0 — verde** |
| **M13** | `UNDERSTAND_FAILED_MESSAGE` → `"erro"` | 527 | 761 | **0 — verde** |
| **M14** | linha da resposta sem o horário | 527 | 761 | **0 — verde** |

¹ `RecadoCortadoTest.depoisDeEscreverUmaFalaCortadaVoltaAMostrarOAviso` falha no baseline
(pré-existente, Compose). As classes Compose (`RecadoCortadoTest`, `HomeAlarmHealthCardTest`)
são intermitentes no run completo: passam isoladas. M7 foi reexecutada no app sozinho e deu
**verde**; a falha que apareceu na primeira passada era a mesma intermitência.

Sete mutações são **controles** (matam ≥1): M1, M5, M6, M8, M9, M10, M11. O harness que dá
verde em M2/M3/M4/M7/M12/M13/M14 mata mutações reais nos eixos vizinhos — a suíte não é
cegamente verde. As **sete mutações invisíveis** são exatamente as respostas que a suíte não
prende.

Restauração: `cp` com backup refeito a cada mutação, `sha256` conferido e `git diff` vazio ao
fim. Hash de `HomeViewModel.kt` restaurado = `68126c35…203317`; `AgendaFormat.kt` =
`2ecaad05…22051a`.

## Não confirmado

- **TalkBack** como superfície separada (`contentDescription`): os textos existem
  (`PickerRow`, `MissedSection.note`), mas não foram executados por leitor de tela.
- **A tela real não foi executada**: os textos saem do `HomeViewModel` + Room (Robolectric) e
  das funções puras (`reminderAlertCard`, `alarmHealthCard`, `AgendaFormat`), não de um
  emulador. A `QuickConfirmDialog` e a `ConfirmDraftScreen` foram lidas, não compostas — os
  textos fixos ("Complete os campos em vermelho.", "O valor precisa ser um número…",
  `TRUNCATED_NOTICE`) foram lidos do código.
- **A notificação como objeto**: mediu-se o texto via mutação (M7), não o `Notification`
  postado e relido; `NotificationHelperTest` roda com `ContextWrapper` que devolve texto fixo.
- **A IA remota** não foi chamada (o eixo é local); as notas da IA (`IA_DATA_ILEGIVEL`) não
  foram exercidas ponta a ponta.
- **Fonte grande no texto da resposta**: não se mediu se o texto do snackbar/diálogo é cortado —
  a caçada da fonte grande mediu geometria de botões, não o texto da resposta. Fica como eixo
  aberto.
- **A contradição do widget** foi medida só pelo `snapshotOf` com seções construídas à mão (sem
  launcher).
