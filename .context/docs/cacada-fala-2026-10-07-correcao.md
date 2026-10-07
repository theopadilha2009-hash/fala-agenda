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
5. **Editar por voz (Parte B) — o fix é grande ou pequeno?** O desfecho seguro **já existe**:
   `Unknown(CHANGE)` → *"Ainda não sei mudar uma tarefa falando. Toque na tarefa na lista
   para editar."* O caminho barato é **alargar o reconhecimento de mudança** para os verbos
   que faltam (`troca`, `corrige`, `atualiza`, `altera`, `ajusta`) e para o `muda` + artigo —
   o que transforma "cria tarefa nova calada" em "diz que não sabe e não cria nada".
   Recomendo **esse** primeiro: é pequeno, reversível e fecha o P0 dos dois alarmes. Fazer a
   edição de verdade (mudar a série existente) é feature nova, com decisão de produto sobre
   qual série casar e o que fazer com as doses já passadas.
6. **O guard de duplicata não existe.** Antes de qualquer edição falada, decidir o que fazer
   quando ela fala de um remédio que já existe — criar segunda série é o dano que o próprio
   código já reconhece (`HomeScreen.kt:913`).
