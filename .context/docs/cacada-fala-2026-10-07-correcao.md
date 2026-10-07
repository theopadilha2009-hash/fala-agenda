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
