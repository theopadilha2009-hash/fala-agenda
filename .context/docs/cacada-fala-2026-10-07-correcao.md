# Caçada — a correção na própria fala (2026-10-07)

Terceira leva de caçadas. As sete primeiras estão em
`auditoria-fala-2026-10-05.md` e as duas seguintes em `cacada-fala-2026-10-06-novos.md`.
Esta mede um eixo que nenhuma das nove tocou: **ela fala, percebe que errou, e se corrige
sem parar de falar.**

Todas as medições foram feitas em cópia descartável (`/tmp/caca-correcao`), com o parser
real e `FixedAppClock` em `2026-08-20 10:00 America/Sao_Paulo` (uma sexta-feira).

## Manchete

**O parser nunca entende a correção.** Em **192 de 240** casos de correção de dia e em
**192 de 192** de hora, ele ou descarta o valor corrigido ou fica ambíguo. E em **120 de
150** casos com data e hora completas ele **salva calado com o valor errado**
(`ambiguous=false` + `canQuickConfirm=true`), porque o `canQuickConfirm` só olha
ambiguidade — nunca se a frase continha uma correção.

No exemplo mais simples, **o título inteiro se perde**: `"amanhã às duas... não, às três"`
→ `title="Tres"`.

## P0 — salva calado com o dia que ela descartou

```
"me lembra de tomar remédio amanhã às oito, não, hoje"
  → date=2026-08-21  time=08:00  amb=false  qc=true  title="Tomar remedio hoje"
```

Ela mandou corrigir para **hoje** (20/08). O app agenda **21/08** — e o título diz "hoje".
Como `canQuickConfirm` é `true`, a caixa "Pode salvar?" abre já pronta: **um toque** e o
compromisso está no dia errado, com o rótulo certo. Ela não tem como perceber.

Vale para todos os conectores medidos (`, não,` `, quer dizer,` `, digo,` `, na verdade,`
`, melhor,` `, errei,` `, ao invés disso,` `, em vez disso,`) e para os quatro prefixos
testados. **48 de 240** casos do oráculo de dia caem aqui.

```
PROBE-VIOL|dia|amanha->hoje|cue=«, nao, »|frase=«me lembra de tomar remedio amanha as oito, nao, hoje»|obtido=(2026-08-21,08:00)|esperado=(2026-08-20,08:00)|amb=false|qc=true|title=«Tomar remedio hoje»
```

O caminho até a caixa: `HomeScreen.kt:259` (`quickDraft = draft` → `QuickConfirmDialog`,
`onSave` em `HomeScreen.kt:864-868`).

## P0 — a correção de hora destrói a hora

```
"me lembra de tomar remédio amanhã às duas, não, às três"
  → obtido=(2026-08-21, null)  esperado=(2026-08-21, 03:00)  amb=true
```

O parser acha **duas** horas (`found.size > 1` → `TimeHit(null, remaining, true)`) e **joga
as duas fora**: `localTime` fica nulo. Ela disse a hora duas vezes e o app não guarda
nenhuma. **192 de 192** casos de correção de hora.

Não salva calado (`qc=false`), mas a hora some — e sem hora o app não tem quando avisar.

## P1 — o título vira lixo, e às vezes some

O conector de correção **não é removido**: `FILLERS` (`LocalTaskParser.kt:1225-1233`) não
contém nenhum deles. O título final sai de `extractTitle` sobre o texto **já mutilado**:

| ela fala | título hoje |
|---|---|
| `me lembra de tomar remédio na sexta, não no sábado` | **"Tomar remedio nao sabado"** |
| `amanhã às duas... não, às três` | **"Tres"** (o título real desaparece) |
| `errei, é dia 20` | **""** (vazio; `faltam=[TITLE, TIME]`) |
| `às duas, não, às duas` | "Tomar remedio duas" (funciona por acaso) |
| `às duas, não, às três` | "Tomar remedio tres" |

Em **366 de 576** casos o título carrega a palavra de correção (`não`, `quer`, `dizer`,
`melhor`, `inves`, `vez`, `errei`, `verdade`, `digo`) e/ou o valor descartado. A prova de
que o resultado depende do filtro e não da intenção dela é o par `"às duas, não, às duas"`
(acerta) × `"às duas, não, às três"` (erra): os dois passam pelo mesmo caminho.

## P1 — o classificador de intenção é cego à correção, e é destrutivo

`SpeechIntentClassifier` não tem o conceito de correção nem de ambiguidade.

```
"cancela o médico, não, o dentista"  → Cancel(target=medico)  → delete(item)
```

Ela corrigiu para **dentista** e o app **apaga o médico** (`HomeViewModel.kt:798-803`).
**275 de 300** casos pegaram o alvo descartado. Sem a vírgula (`, nao `), o alvo vira
`"medico nao"`, que não casa com nada → *"Não achei nenhuma tarefa com esse nome."*

```
PROBE-VIOL|alvo-descartado|frase=«cancela o medico, nao, dentista»|intencao=Cancel(target=medico)|deveriaSer=«dentista»
PROBE-ESP-INTENT|«ja tomei o remedio de pressao, nao, o de diabetes»|Complete(target=remedio de pressao)
```

O `Complete` é o mais grave dos dois: no remédio, concluir o alvo errado é **dose errada
registrada**.

## P2 — o espelhado não cria tarefa nova, mas corrompe o título

| ela fala | título hoje |
|---|---|
| `não é pra mim, é pra minha filha` | "Nao minha filha" |
| `não tenho isso, é só um comprimido` | "Nao so comprimido" |
| `não é remédio de pressão, é o de diabetes` | "Nao remedio diabetes" |
| `não consigo de manhã, me lembra de tarde` | "Nao consigo" — e **"de tarde" é engolido**, `time=null` |

Nenhum vira duas tarefas (bom), mas a negação **entra no título** em todos, e a correção de
período some por completo.

## Oráculo (output colado)

Invariante central: `parse("<base com o valor ERRADO> <conector> <valor CERTO>")` deve ser
igual a `parse("<base com o valor CERTO>")`, com a referência construída por substituição
na própria base.

```
PROBE-RESUMO|invSubstituicao|casos=576|violValor=360|violTitulo=474|violValorComQC=0
PROBE-RESUMO|invNaoE|casos=144|violValor=36|violTitulo=144|violValorComQC=0
PROBE-RESUMO|invTitulo|casos=576|violCue=144|violValorNoTitulo=366|violCueComQC=0
PROBE-RESUMO|correcaoDeDia|casos=240|viol=192|violComQC=48
PROBE-RESUMO|correcaoDeHora|casos=192|viol=192|violComQC=0
PROBE-RESUMO|correcaoDePeriodo|casos=144|viol=0
PROBE-RESUMO|primeiraOuUltima|casos=240|pegouPrimeiraDescartada=48|pegouUltimaCorrigida=48|nenhuma=144
PROBE-RESUMO|alvoSobCorrecao|casos=300|pegouAlvoDescartado=275
PROBE-RESUMO|quantosSalvamCalado|casos=150|qcTrue=120|qcComValorDiferenteDaCorrecao=120
```

**A regra de decisão, medida:** quando algum valor vence, vence o **primeiro** — o que ela
**descartou** (48 de 48). Nos outros 144 nenhum valor sobrevive: a correção produz
ambiguidade ou nulo.

## Diferencial — o defeito é pré-existente

Mesmo oráculo, mesmos números no `origin/main` de hoje (`83bf24bc`) e no head de **cada**
PR aberto que toca esses arquivos. **Nenhum PR introduz nem conserta** isto:

```
=== main (83bf24b) ===  correcaoDeDia viol=192 violComQC=48 | correcaoDeHora viol=192 | alvoSobCorrecao pegouAlvoDescartado=275
=== pr68 (4c93a2b) ===  idêntico
=== pr62 (0102bc5) ===  idêntico
=== pr67 (ce55833) ===  idêntico
=== pr75 (e7de010) ===  idêntico
```

sha256 idêntico ao repo real em `LocalTaskParser.kt` (`c56e45da…`) e `SpeechIntent.kt`
(`de1185f0…`).

## Parte B — a edição de uma tarefa que já existe

Segunda caçada, eixo irmão do primeiro: **ela pede para mudar algo que já está na agenda.**
Não é uma correção dentro da frase — é uma correção sobre o **mundo** ("o remédio agora é às
nove"). Espaço medido: `verbo × objeto × hora × data` = **1680 frases**, contra a API real,
com o relógio em `2026-08-20 06:00 America/Sao_Paulo`.

### Manchete

**O app não tem intenção de editar.** O `sealed interface SpeechIntent`
(`SpeechIntent.kt:16-34`) conhece só `Capture`, `Ask`, `Complete`, `Cancel` e `Unknown`. Ela
diz `"muda o remédio pra 9 horas"` e o app **cria a tarefa "Muda remédio"** às 09:00 — com a
rotina dela ainda às 08:00. **Dois alarmes de remédio.**

### P0 — o verbo de edição entra no título em 1260 de 1260

`extractTitle` (`LocalTaskParser.kt:917`) não tem lista de verbos de comando para remover;
`FILLERS` (`:1225-1233`) não contém nenhum dos oito.

```
INV2 VERBO-DE-EDICAO-NO-TITULO = 1260 de 1260
  INV2 muda o remédio pra nove    -> title="Muda remédio nove"
  INV2 muda o remédio pra 9 horas -> title="Muda remédio"
```

Ela veria **"Muda remédio nove / 20 de agosto às 09:00"** na caixa "Pode salvar?". Nada na
tela diz que é uma edição, nem que já existe um remédio às 08:00.

### P0 — salva calado em 360 de 1260

`canQuickConfirm` só é `false` em três situações: falta campo, `ambiguous`, ou o instante já
passou. Numa frase de edição com data nenhuma vale:

```
DATA|muda o remédio pra 9 horas hoje|title="Muda remédio"|date=2026-08-20|time=09:00|amb=false|QUICK=true
DATA|muda a consulta pra sexta às nove|title="Muda consulta"|date=2026-08-21|time=09:00|amb=false|QUICK=true
DATA|o remédio agora é às nove hoje|title="Remédio agora"|date=2026-08-20|time=09:00|amb=false|QUICK=true
```

Detalhe que agrava e que muda a prioridade: **`"às 9 horas"` em dígito é o único caminho que
produz hora.** `"muda o remédio pra nove"` (por extenso) sai `time=null` → não salva calado.
A forma mais natural de uma idosa é a **por extenso** — então o defeito silencioso se
concentra na forma menos provável dela, e a mais provável cai na tela de confirmação **com o
verbo de edição dentro do título**.

### P1 — `troca`, `corrige`, `atualiza`, `altera`, `ajusta` não existem no vocabulário

```
VERBO|muda     | Capture | Unknown(CHANGE) | Capture
VERBO|troca    | Capture | Capture         | Capture
VERBO|corrige  | Capture | Capture         | Capture
VERBO|atualiza | Capture | Capture         | Capture
VERBO|remarca  | Unknown(CHANGE) | Unknown(CHANGE) | Unknown(CHANGE)
VERBO|adia     | Unknown(CHANGE) | Unknown(CHANGE) | Unknown(CHANGE)
```

O gatilho é `Regex("\\bmuda\\s+(pra|para|isso|isto|ele|ela)\\b")` (`SpeechIntent.kt:232`) — o
`muda` **exige preposição ou pronome logo depois**. O artigo (`o remédio`) derruba a âncora, e
é exatamente o caso de uso. `remarca`/`adia` aceitam artigo (`:233`), por isso funcionam.

### P1 — a antiga continua no horário errado, e não há guard de duplicata

`TaskRepository.saveDraft` (`TaskRepository.kt:124`) sempre cria `TaskSeries` com
`UUID.randomUUID()`. Não existe cruzamento com o que já está na agenda. O dano de dois
alarmes já é conhecido do próprio código — `HomeScreen.kt:913` escreve *"recadastrar o que já
existe (segunda série, segundo alarme)"* —, mas só como proteção contra salvar duas vezes a
mesma coisa, nunca contra edição falada.

### P2 — `"não é mais"` vira título e às vezes come o horário certo

```
NAOMAIS|o remédio não é mais às oito, é às nove|title="Remédio não mais nove"|time=null|amb=true|quick=false
NAOMAIS|o remédio não é mais às oito|title="Remédio não mais"|time=08:00|amb=false|quick=false
```

Escala para a IA, mas o **prompt do remoto** (`supabase/functions/_shared/openai.ts:65`) é
*"Extraia um rascunho de tarefa em pt-BR. Nunca invente data ou horário ausentes"* — nada nele
diz "isto é uma edição". A IA é instruída a **extrair um rascunho**, e é isso que devolve.

### P2 — `adia`/`remarca` roubam capturas legítimas

`"adia o remédio"` → `Unknown(CHANGE)` → "não sei mudar". Pode ser perfeitamente a tarefa nova
*"Adiar o remédio"*. É o erro seguro (não cria nada errado), mas é uma porta que o `muda`
fechou de propósito.

### Oráculo

```
PROBE3-ESPACO-EDICAO 1680
PROBE3 capturas=1260 outros_intents=420
INV1 EDICAO-SALVA-CALADO(quick=true) = 360 de 1260
INV2 VERBO-DE-EDICAO-NO-TITULO = 1260 de 1260
INV3 TITULO-CONTEM-NAO-E-MAIS = 0 de 1260
INV4 EDICAO-RECONHECIDA-COMO-CHANGE = 420 de 1680
### ORACULO — espaco de CAPTURA LEGITIMA (24 frases)
INV5 CAPTURA-LEGITIMA-DESVIADA = 0 de 24
```

### Medido e OK (não refazer)

- **O desfecho seguro existe e é alcançável:** `"remarca a consulta"`, `"adia o médico pra
  amanhã"`, `"muda pra quinta"`, `"muda isso"` → `Unknown(CHANGE)` → *"Ainda não sei mudar uma
  tarefa falando. Toque na tarefa na lista para editar."* É o que a maioria das frases de
  edição **não** recebe.
- **O espaço espelhado está limpo:** 24 de 24 capturas legítimas com radical de edição
  (`"muda o óleo do carro"`, `"troca a lâmpada da sala"`, `"corrige a prova do neto"`,
  `"atualiza o cadastro no posto"`, `"altera a receita do bolo"`, `"ajusta a altura da
  cadeira"`, `"mudar o óleo do carro"`, `"trocar o pneu do carro"`, `"adiar a reunião"`, `"me
  lembra de trocar o remédio"`, `"vou mudar o remédio pra nove"`, `"preciso trocar o horário
  do remédio"`) continuam `Capture`. **Zero desviadas.**
- Toda frase de edição **sem** data tem `canQuickConfirm=false`.
- A string exata `"não é mais"` nunca sobrevive no título (a frase é mutilada antes, para
  `"não mais"`).
- A suíte está verde e não pegou nada disso: `SpeechIntentTest` 49/0, `LocalTaskParserTest`
  119/0, `QuickConfirmTest` 5/0. O `SpeechIntentTest` cobre `"muda pra quinta"` (com
  preposição) e `"mudar o óleo do carro"` (infinitivo); **o caso do meio — `muda` + artigo +
  objeto + hora — não existe em teste nenhum.**

### Não confirmado nesta parte

- **A tela não foi executada.** `QUICK=true` é medido; o que a tela faz com ele é lido
  (`HomeScreen.kt:258-263` → `quickDraft` → `QuickConfirmDialog.kt`, primário "Salvar").
- **A IA não foi chamada** para `"o remédio não é mais às oito, é às nove"` — o desfecho do
  caminho escalado ficou sem medição.
- **Não foi varrido o `data/`** em busca de um guard de duplicata fora do `saveDraft`.

## Parte C — a disfluência: hesitação, gagueira e muleta

Terceira caçada. Nenhuma das nove anteriores mediu isto, e é o que o reconhecimento de fala
entrega na prática: **uma idosa não fala em frase limpa.** Ela hesita, gagueja, se repete e
usa muleta. Todas as sondas rodaram em cópia descartável, com o parser real.

### Correção de premissa (método)

O briefing dizia "salva calado, sem mostrar a tela". O fluxo real é `canQuickConfirm=true`
(`HomeScreen.kt:259`) → **`QuickConfirmDialog` ("Pode salvar?")** com **`Salvar` no slot
primário** (`QuickConfirmDialog.kt:118-127`). Não é salvo sem interação — é **um toque**, com
o valor errado já preenchido e o botão certo em destaque. A classe de erro é a mesma; o
número de toques é 1, não 0.

### A1 [P0] Muleta dentro do período do dia → erro de 12 horas, silencioso

```
S4 controle "tomar remédio amanhã às oito da noite"        -> hora=20:00 amb=false qc=true
S4 hesit    "tomar remédio amanhã às oito da, hã, noite"   -> hora=08:00 amb=false qc=true
S4 VIOLA-SILENCIOSO: qc=true 20:00 -> 08:00
```

Ela veria **"Tomar remédio / Amanhã às 08:00"** e um toque em Salvar. **Remédio da noite às 8
da manhã.** Varredura de 11 horas × 3 períodos: **16 de 33 violam, 10 silenciosas.** A faixa
perigosa é exata:

```
"…às sete da, hã, tarde" -> 19:00 virou 07:00 amb=false SILENCIOSO(qc=true)
"…às oito da, hã, noite" -> 20:00 virou 08:00 amb=false SILENCIOSO(qc=true)
"…às onze da, hã, tarde" -> 23:00 virou 11:00 amb=false SILENCIOSO(qc=true)
"…às uma da, hã, tarde"  -> 13:00 virou 01:00 amb=true  qc=false
```

Horas **1–6** quebradas ficam `amb=true` (escala, seguro). Horas **7–11** ficam `amb=false` +
`qc=true` — **o mesmo número da manhã, cravado com cara de certeza.**

**Causa raiz** (`LocalTaskParser.kt`): os qualificadores da hora são **adjacentes por
construção** — `trailingMinutes` (`:469`, `m.range.first != from`) e `TRAILING_PERIOD` (`:480`,
`p.range.first == after`) exigem colagem. Uma muleta no meio os desconecta. E `noPeriod`
(`:492`) consulta `PERIOD_PHRASE` no **texto inteiro**, então o "tarde" solto no fim mantém
`noPeriod=false` — nem ambíguo fica. Bônus: a muleta entra no título (`"Tomar remédio ah"`).

### A2 [P0] Muleta dentro do minuto ("e meia") → perde 30 min, silencioso

```
S4 controle "…às oito e meia"      -> hora=08:30 amb=false qc=true
S4 hesit    "…às oito, hã, e meia" -> hora=08:00 amb=false qc=true
S4 VIOLA-SILENCIOSO: qc=true 08:30 -> 08:00
```

**6 de 6** casos. Mesma causa raiz (adjacência do `MINUTE_TAIL`). `"nove e meia"` → 09:00;
`"oito e quinze"` → 08:00, e o "quinze" ainda sobra no título.

### A3 [P0] Muleta imediatamente antes da hora → a hora some

```
"tomar remédio amanhã às oito"      -> hora=08:00 amb=false qc=true
"tomar remédio amanhã às, hã, oito" -> hora=null  amb=false qc=false titulo="Tomar remédio oito"
```

**69 de 981** variações perdem a hora. Não inventa hora errada (`qc=false` → tela de edição),
mas ela tem que **ditar de novo** — e o título fica `"Tomar remédio oito"`, com a hora no nome.

### A4 [P1] O "ou" da indecisão: escolhe a primeira e deixa o Salvar pronto

```
"tomar remédio amanhã às oito ou nove" -> hora=08:00 amb=false qc=true titulo="Tomar remédio ou nove"
```

Ela hesitou entre oito e nove; o app cravou oito e não marcou ambíguo.

### A5 [P1] Gagueira da hora → extração perdida (não inventa)

```
"tomar remédio amanhã às 21h 21h"          -> hora=null amb=true qc=false
"oito, oito horas tomar remédio amanhã"    -> hora=null amb=false qc=false titulo="Oito, tomar remédio"
"tomar remédio amanhã às oito, oito horas" -> hora=08:00 qc=true titulo="Tomar remédio oito"
```

A gagueira da hora nunca gera hora errada — ou some (seguro) ou repete a mesma. O resíduo é
o título.

### A6 [P2] Muleta e gagueira no nome da tarefa — 476 de 981 variações

```
"ah, deixa eu ver, tomar remédio amanhã às oito" -> titulo="Deixa eu tomar remédio"
"tipo assim, tomar remédio amanhã às oito"       -> titulo="Tipo tomar remédio"
"olha, eu preciso tomar remédio amanhã às oito"  -> titulo="Eu tomar remédio"
"tomar remédio, hã, às oito amanhã"              -> titulo="Tomar"          <- a tarefa se chama "Tomar"
"tomar, tomar remédio amanhã às oito"            -> titulo="Tomar, remédio" <- vírgula no nome
"tomar remedio amanha as oito ne"                -> titulo="Tomar remedio ne"
```

`"Tomar"` sozinho e `"Tomar, remédio"` são novos (o catálogo de 06/10 registrou a classe em
`"Ãh remédio"`). Todos com `qc=true`.

### Oráculo

| invariante | casos | violações |
|---|---|---|
| **I1** acrescentar hesitação não muda data nem hora (conjunto à mão) | 16 | **0** |
| **I1'** o mesmo, varredura sistemática (posição × 16 marcadores × vírgula × gagueira) | **981** | **154** (85 silenciosas, 69 perdeu) |
| **I2** hesitação não muda o título | 16 | **8** |
| **I3** `qc=true` ⇒ data/hora são as que ela disse | 9 | **4** |
| **I4** sem acento/minúsculo/ordem trocada dá o mesmo | 5 | data/hora **0**, título **5** |
| **I5** título nunca carrega muleta | 15 | **5** |
| **I7** período do dia não muda com muleta no meio | 33 | **16** (10 silenciosas) |
| **I8** "e meia" não muda com muleta no meio | 6 | **6** (6 silenciosas) |
| data muda com hesitação/gagueira | 981 | **0** |

```
PROBE| C total casos=981 quebraHora=154 quebraData=0 perdeQC=69 tituloComMuleta=476
```

**A lição de método se repetiu, e é a segunda vez no dia:** o conjunto **escolhido à mão**
(I1, 16 casos) deu **0 violações**; a varredura sistemática do **mesmo eixo** deu **154**. A
sonda manual não acha — o gerador acha.

### Diferencial — pré-existente, e nenhum PR aberto toca

Números **idênticos** nos três commits, com `--rerun-tasks`:

| commit | o que é | quebraHora / silenciosas / perdeQC / título |
|---|---|---|
| `83bf24b` | clone local | 154 / 85 / 69 / 476 |
| **`ca65eeb`** | **main real do GitHub hoje** (#62) | **154 / 85 / 69 / 476** |
| `4c93a2b` | head do PR **#68** (o único aberto que mexe em `extractDate`/`extractTime`) | 154 / 85 / 69 / 476 |

Não duplica os nove anteriores: a Parte A cobre a **correção com conector** (`"não,"`,
`"quer dizer,"`), eixo distinto e com causa raiz distinta (`found.size > 1` → hora nula). O
catálogo de 06/10 (itens 4 e 9) registrou a muleta no título. **Nada mediu o período ou o
minuto quebrado por hesitação (A1/A2) nem a hora que some (A3)** — são novos.

### Medido e OK nesta parte (não refazer)

- **O caminho limpo:** `"tomar remédio amanhã às oito"` → 21/08 08:00, `amb=false`, `qc=true`.
- **ASR cru não quebra data/hora:** sem acento, minúsculo, tudo junto, ordem trocada, sem
  pontuação — **0 de 5** violações de data/hora. `"amanha as oito e meia tomar remedio"` →
  08:30. O parser **não depende de acento, ordem nem pontuação** (só o título ecoa a forma).
- **Hesitação ANTES da frase é inofensiva:** `"é... tomar remédio"`, `"né, então, ..."`,
  `"bom, então, ..."` → data, hora e título corretos.
- **Data nunca muda** com hesitação ou gagueira: `quebraData=0` em 981 casos.
- **Gagueira de hora não inventa:** `"21h 21h"` → `null` + `amb=true` (seguro).

### Não confirmado nesta parte

- **Se a IA remota resgata.** Os casos `amb=true` escalam via `HybridParser.deveEscalar`. **Os
  silenciosos (A1/A2, `qc=true`) não escalam** — `deveEscalar` só dispara com campo faltando ou
  `ambiguous` —, então esse caminho está fechado mesmo com a IA ligada. A IA não foi chamada.
- **Forma real da hesitação no áudio.** Testado texto; as três formas de inserção (com vírgula,
  sem, e reticências) quebram todas.
- **Se o `PERIOD_PHRASE` global é intencional.** Ele existe para `"hoje à noite às nove"` não
  virar ambíguo, mas é o que mascara `noPeriod` no caso A1. Um fix de adjacência precisa
  decidir isso antes.

## Medido e OK (não refazer)

- **Sem correção, o caminho está limpo.** `"me lembra de tomar remédio amanhã às nove"` →
  `qc=true`, `title="Tomar remedio"`, `date=2026-08-21`, `time=09:00`.
- **O espelhado não cria segunda tarefa** nem apaga o título; `looksLikeTwoTasks` não
  dispara nessas frases.
- **`"apaga isso, não, aquilo"` → `Unknown(ERASE)`** e **`"muda pra quinta, não, pra sexta"`
  → `Unknown(CHANGE)`**: corretos, porque o gatilho abre a frase. O app diz que não sabe e
  não executa nada — desfecho seguro.
- **Pergunta de agenda resolve certo por acidente:** `"o que tenho hoje, não, amanhã"` →
  `Ask(TOMORROW)` (certo). Mas `"o que tenho amanhã, não, hoje"` → `Ask(TOMORROW)`
  (**errado**) — o `amanha.containsMatchIn(folded)` pega qualquer "amanhã" da frase.

## Não confirmado

- **Se a IA remota resgata.** `HybridParser.deveEscalar` escala quando falta data/hora, e
  `"amanhã às duas, não, às três"` (`time=null`) **escala** — o LLM poderia devolver 03:00.
  Não medido (exige rede). **O P0 do `qc=true` não escala** (`deveEscalar` devolve `false`
  com data e hora completas), então esse está fechado mesmo com a IA ligada.
- **Fluxo de UI real.** "Salva calado" é afirmação sobre `canQuickConfirm` + a caixa rápida
  de um toque, lida no código — não sobre toques observados no aparelho.
- **Transcrição.** Testado só o texto já transcrito. Se o Vosk entrega `"não"` ou `"não,"`,
  e como lida com a pausa (`"..."`), não foi medido.
- **`, nao ` sem vírgula** em `Complete`: o alvo vira `"medico nao"`; não verificado se o
  `SpeechTargetMatcher` casa isso por maioria.

## PENDENTE (decisão de produto, para o Ruan)

1. **O que vence numa correção?** O padrão humano é **a última** ("não, às três" = três).
   Mas a regra medida hoje é o **primeiro**. Recomendo: a última, com o conector **removido
   do título** e o valor descartado também.
2. **`"às duas, não, às duas"`** (correção redundante): hoje funciona por acaso. Depois do
   fix tem que continuar funcionando — é o caso em que ela hesita e repete o mesmo valor.
3. **A correção de alvo em `Cancel`/`Complete` é a mais perigosa** (apaga o médico, conclui
   o remédio errado). Vale tratar antes do resto? Recomendo **sim**: é a única que age
   irreversivelmente sobre dado real, e a classe de erro é a pior do app.
4. **O prefixo `"não é X, é Y"` no título**: hoje a negação entra no nome da tarefa. Se o
   fix tirar, some junto com ela a informação de que era uma correção — decidir se cabe uma
   nota.
5. **Editar por voz (Parte B) — o fix é pequeno, mas tem uma armadilha.** O desfecho seguro
   **já existe**: `Unknown(CHANGE)` → *"Ainda não sei mudar uma tarefa falando. Toque na
   tarefa na lista para editar."* O caminho barato é alargar o reconhecimento de mudança —
   mas **alargar os verbos sozinho é uma regressão**: `"troca a lâmpada da sala"` viraria
   `Unknown(CHANGE)` e ela **não conseguiria criar** essa tarefa (as 24 capturas legítimas
   medidas hoje passam justamente porque `troca` não é reconhecido).
   O sinal que separa os dois é o **valor novo na frase**: `"muda o remédio pra nove"` traz
   alvo **e** valor; `"troca a lâmpada da sala"` não traz valor nenhum. Regra proposta:
   **verbo de edição + objeto + valor novo (hora/data) ⇒ `Unknown(CHANGE)`**; sem o valor,
   continua `Capture` (como hoje). Recomendo medir essa regra contra as 24 capturas legítimas
   **antes** de implementar — se alguma for roubada, a regra está errada.
6. **Editar de verdade (mudar a série existente) é feature nova**, não fix: exige decisão de
   produto sobre qual série casar e o que fazer com as doses já passadas. Não cabe no mesmo
   lote do item 5.
7. **O guard de duplicata não existe.** Antes de qualquer edição falada de verdade, decidir o
   que fazer quando ela fala de um remédio que já existe — criar segunda série é o dano que o
   próprio código já reconhece (`HomeScreen.kt:913`).
8. **A disfluência (Parte C) é a que mais machuca, e a de fix mais estrutural.** A1 e A2 são a
   **mesma causa raiz** (adjacência dos qualificadores da hora: `trailingMinutes`/`TRAILING_PERIOD`
   exigem `range.first == after`) e a **única classe que entrega valor errado com o Salvar
   pronto**. O fix proposto é consumir os qualificadores por **janela com as muletas
   removidas**, não por colagem — o que toca o núcleo do `extractTime`. Recomendo tratar
   primeiro, e depois do fix **remedir a faixa 7–11 especificamente**: é ela que fica
   `amb=false` hoje (1–6 já escala) e é onde o erro de 12 h passa calado.
9. **`noPeriod` / `PERIOD_PHRASE` é decisão embutida no fix do item 8.** Hoje o período é
   procurado no **texto inteiro**, o que mantém `noPeriod=false` mesmo com o qualificador
   solto. O caso legítimo que ele protege é `"hoje à noite às nove"` (não virar ambíguo).
   Recomendo: quando o qualificador **não está colado** na hora, marcar `amb=true` — o app já
   tem o caminho seguro e ela prefere confirmar a receber o remédio na hora errada.
