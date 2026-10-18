# Caçada — O ADIAMENTO (snooze): a porta que ela mais usa e ninguém tinha medido (2026-10-08)

Décima nona leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

O adiamento já tinha aparecido como **causa** de um defeito em outra superfície (o widget, #107:
`snooze` grava `nextReminderAt` e não toca `scheduledAt` nem `series.localTime`), mas o eixo
**em si** nunca foi auditado: entrada, decisão e saída.

**Base:** `origin/main` @ `47f6500`. Cópia descartável (`/tmp/fala-caca-snooze`). **Corpus escrito
antes de abrir o código** (`CORPUS.md`, 40 casos de uso real de idosa). Medição com o **pipeline
real**: `TaskRepository` + `SpeechIntentClassifier`, `AgendaWidgetProvider.views` **aplicado**
(`remote.apply()`), a `HomeScreen` **composta** (Compose real), `AgendaFormat` real.
**7 mutações de produção**, contadas pelos XMLs.

---

## P0 — a FALA do adiamento não existe: "adia 10 minutos" vira a tarefa "Adia 10"

O README declara duas portas de adiamento (a notificação e o cartão). **A fala não é porta
nenhuma** — e é o gesto natural de quem fala em vez de tocar.

```
"adia 10 minutos"   → Capture → tarefa «Adia 10»   (com data e hora inventadas pelo parser)
"soneca"            → Capture → tarefa «Soneca»
"mais tarde"        → Capture → tarefa «Mais»
"depois"            → Capture → tarefa «»  (vazia)
"me lembra em 1 hora" → Capture → tarefa «»
"5 minutinhos"      → Capture → tarefa «5 minutinhos»
"adia o remédio"    → Unknown(CHANGE) → «Ainda não sei mudar uma tarefa falando…»
```

**Causa:** `SpeechIntent.kt:494` — `adia = Regex("\b(adia|remarca)\s+(pra|para|isso|isto|ele|ela|o|a|os|as)\b")`.
A lista exige artigo/preposição **depois do verbo**: "adia **10** minutos" (número) e "adia" nu não
casam. A âncora foi escrita para *não* roubar "adiar a reunião" (infinitivo) — o que é correto —,
mas o preço é que a forma que ela fala cai na captura.

**Prova de invisibilidade:** MUT-FALA (o classificador passa a reconhecer `adia <número>`) →
**domain 538 / 0 falhas — verde**. Nenhum dos 538 testes prende que "adia 10 minutos" não é uma
tarefa. `SpeechIntentTest.kt:305` só prende `"adiar a reunião"` (infinitivo) → `Capture`.

**Cenário:** o despertador toca, ela fala "adia 10 minutos". Nasce um lembrete "Adia 10" com data e
hora que ela nunca escolheu, e o remédio segue pendente.

---

## P1 — o cartão da lista mostra a hora MARCADA, não a do aviso

`HomeScreen.kt:1153`:

```kotlin
val time = AgendaFormat.time(item.series.localTime)
```

O `snooze` grava `nextReminderAt`/`snoozedUntil` e **não toca** `scheduledAt` nem
`series.localTime` — e o cartão lê o horário da **série**. Medido (Compose real, tela alta):

```
dose 00:01, ela adia 30 min
AVISO REAL = 06:01
CARTÃO     = «Tomar remédio, Hoje · 00:01 · há 5 h 29 min. Toque para editar.»
```

**É o mesmo defeito do widget** (`cacada-fala-2026-10-07-widget.md` P1-b), na tela de dentro — e a
caçada do widget não o viu porque mediu só a tela de fora.

**Prova de invisibilidade:** MUT-CARD (o cartão passa a ler o instante do aviso) → **app 767 / 0
falhas — verde**. O oráculo Compose (`SondaAdiamentoCartaoTest`) mata a mutação pela razão certa:
com o código atual o detalhe é `00:01`, com a mutação é `06:01`.

**Cenário:** ela adia para daqui a meia hora, olha a lista, lê "há 5 h 29 min", conclui que já
passou e toma duas vezes — ou não toma.

---

## P1 — a manchete do topo mostra a hora da SÉRIE (e a escolha do "próximo" também)

`HomeScreen.kt:958` (`nextTime = next?.series?.localTime`) e `:948`
(`minByOrNull { it.occurrence.scheduledAt }`):

```
MANCHETE = «Bom dia. Próximo: Tomar remédio, hoje às 08:00.»   (aviso real 08:30)
```

A manchete é a frase mais visível da tela — é a que ela lê sem rolar.

**Cobertura:** MUT-HEADLINE (a hora da manchete passa a ser a do aviso) → app 767 / **2 falhas**,
mas por **consequência lateral**: as duas falhas são `HomeHeadlineTest` cujo fixture monta
`nextReminderAt` de um instante diferente do `scheduledAt` (uma às 06:00 e outra às 21:00), não o
estado "ela adiou". **MUT-HEADLINE-SEL** (a *escolha* do próximo passa a ser pelo instante do aviso)
→ **767 / 0 — invisível**.

**Cenário:** com duas tarefas, o topo anuncia a que toca depois — a mesma classe do defeito que o
#107 fechou no widget, agora na home.

---

## P1 — com o adiamento atravessando a meia-noite, o cartão diz "Ontem · 22:00 · atrasada"

Dose de ontem às 22:00 adiada 3 h (aviso às 01:00 de hoje). Medido:

```
AVISO REAL = 2026-10-08T01:00 (ainda por tocar)
CARTÃO     = «Ontem · 22:00» · late=atrasada
MANCHETE   = «Boa noite. Atrasada: Tomar remédio, ontem às 22:00.»
WIDGET     = «Próxima | Hoje · 00:20»   ← o widget acerta
```

`AgendaFormat.kt:256` decide o rótulo por `lateMark(nextDate, today)` — a **data da ocorrência** —,
enquanto o widget decide pela hora do aviso. É literalmente o defeito que o #107 consertou do lado
de fora, sobrevivendo do lado de dentro: **duas contas para o mesmo fenômeno no mesmo repo**.

Aqui não é só a hora: o rótulo **"Atrasada"** afirma que passou uma coisa que ainda não tocou.

---

## P1 — o "Enviar o dia" manda a hora errada para a família

`HomeScreen.kt:458` (`time = it.series.localTime`) monta o texto do `todayShare`:

```
«Hoje no Fala Agenda: • Tomar remédio às 08:00»   (aviso real 08:30)
```

**Prova de invisibilidade:** MUT-SHARE → **app 767 / 0 falhas — verde**. `AgendaFormat.todayShare`
tem teste (`AgendaFormatTest`), mas nunca com o adiamento — o horário sai da série.

**Cenário:** a família recebe "às 08:00" e liga às 8h para lembrá-la de um remédio que o app só
vai avisar às 08:30.

---

## P2 — a resposta falada usa a mesma conta

`HomeViewModel.kt:894`: `"${item.series.title} às ${AgendaFormat.time(item.series.localTime)}."`
Mesma raiz dos P1 acima; entra aqui porque é a **saída falada**, e a caçada da resposta ao usuário
já tinha medido esta linha (M14: sem o horário, verde) sem o estado do adiamento.

---

## P2 — adiar dentro do silêncio toca no horário pedido, e o app não conta isso a ela

`ReminderPolicy.snooze(..., respectQuietHours = false)` (`ReminderPolicy.kt:119`): o adiamento é
ação explícita e **não** é deslocado pelo silêncio. Medido: às 22:10 ela adia 30 min → aviso
**22:40**, dentro da janela 22:00–08:00, e o alarme toca no meio da noite.

O README diz "das 22h às 8h as **repetições** pausam; o primeiro aviso no horário combinado ainda
toca" — e o adiamento foi classificado como "não é repetição". A decisão é defensável; o que falta
é **ela saber**: nenhuma superfície diz que o aviso vai tocar de madrugada. O recado pós-toque diz
só "Vai avisar hoje às 22:40.".

---

## Medido e OK (não refazer)

| Eixo | Medição | Veredito |
|---|---|---|
| **A notificação** | `NotificationHelper.kt:295` — `Adiar 30 min`; `Receivers.kt:192` — `snooze(id, 30)` fixo | porta viva, 30 min |
| **A tela de edição** | `ConfirmDraftScreen.kt:427-434` — chips `10 min / 30 min / 1 hora`, só quando `editing` e `PENDING` | porta viva, 3 números |
| **O que é gravado** | adiar 30 min às 08:00 → `nextReminderAt = 08:30`, `snoozedUntil = 08:30`, `step = 3` | **o aviso está certo**; o defeito é quem o mostra |
| **O widget** | `AgendaWidgetProvider.kt:161` — lê `nextReminderAt ?: scheduledAt` | OK (#107); adiar 30 → «Hoje · 08:30» |
| **A escolha no widget** | o adiado ganha da tarefa seguinte mesmo com `scheduledAt` menor | OK (#115) |
| **O recado do toque** | `HomeViewModel.kt:685` — anuncia `now + minutes` | OK, bate com o gravado |
| **Duas doses no mesmo dia** | adiar a das 08:00 não toca a das 20:00 | OK — é por ocorrência |
| **Adiar uma MISSED** | `GONE` → «Não deu para adiar esta tarefa.» | OK — nunca mente (MUT-GONE contido por 2 testes) |
| **Adiar não-pendente** | `TaskRepository.kt:559` recusa antes de agendar | OK |
| **Fim da escada** | `nextReminderAt` nulo + adiar → `PENDING`, aviso no horário pedido | OK — não trava |
| **Ocorrência nascida vencida** | `PENDING` com aviso vencido + adiar → agenda o pedido | OK |
| **Meia-noite na varredura** | dose de ontem adiada p/ 01:00, varredura às 00:05 → continua `PENDING` | OK (`temLembreteVivo`) |
| **O adiamento sobrevive ao reboot** | `rescheduleAll` rearma do banco | OK (já preso por teste) |
| **A notificação não mente** | título = nome da série; texto fixo sem hora | OK — não tem hora a errar |

## Não confirmado

- **Sem teto de adiamento:** medido 25 adiamentos seguidos → **25× `APPLIED`**, `step` sempre 3,
  sem aviso e sem limite. Não medi o efeito no `AlarmManager` real (Doze, bateria) nem se 25
  toques no botão numa madrugada é o comportamento desejado — a ausência de teto é **fato medido**;
  se é defeito é decisão de produto.
- **A fala como porta:** medi o classificador, não a fala ponta a ponta por voz. O caminho
  `understandSpeech` → captura → rascunho foi exercido com o texto real.
- **O cartão não tem adiar:** confirmado por leitura (`HomeScreen.kt:1150-1232` só monta
  "Concluir"; `onSnooze` só existe em `ConfirmDraftScreen`/`FalaAgendaRoot`). Não medi emulador.
- **A ordem do silêncio × adiamento** dentro do `fire` com o `AlarmManager` real (o disparo às
  22:40 depende de o alarme exato ser entregue).
- **TalkBack:** a descrição do cartão carrega a mesma hora errada; não foi executado por leitor.
- **A home com o adiamento de outra série** escolhendo o "próximo" errado — medido só pela mutação.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

Baseline (produção intacta, sondas fora da conta): **domain 538 / app 767, 0 falhas, 0 erros.**
Sondas: domain 1, app 27 (o `AdiamentoContratoTest` tem 7 falhas no baseline — é o oráculo, não a
suíte).

| Run | Mutação | domain | app | Falhas | Veredito |
|---|---|---|---|---|---|
| MUT-CARD | cartão lê `nextReminderAt ?: scheduledAt` | — | 767 | **0** | **invisível** |
| MUT-HEADLINE | manchete lê a hora do aviso | — | 767 | **2** | contida (lateral) |
| MUT-HEADLINE-SEL | escolha do "próximo" pelo aviso | — | 767 | **0** | **invisível** |
| MUT-SHARE | "Enviar o dia" lê a hora do aviso | — | 767 | **0** | **invisível** |
| MUT-GONE | `snooze` deixa de recusar não-pendente | — | 767 | **2** | contida |
| MUT-FALA | classificador reconhece `adia <número>` | 538 | — | **0** | **invisível** |

As duas falhas de MUT-HEADLINE são `HomeHeadlineTest`, e caem por consequência lateral (o fixture
monta `nextReminderAt` ≠ `scheduledAt`), não pelo eixo do adiamento. As duas de MUT-GONE são o
contrato do `GONE` — o **repositório** está coberto; a **tela** não.

**4 de 6 mutações invisíveis.** As três que consertam o cartão, a escolha da manchete e o share
passam inteiramente verdes na suíte de 1305 testes, e a que ensina o app a ouvir "adia 10 minutos"
também. É a medida da cegueira do eixo: o adiamento foi medido como **estado** (o widget), nunca
como **porta**.

Restauração: backup em `/tmp/fala-backup-snooze/` refeito antes de cada mutação, restaurado por
`cp` com `sha256` conferido; `git status -- app/src/main domain/src/main` vazio ao fim.

```
c655ac7de8c57836a771cd5b33ecd7a38e0dc4e83d789dc50002e727ff81aca3  HomeScreen.kt
1c7cb18a4f3287da9a8908e3bfd0bf59a5d587082d99025837e7f6cc65705ed9  TaskRepository.kt
103318e879128e71e068c7b3a33c1e28da877da7ba2af657f5ed6eb6af96a336  SpeechIntent.kt
```

## Nota de método

O corpus foi escrito antes do código, a partir do README e do uso de uma idosa, e por isso pegou o
P0: a **fala** como porta não aparece em nenhuma estrutura do código — ela só existe na intuição de
quem tenta falar. A sonda do cartão quase replicou a linha do código (o oráculo do `HomeScreen` não
é alcançável por função pura); só o **Compose real** com tela alta expôs `00:01` contra o aviso em
`06:01`. Fica registrado para a próxima leva: superfície de UI se mede compondo, não chamando a
função que ela usa.
