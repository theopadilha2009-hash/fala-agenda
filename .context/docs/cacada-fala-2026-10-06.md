# Caçadas — fala, conversa, áudio e toque (06/10/2026)

Sete caçadas independentes feitas em paralelo sobre o caminho real da mãe: ela fala, o
app entende, o alarme toca, ela abre o app e responde. Diferente da auditoria de
05/10 (leitura de código), **a maior parte daqui foi medida executando**: parser rodado
com relógio fixo, telas compostas de verdade, contadores de toque instrumentados e a
árvore de semântica inspecionada.

Base de tudo: `fbebfa9`. Onde a medição foi feita contra um PR, o PR está nomeado.

O que este documento é: o registro dos defeitos **medidos** e do que já está correto.
O que não virou PR ainda está marcado como tal. Nada aqui foi inferido de leitura sem
dizer que foi.

---

## Parte 1 — a conversa de volta (correção)

### 1. [ALTA] Não existe gatilho de correção — ela corrige o app e o app registra o oposto

`domain/.../parser/SpeechIntent.kt` — nenhum tratamento para `não é`, `errei`,
`na verdade`, `esquece`, `deixa`. Medido: 7 de 7 frases viram `Capture`.

| ela fala | o app faz |
|---|---|
| `na verdade é quinta às dez` | tarefa **"Verdade"**, quinta 20/08 às 10:00, `qc=true` — **confirma em silêncio** |
| `não é amanhã` | tarefa **"Não"** para amanhã |
| `não é amanhã, é quinta` | tarefa "Não quinta", e a data é **amanhã** — `amanhã` vence antes de o dia da semana ser lido |
| `errei, é às nove` | título **vazio**, hora 09:00 |
| `não é o remédio, é a injeção` | tarefa "Não injeção" |

O dano é o formato de defeito que este projeto já nomeou como o pior: **o app confirma
em silêncio uma coisa que ela não pediu**. A frase de correção é justamente a que ela
fala quando percebeu o erro — e é a que o app trata como tarefa nova.

### 2. [ALTA] `muda`/`apaga` com **artigo** viram tarefa nova; com pronome funcionam

`SpeechIntent.kt:232` exige `muda` + (`pra`/`para` direto | pronome). Medido:

| ela fala | o app faz |
|---|---|
| `muda o remédio pra amanhã às nove` | **tarefa nova** "Muda remédio", 21/08 09:00, `qc=true` — e o remédio **segue no horário velho** |
| `muda a consulta pra sexta` | tarefa nova "Muda consulta" |
| `apaga o médico` | tarefa nova "Apaga médico" |
| `muda pra amanhã às nove` (controle) | `CHANGE` — funciona |
| `adia isso pra amanhã` (controle) | `CHANGE` — funciona |

O mecanismo está certo; é o **artigo** que o derruba. `muda o remédio…` é a frase mais
natural das duas.

---

## Parte 2 — o vocabulário

### 3. [ALTA] Toda palavra antes de uma vírgula desaparece do título

`domain/.../parser/LocalTaskParser.kt:917-937` — **causa raiz localizada.** O título é
remontado por dois lados que não normalizam igual:

- `leftover` (linhas 918-921) sai do texto **cru**, então a palavra carrega a
  pontuação: `"pão,"`.
- o candidato (linha 923) é `fold(word).trim(',', '.', '!', '?')` → `"pao"`.

`"pao" == "pão,"` é falso e `"pao".startsWith("pão,")` também — a palavra **nunca casa
e é descartada**. O Vosk insere vírgulas, então isto não é caso de borda.

| ela fala | título hoje |
|---|---|
| `comprar pão, leite e ovos` | **"Comprar leite ovos"** — perdeu o pão |
| `levar o exame, a receita` | **"Levar receita"** — perdeu o exame |

### 4. [MÉDIA] As duas listas de preenchedores divergiram — 21 de 23 vazam para o título

`FILLERS` (`LocalTaskParser.kt:1225-1233`, limpa o **título**) e `FILLER_PREFIX`
(`SpeechIntent.kt:82-92`, limpa a **intenção**) são duas listas para a mesma ideia, e
**21 das 23 formas do prefixo não existem em `FILLERS`** (as 2 que coincidem casam por
acidente, via `por` + `favor` soltos). Medido com a invariante "preenchedor no começo e
mais nada mudou ⇒ título idêntico": **21/23 divergem, no início e no meio**.

Os que vazam: `ah, bom, bem, entao, olha, escuta, pode, eu, hoje`.

| ela fala | título hoje |
|---|---|
| `ah remédio amanhã às oito` | "Ah remédio" |
| `bom remédio amanhã às oito` | "Bom remédio" |
| `eu preciso tomar remédio amanhã às oito` | "Eu tomar remédio" |

Isto é a **classe** por trás do achado isolado do áudio ("ãh remédio"): a correção é
unificar a fonte da verdade, não acrescentar mais uma palavra a uma das listas.

### 5. [BAIXA] `ontem` não é data; `de madrugada` é o único período sem a nota de inexatidão

Medido, os dois **sem** inventar dado errado (não há hora errada nem confirmação
silenciosa — `qc=false` em ambos):

- `fiz isso ontem` / `tomei o remédio ontem` → `localDate=null` e `ontem` **fica no
  título**. A tarefa nasce morta na lista, mas o app não a agenda no dia errado.
- `tomar remédio de madrugada` → `localTime=null`, `amb=false`, **sem** a nota "não é
  hora exata" — enquanto `de manhã`, `à tarde` e `à noite` (os três irmãos) recebem a
  nota e marcam `amb=true`. Nada errado é agendado; o tratamento é que é desigual.

---

## Parte 3 — a recorrência

### 6. [ALTA] O modelo não tem intervalo, contagem nem fim — cinco expressões não têm onde existir

`RecurrenceKind` (`domain/.../model/Models.kt:10-16`) só tem
`NONE/DAILY/WEEKDAYS/WEEKLY/MONTHLY/YEARLY`, e `RecurrenceRule` só carrega
`kind/weekDays/dayOfMonth/monthOfYear`. **Não existe `until`, `count` nem intervalo.**

Consequência: `de 8 em 8 horas`, `a cada duas horas`, `até o dia 30`, `por 7 dias` e
`durante uma semana` **não têm onde ser representados**. O `INTERVAL`
(`LocalTaskParser.kt:1030`) reconhece a expressão e a marca **ambígua**, mas nunca vira
série — a dose simplesmente não é criada.

Caso pior, porque mente: **`todo dia às 8 até o dia 30`** lê `dia 30` como **a data**,
não arma nada de 20 a 29 e **nunca termina**.

**Não é fix de parser.** Exige o modelo de domínio, uma migração do Room, a tela de
recorrência e o `PARSED_TASK_SCHEMA` da IA (`supabase/functions/_shared/openai.ts`,
que também não tem o campo). Desbloqueia as cinco de uma vez.

### 7. [BAIXA] `toda semana` sem dia não casa

`toda` (`:320`) soma com `extractWeekDays` vazio e não fecha; `weeklyPrefix` (`:301`)
idem. Suspeita por leitura — falta medir.

---

## Parte 4 — o áudio

### 8. [ALTA] A pausa dela vira o fim do recado, e o app não avisa

Quando ela pausa no meio da frase, o parcial é promovido a mensagem fechada e o estado
volta a `IDLE` sem nenhum sinal de que o app parou de ouvir. Ela acha que falou; o app
tem um recado pela metade.

### 9. [MÉDIA] A hesitação entra no título com confiança alta

`ãh remédio amanhã às oito` → título **"Ãh remédio"**, `conf=0.85`, `qc=true`. Mesma
classe do item 4 — ver lá para a causa raiz e a correção certa.

---

## Parte 5 — a jornada de toque

Método: telas compostas de verdade (`AppContainer` + Room real), alvos medidos em dp
contra o viewport, e **contadores instrumentados** (`editCalls`, `deletes`) em vez de
inspeção visual.

### 10. [ALTA] O cartão atrasado não tinha "Concluir" — o remédio que já passou era o único sem o gesto de um toque

Medido: com um pendente + um atrasado, `onAllNodesWithText("Concluir")` na home = **1**
(só o pendente). Só com o atrasado: **0**. O toque no cartão atrasado abria a edição
(`editCalls=1`), onde o "Concluir" fica em `top≈1179 dp` num viewport de `24–720 dp` —
**~460 dp de rolagem abaixo da dobra**.

Causa: `HomeScreen.kt:1174` exigia `status == PENDING`, e `:743-752` não passava
`onComplete` no loop de `missedSections`. O modelo **já aceitava** concluir MISSED
(`ConfirmDraftScreen.kt:397-402`, `TaskRepository.kt:183-184`).

**Corrigido** — PR #70.

### 11. [MÉDIA] "Excluir" apaga em um toque, sem confirmação, enquanto o irreversível confirma

`ConfirmDraftScreen.kt:418-419` chama `onDelete()` direto (`deletes=1`, nenhum
diálogo), enquanto `:422` faz o "Encerrar série" passar por confirmação. O botão fica a
~58 dp do "Concluir" e ~10 dp do "Fazer hoje". Se a tarefa for única,
`TaskRepository.kt:247-261` apaga a **série inteira**. O único freio é o snackbar com
"Desfazer" na tela seguinte. **Decisão de produto, não bug.**

---

## Parte 6 — sobrevivência de estado

**Medido e OK** (não mexer): boot declarado, re-arme idempotente sem duplicação, nenhum
`fallbackToDestructiveMigration` em lugar nenhum, escrita transacional (`applyBatch`),
fuso lido do relógio e não de coluna, permissão revogada detectada com caminho de volta,
cartão de bateria que persiste, DST sem exceção, nenhum vazamento de `AudioRecord`.

**Aberto:** `allowBackup="false"` com banco e prefs excluídos — a única cópia da agenda
dela é o celular. Parece intencional (dado de saúde), mas é decisão.

---

## Parte 7 — a instabilidade do CI

### 12. [ALTA] Um vazamento de corrotina derruba um teste aleatório por run

Exceção de um teste escapa e mata um teste **de outra classe**:
`AgendaWidgetSyncTest.temaNovoRepintaOWidgetSemAAgendaMudar`, com
`UncaughtExceptionsBeforeTest`. Evidência de que é pré-existente e não de um PR:

- na base `7e8a762` **sem nenhuma mudança**: `561 tests, 1 failed`, mesmo teste;
- isolado, o teste passa **4/4**;
- emparelhado com cada classe nova do lote, também passa;
- `gh run rerun` no **mesmo sha** deu verde depois de vermelho.

Já fez o CI de **três** PRs piscarem vermelho (#63, #69 e o #70), sempre com
`554/570 tests completed, 1 failed` e nunca o mesmo teste.

**Por que é ALTA:** a regra da casa é "trust-the-CI: verde basta". Um verde que pisca
não prova nada, e o vermelho falso já custou tempo de três lotes.

---

## Resumo do que já foi corrigido

| # | Defeito | Onde |
|---|---|---|
| 10 | Cartão atrasado sem "Concluir" | PR #70 |
| — | Widget cortava o título (130×338 dp → 203×220 dp) | PR #63, mergeado |
| — | Divisor da gaveta a 1,18:1 de contraste | PR #63, mergeado |
| — | Guarda de contraste escapava com quebra de linha | PR #63, mergeado |

Os itens 1, 2, 3, 4, 6 e 12 têm causa raiz localizada e ainda **não** viraram PR: os
quatro primeiros caem em `LocalTaskParser.kt` e `SpeechIntent.kt`, disputados pelos PRs
#67, #68 e #62 abertos ao mesmo tempo. A ordem é mergear e depois corrigir, para não
empilhar um terceiro ramo no mesmo arquivo.
