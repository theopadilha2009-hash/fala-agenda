# Caçada — A TELA DO MÊS: o resumo que ela lê para saber se tomou o remédio (2026-10-08)

Vigésima primeira leva. As anteriores estão listadas em `cacada-fala-2026-10-07-intencao-e-comandos.md`.

Vinte levas mediram a entrada (a fala), o armazenamento e as saídas de uma linha só — o recado, a
manchete, o widget, a notificação. **A tela do mês nunca foi auditada.** Ela é a superfície de "o
que aconteceu": o fechamento que a idosa abre para conferir se tomou o remédio. O `auditoria-2026-09-27.md`
só tinha tocado nela duas vezes, e nenhuma vez compondo a tela.

**Base:** `origin/main` @ `b78f8cf`. Cópia descartável (`/tmp/fala-caca-mes`). Corpus escrito
**antes de abrir o código** (`CORPUS.md`, 7 eixos de pergunta real de idosa sobre o mês passado).
Medição com o **pipeline real**: `TaskRepository` + Room real (in-memory), a `MonthSummaryScreen`
**composta** (Compose real, Robolectric), `MonthInsights` real, `HomeScreen` composta para o recap.
**12 mutações de produção**, contadas pelos XMLs.

---

## P1 — F1: os dias em que o app ficou fechado NÃO viram "não realizadas" — o mês apaga as faltas

Ela cria "Tomar remédio" todo dia e não abre o app por 9 dias. O resumo do mês devia dizer que ela
faltou (ou que o app não avisou) **9 vezes**. Diz **1**:

```
=== S11: remedio diario criado em 01/08 05:00, app fechado 9 dias ===
  apos criar: completed=0 missed=0 today=1 upcoming=0
  depois da varredura unica em 10/08:
    missed=1  dias=[1]
    RESPOSTA DA TELA DO MES: [Não consegui avisar 1 tarefa]
    (ela NAO tomou do dia 1 ao 9 = 9 faltas; o app so conhece 1)
```

**Causa:** a materialização não faz catch-up. `OccurrenceLifecycle.advance`
(`OccurrenceLifecycle.kt:124`) só cria a **data devida de hoje** (`RecurrenceEngine.firstOnOrAfter`),
e `spawnUpcomingPreview` (`TaskRepository.kt:887-899`) só cria **3 datas à frente**. Nenhum caminho
cria os dias **entre** a última abertura e hoje: esses dias nunca existiram como ocorrência, nunca
viraram `MISSED`, e o mês os ignora. É o P2 do `auditoria-2026-09-27.md:122` ("o horizonte é de 3
prévias; celular desligado 5 dias → 2 dias nunca existiram"), agora **medido na superfície onde o
efeito aparece**: o resumo de fechamento do mês.

**Para ela:** ela abre o "Resumo de agosto" para saber se tomou o remédio, lê uma contagem menor que
a verdade, e conclui que tomou mais do que tomou. É a superfície de confiança mentindo para baixo.

## P1 — F4: a separação "falha do app × falta dela" colapsa no caso mais comum

O #44 separou as não realizadas em duas linhas: "Não realizadas" (o aviso tocou, ela não fez) e
"Não consegui avisar" (o app não avisou). A separação depende de `lastReminderAt`. Mas **toda**
ocorrência que o app **não** avisou por não estar aberto nasce `MISSED` com `lastReminderAt` nulo, e
o mesmo vale para a tarefa criada para um horário já passado (`bornWithoutReminder`). Medido:

```
=== S8: mes com uma nascida-vencida e uma pendente ===
  saveDraft status=MISSED lastReminderAt=null
  completed=0 missed=2 naoRealizadas=0 naoAvisadas=2
  LINHAS: [Não consegui avisar 2 tarefas]
```

```
=== S13: ordem das faltas ===
  TELA DO MES: [Não consegui avisar 2 tarefas]
  HOME       : [Não consegui avisar]
```

Ou seja: no mês de uma rotina diária em que ela faltou de verdade (o app avisou e ela não fez), o
resumo diz **"Não consegui avisar N tarefas"** — o app assume a falta que foi dela —, e a linha
"N não realizadas" que o #44 criou **nunca aparece**. É o inverso do defeito que o #44 consertou:
antes o app cobrava dela a falta que era do app; agora, quando o app não avisou, ele **não** cobra
dela — mas também não conta a falta dela quando o aviso saiu, porque `lastReminderAt` só é gravado
no `fire` de verdade (`TaskRepository.kt:661`), que não roda em Robolectric nem na varredura. Não
medi em aparelho se o `fire` real grava o campo; o que medi é que a distinção depende de um campo
que **só existe depois de um aviso entregue**, e o resumo de fechamento é justamente onde o aviso
quase nunca foi entregue. **Não provei em aparelho** — declarado como hipótese.

## P2 — F2: o cartão da home promete um mês e o toque abre outro

No início do mês a home mostra o fechamento do mês anterior. O cartão é o caminho de entrada:

```
HomeScreen.kt:702-706  ->  dia <= 3 mostra o mês ANTERIOR ("Resumo de Julho de 2026")
FalaAgendaRoot.kt:250  ->  onOpenMonth = { nav.navigate("month") }        (sem argumento)
MonthSummaryScreen.kt:53 -> initialMonth: YearMonth = YearMonth.now()      (mês ATUAL)
```

Medido (S15): a rota abre em "Outubro de 2026" enquanto o cartão, no dia 1, promete julho/agosto:

```
=== S15: a rota 'month' abre em qual mes? ===
  LocalDate.now() do teste = 2026-10-08
  rotulo da tela = 'Outubro de 2026'
  cartao da home no dia 01/08 promete = '2026-07'
```

Ela toca "Resumo de Julho", cai no "Resumo do mês" de agosto, e tem que achar as setas de mês — a
tela abre **dois meses à frente** do que ela pediu. Além disso o cartão diz "Resumo de Julho de
2026" e a tela diz "Resumo do mês" (genérico): dois nomes para a mesma coisa.

**Prova de invisibilidade:** MUT-1 (a tela passa a abrir no mês passado por padrão) →
**app 798 / 0 falhas da suíte** (só a `FonteGrandeGeometriaTest`, pré-existente). A sonda S15 mata
a mutação pela razão certa (`expected: Outubro de 2026 / but was: Setembro de 2026`).

**Cegueira de teste (achado, não limitação):** a **janela do recap** (`HomeScreen.kt:702-706`) é
função do **dia real** e **não é medível por composição** nesta suíte. MUT-2 (dias 1–3 passam a
mostrar o mês corrente) fica **verde em 798/0**. Tentei forçar a data e não há caminho:
`android.os.SystemClock.setCurrentTimeMillis` **não** move `LocalDate.now()` sob Robolectric 4.14.1
(medido: `LocalDate.now()` continuou `2026-10-08`). O eixo do recap da home **não tem relógio
injetável na tela** — a `HomeScreen` lê `LocalDate.now()` direto (`:701`), fora do `AppClock` do
`AppContainer`. Isso não é falta de esforço da sonda: é um ponto cego estrutural do código, e a
janela em que o cartão aparece pode mudar de comportamento sem nenhum teste acusar.

## P2 — F3: "O que mais você fez" corta em 8 e não conta que cortou

`MonthInsights.kt:80` (`.take(8)`). Medido com 9 títulos distintos concluídos:

```
=== S12: 9 titulos distintos concluidos ===
  completed=9
  frequent (8): [Banco, Cabelo, Dentista, Farmacia, Feira, Fisio, Igreja, Medico]
  titulos que a TELA mostra: [Cabelo, Farmacia, Medico, Dentista, Feira, Igreja, Fisio, Banco]
```

"Mercado" foi feito e **some** do resumo, sem "e mais 1". Para uma pessoa que pergunta "quantas
vezes eu fui no cabelo?", a lista parece completa. **Cobertura:** MUT-3 (`take(8)` → `take(4)`) é
pega só pela **minha** sonda (app 1 falha); a suíte de 538+798 não prende o teto.

## P2 — F5: "1 feitas" — a manchete não concorda

`MonthSummaryScreen.kt:142` monta `"${insight.completed} feitas"` sem singular. Medido (S14):

```
=== S14: 1 concluida ===
  manchete: '1 feitas'
```

`monthRecapLine` (`:252`) tem o mesmo "N feitas". O projeto trata vocabulário com cuidado (o
`emptyFrequentMessage` distingue "vez" de "vezes" em `:176`); a manchete não. Não é dano funcional,
é a frase mais lida da tela soando errada.

## P2 — F6: editar o valor de uma dose reescreve a soma de um mês FECHADO

`InsightRow.amountCents` vem da **série** (`MonthSummaryScreen.kt:222`), não da ocorrência
(`Models.kt:164`). Medido: uma consulta de **julho** com valor R$ 80; em **agosto** ela corrige o
valor para R$ 50 pela tela de edição:

```
=== S5: soma de julho antes/depois de editar o valor em agosto ===
  ANTES:  R$ 80,00  (concluidas=1)
  DEPOIS: R$ 50,00  (concluidas=1)
```

O fechamento de julho, já lido e fechado, muda sozinho. É o P2 do `auditoria-2026-09-27.md:126`
(`amountCents` mora na série), **medido ponta a ponta** pelo `editOccurrence` real.

## P2 — F7: o resumo aparece com dois nomes diferentes

O cartão da home diz **"Resumo de Julho de 2026"** (`HomeScreen.kt:716`) e a tela que ele abre diz
**"Resumo do mês"** (`MonthSummaryScreen.kt:67`), sem o mês no título — o mês só aparece no rótulo
central do corpo (`:98`, "Julho de 2026"). Duas frases para a mesma superfície, como o #46 já
consertou no jargão do alarme.

---

## Medido e OK (não refazer)

| Eixo | Medição | Veredito |
|---|---|---|
| **"O que mais você fez" só conta concluída** | MUT-11 (contar PENDING/MISSED) derruba 2 testes do domain | **preso** |
| **A soma só conta concluída** | MUT-6 (somar não-concluídas) derruba `gastoSoSomaOQueFoiConcluido` | **preso** |
| **A linha leva o aviso da ocorrência** | MUT-4 (`naoAvisada=false`) derruba 5 testes | **preso** |
| **"Não realizadas" ≠ falha do app** | MUT-5 (`missed`) derruba 2; MUT-10 (`naoAvisadas`=todos) derruba 2 | **preso** |
| **A falha do app tem linha própria** | MUT-16 (remover a linha) derruba 2 | **preso** |
| **"ainda" só no mês corrente** | MUT-8 derruba `mesPassadoNaoFalaEmAinda` | **preso** |
| **`insightRows` leva as não realizadas** | MUT-12 (tirá-las) derruba 7 | **preso** |
| **O mês vazio de um mês passado** | "0 feitas / Você não marcou nada como feito neste mês." (S3) | OK |
| **A falha de leitura não vira mês zerado** | `agendaUi.failed` monta o cartão "Não consegui ler a sua agenda" (`:126-138`) | OK |
| **O próximo mês fica indisponível no mês atual** | `enabled = month.isBefore(YearMonth.now())` (`:107`) | OK |
| **A contagem diária funciona** | 20 doses concluídas → "20 feitas · Tomar remédio · 20 vezes" (S4) | OK |
| **`CANCELLED` (série encerrada) não vira "não realizada"** | MUT-9 passa **invisível** — nenhum teste prende, mas o comportamento atual é o certo | cobertura zero |

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

Baseline (produção intacta, sondas fora da conta): **domain 538 / app 798, 0 falhas** — mais o
`FonteGrandeGeometriaTest`, **vermelho pré-existente e alheio a esta leva** (sensibilidade a fontes
do SO; aparece igual em `origin/main` puro e em toda execução, mutada ou não).

| Run | Mutação | domain | app | Falhas | Veredito |
|---|---|---|---|---|---|
| MUT-1 | a tela do mês abre no mês passado por padrão | — | 798 | **0** | **invisível** (sonda S15 mata) |
| MUT-2 | recap da home: dias 1–3 mostram o mês corrente | — | 798 | **0** | **invisível** (cego: sem relógio injetável — ver F2) |
| MUT-3 | `.take(8)` → `.take(4)` | 538 | 798 | **1** (sonda) | pega só pela sonda |
| MUT-4 | `insightRows`: `naoAvisada = false` | — | 798 | **5** | pega |
| MUT-5 | `monthMissedLines` usa `missed` | — | 798 | **2** | pega |
| MUT-6 | a soma inclui não-concluídas | 538 | — | **1** | pega |
| MUT-8 | mês passado também diz "ainda" | — | 798 | **1** | pega |
| MUT-9 | "não realizadas" inclui `CANCELLED` | 538 | 798 | **0** | **invisível** |
| MUT-10 | `naoAvisadas` = todos os `MISSED` | 538 | — | **2** | pega |
| MUT-11 | `frequent` conta `PENDING`/`MISSED` | 538 | — | **2** | pega |
| MUT-12 | `insightRows` sem as não realizadas | — | 798 | **7** | pega |
| MUT-16 | o mês deixa de dizer "Não consegui avisar" | — | 798 | **2** | pega |

**3 mutações invisíveis à suíte de 1336 testes** (MUT-1, MUT-2, MUT-9): o **mês em que a tela abre**
— o eixo de F2 — e a inclusão de `CANCELLED` não são prendidos por nenhum teste. MUT-1 é matável
por sonda (S15); **MUT-2 não é** — a janela do recap da home é função do **dia real** e o eixo não
tem relógio injetável (ver a cegueira de teste registrada em F2). Fica para a próxima leva: **a
janela do recap da home não é medível por composição sem um relógio injetável, e enquanto isso o
cartão pode mudar de comportamento sem teste nenhum acusar.**

Restauração: backup em `/tmp/fala-backup-mes/`, refeito **antes de cada mutação**, restaurado por
`cp` com `sha256` conferido; `git diff -- app/src/main domain/src/main` **vazio** ao fim.

```
f0b4af0c02bc38aba2c8a475946d1976586623ebc35a9607d00e9eaf19ab305c  MonthSummaryScreen.kt
781473b03057a25d67b6c27a17f8342657150b4c77d03f279eb3a12b52f2b324  MonthInsights.kt
2f765c00af25648d1bfed9262081de9cefd52541ba204993da426bf6c2bf7b72  HomeScreen.kt
```

## Não confirmado

- **A tela real não foi executada num emulador.** As medições são Compose em Robolectric (o
  `MonthSummaryScreen` **composto** de verdade, com Room in-memory e `AppContainer` real).
- **O F4 (atribuição) é hipótese na fronteira:** medi que `lastReminderAt` só é gravado no `fire`
  entregue (`TaskRepository.kt:661`), e que toda ocorrência não-avisada nasce `MISSED` sem o campo.
  **Não medi em aparelho** se uma rotina diária real, com o app aberto todo dia, produz
  `lastReminderAt` preenchido e faz a linha "N não realizadas" aparecer. Se produzir, F4 é menos
  grave do que parece; se não, a distinção do #44 é inalcançável no fechamento do mês.
- **F1 (catch-up) não é um defeito da tela do mês** — é de dados, a montante. A tela o **exibe**. Não
  medi se o app cria os dias faltantes por outro caminho (varredura por dia) no aparelho real; o
  `rescheduleAll` do start foi exercido com o relógio movido à mão, e uma varredura única não
  recuperou os dias intermediários.
- **`CANCELLED` no mês:** não medi uma série encerrada ponta a ponta pela tela de edição; a mutação
  MUT-9 mediu a **ausência de teste**, não o desfecho visual.
- **TalkBack:** não foi executado por leitor de tela; a tela tem `heading()` no rótulo do mês (`:102`).
- **A lista do "o que mais você fez" em fonte grande:** não medi a geometria com `fontScale` (o eixo
  do #113). A tela rola (`verticalScroll`, `:84`), mas não medi se o cartão inteiro cabe.

## Nota de método

O corpus foi escrito antes do código, a partir do README ("Resumo do mês com o que mais você fez") e
das perguntas de uma idosa sobre o mês passado. Ele pegou o P1 do catch-up (F1): a pergunta "quantos
dias eu esqueci de tomar?" não aparece em nenhuma estrutura do código — só na boca de quem confia no
resumo. As duas cegueiras medidas (o mês em que a tela abre, e o teto de 8) são **fronteiras de
argumento**: a rota chama a tela sem dizer o mês, e a mutação de uma linha passa verde. Fica para a
próxima leva: **toda tela de UI se mede compondo, e todo argumento com valor padrão é um ponto cego
até que uma sonda o prenda.**
