# Caçada — as FUNÇÕES DE LIMPEZA: quem apaga texto antes do parse (2026-10-07)

Décima quarta leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

O gatilho foi um achado de outro lote: o `stripWeekDays` apaga `\b(e|,)\b` como **efeito
colateral** de tirar os dias da semana. A hipótese: existem **outras** funções que
apagam/reescrevem texto antes do parse e cujo efeito colateral ninguém mediu. Cada uma é um
lugar onde a fala dela **perde um pedaço** e o app não avisa.

**Base:** `origin/main` @ `e0b721c`. Cópia descartável (`/tmp/limpeza-caca`).
**13 funções de limpeza** inventariadas com `arquivo:linha`, **~120 falas** executadas no
parser real, **4 mutações** com contagem dos XMLs.

## P0 — o `\b(e|,)\b` de `stripWeekDays` é uma limpeza cega: come a vírgula do valor

`LocalTaskParser.kt:1795`, dentro de `stripWeekDays`, que roda sempre que há dia da semana:

```kotlin
remaining.replace(Regex("""\b(e|,)\b"""), " ")
```

A regex roda sobre **todo o texto depois do dia**, sem saber se o `e` é conjunção de dia,
conector de hora, `e` de número, ou se a vírgula é **separador decimal**. Saída real
(relógio 2026-08-20, quinta, 10:00, America/Sao_Paulo):

**CORRIGIDO em 08/10/2026 (review do #106).** A tabela abaixo é a medição **correta**. A
versão original deste doc tinha a **ordem invertida** em duas linhas: publicava
`pagar 30,50 reais toda segunda` (valor **antes** do dia) como quebrada, quando essa ordem
**já saía certa** na base — o `extractAmount` roda antes e consome o valor, então a limpeza
nunca o alcança. **O eixo do defeito é a POSIÇÃO do dia**: o dano exige o dia **antes** do
valor. Medido nas duas bases (`e0b721c` e `55f7448`, idêntico).

| Fala | DEVE sair | SAI (real) | Cenário |
|---|---|---|---|
| `pagar 30,50 reais toda segunda` | R$30,50 | **R$30,50 — já estava correto** | (não é dano; é o controle do caminho dia-depois-do-valor) |
| `pagar 12,90 reais toda segunda` | R$12,90 | **R$12,90 — já estava correto** | idem |
| `toda segunda pagar 30,50 reais` | R$30,50 | título `Pagar 30,50`, **VALOR 5000 (R$50,00)**, `ambiguous=false` | a fatura de R$30,50 vira R$50,00 calada |
| `toda segunda pagar 1.234,56 reais` | R$1.234,56 | **VALOR 5600 (R$56,00)** | idem, com milhar |
| `toda segunda pagar 2 mil e 500 reais` | R$2.500 | título `Pagar 2 mil`, **VALOR 50000 (R$500,00)**, `ambiguous=false` | idem |
| `toda segunda tomar remédio oito e meia` | 08:30 | **HORA null**, título `Tomar remédio oito` | alarme de dose sem hora |
| `toda quarta tomar remédio às sete e meia` | 07:30 | **HORA 07:00**, título `Tomar remédio` | alarme meia hora errado |
| `toda segunda tomar remédio nove e vinte` | 09:20 | **HORA null**, título `Tomar remédio nove vinte` | dose sem hora |
| `toda segunda pagar 30 reais e 50 centavos` | R$30,50 | título `Pagar 50 centavos`, **VALOR 3000** | valor errado + lixo no título |

**Prova de que a linha CAUSA, não só coexiste:** sob a mutação (removendo o `e` da regex,
mantendo a vírgula), `toda segunda pagar 30,50 reais` → VALOR **3050** e
`toda segunda pagar 1.234,56 reais` → VALOR **123456**, os dois **corretos**. A limpeza é a
causa única.

**A prova original estava inválida:** ela citava justamente as duas falas de valor
**antes** do dia — que já estavam corretas sem mutação nenhuma. As linhas de dia
**antes** do valor são a evidência válida.

**Prova de invisibilidade:** mutar `\b(e|,)\b` → `\b(,)\b` e depois → `\b(e)\b` deixa a
suíte **inteira verde** (domain 489 / app 761) nas duas. **1250 testes não veem a diferença.**

**Cobertura: zero.** Não existe teste no repo que combine dia da semana com valor em reais,
hora por extenso, ou centavos (`grep` de `reais`/`e meia` cruzado com
`segunda|terça|…|toda` nos dois módulos: vazio). As falas que exercitam esses tokens
(`LocalTaskParserValoresEFaixasTest`, `LocalTaskParserValorMistoECentavosTest`) usam
`amanhã às 10h` — que **não passa por `stripWeekDays`**.

## P1 — o título perde `e` e vírgula, sem nota

**CORRIGIDO em 08/10/2026 (review do #106).** A causa **não é** o `\b(e|,)\b` do
`stripWeekDays` — é a política de `FILLERS` no `extractTitle` (`:2428`), e o desfecho é
**idêntico com e sem o dia da semana**. O review mediu:

```
comprar pão e leite toda segunda   →  Comprar pão leite
comprar pão e leite                →  Comprar pão leite      (idêntico)
toda segunda e quarta natação às 18h  →  Natação / 18:00 / MON,WED   (idêntico nas duas bases)
```

A mutação que confirma: tirar o `e` de `FILLERS` mata 7 testes (3 do lote novo + 4
pré-existentes, entre eles `LocalTaskParserListaPorVirgulaTest`). A conjunção sai pelo
`extractTitle`, não pela limpeza do dia. Com `ambiguous=false` e sem nota, a caixa rápida
confirma calada:

- `comprar pão e leite` → `Comprar pão leite`
- `falar com a Maria e com o João` → `Falar com Maria com João`
- `tomar água e remédio` → `Tomar água remédio`

**Não coberto** — `LocalTaskParserListaPorVirgulaTest:43` espera `Comprar leite pão ovos`,
ou seja, **codifica** a remoção do `e`. (A expectativa está certa para o comportamento
atual; o que falta é um teste que decida se esse é o comportamento **desejado**.)

## P2 — a vírgula como separador de "reais, centavos" (novo, fora do escopo do #106)

O review achou um caminho que o #106 **não** fecha:

```
toda segunda pagar 30 reais, 50 centavos  →  amountCents=null, título "Pagar 30 reais 50 centavos 20"
```

Errado nas **duas** bases, por caminho diferente do que o #106 fecha (a vírgula como
separador entre reais e centavos, não como decimal). Vale lote próprio.

## O inventário

**`arquivo:linha` corrigidos em 08/10/2026** — os da versão original estavam defasados
contra a própria base que o doc declara (`e0b721c`). Os valores abaixo foram conferidos pelo
review do #106 na base `e0b721c`.

| Função | `arquivo:linha` (base `e0b721c`) | O que apaga | Efeito colateral medido |
|---|---|---|---|
| `TextNormalizer.fold` | `TextNormalizer.kt:6` | acento (NFD + `\p{M}`) | **nenhum** — só alimenta comparação dobrada; o título sai do `original` |
| `TextNormalizer.compactSpaces` | `TextNormalizer.kt:11` | espaços múltiplos | nenhum |
| `stripWeekDays` (corpo) | `LocalTaskParser.kt:1811` | dias + `\b(e,)\b` | **P0/P1** acima |
| └ o `\b(e\|,)\b` | `LocalTaskParser.kt:1817` | toda conjunção e vírgula pós-dia | **o defeito** (removido no #106) |
| `stripFeiraSuffix` | `LocalTaskParser.kt:1832` | `\bfeiras?\b` com guard de preposição | nenhum — `ir na feira de ciências do neto sábado` → `Ir feira ciências neto` OK |
| `stripDayWords` | `LocalTaskParser.kt:1492` | `\bamanha\b\|\bhoje\b` | nenhum — `comprar o jornal de amanhã` → `Comprar jornal`, data certa |
| `FILLERS` (uso) | `LocalTaskParser.kt:2428` / `:1711` | `de do da um uma e` do título | **P1/P2**; remover `de/do/da/um/uma` derruba **13 testes** → essa metade está pinada |
| `withoutFiller` / `FILLER_PREFIX` | `SpeechIntent.kt:305/318` | preâmbulo de cortesia | nenhum no que se testou; abertura fora da lista (`e aí,` `bom dia,` `ó,` `aí,`) cai em `Capture` |
| `stripTrailingCourtesy` | `SpeechIntent.kt:618/622` | `por favor/obrigada/sim/ok/ta` no fim | nenhum medido |
| `stripLeadingArticles` | `SpeechIntent.kt:639` | artigo/demonstrativo que abre o alvo | nenhum medido |
| `looksLikeTwoTasks` / `NUMBER_E` | `LocalTaskParser.kt:1682/2365` | cola `NUMBER_E` antes de dividir | nenhum — protege `vinte e cinco` |
| `dateTimeDigitPositions` | `LocalTaskParser.kt:1783` | dígito usado como data/hora | nenhum |
| `extractTitle` `.trim(',','.','!','?')` | `LocalTaskParser.kt:1735` | pontuação de borda | nenhum — o caso `,` colada tem teste (#92) |

## Medido e OK (não refazer)

- `stripWeekDays` para **dias**: `toda segunda e quarta` → `WEEKLY[MONDAY, WEDNESDAY]`;
  `toda segunda, quarta e sexta` → três dias corretos.
- `stripFeiraSuffix`: `ir na feira sábado` → `Ir feira`, `2026-08-22` (não come o substantivo).
- `stripDayWords`: `comprar o jornal de amanhã` → `Comprar jornal`, `2026-08-21`.
- Lista por vírgula (#92): `comprar pão, leite, ovos e queijo` → os quatro itens.
- Hora por extenso **sem dia**: `tomar remédio oito e meia` → 08:30.
- Valor composto sem dia: `pagar mil e quinhentos reais` → 150000.
- `looksLikeTwoTasks`: `tomar remédio às 8 e às 20 todo dia` → `ambiguous=true` com nota.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| Execução | domain | app |
|---|---|---|
| Baseline (`e0b721c`) | **489 / 0** | **761 / 0** |
| MUT 1 — `\b(e\|,)\b` → `\b(,)\b` | **489 / 0** | **761 / 0** |
| MUT 2 — `\b(e\|,)\b` → `\b(e)\b` | **489 / 0** | **761 / 0** |
| MUT 3 — `FILLERS` sem `de/do/da/um/uma` | **489 / 13** | não rodado |

**Verificado no #106 (review independente, base `55f7448`):** MUT 1 → **489/0**, MUT 2 →
**489/0**, confirmando a invisibilidade. E o #106, já com o fix, mede **130 consertos /
0 regressões** em 385 falas — o saldo que decide o merge.

## Não confirmado

- **`SpeechIntent`:** `cancela a consulta do cardiologista e o exame` mantém o `e` no alvo —
  o `de/do/da` **não** é removido ali, diferente do `FILLERS` do parser. Não se mediu se
  isso gera alvo que não casa.
- **`:app` não foi rodado sob MUT 3** (FILLERS).
- **`fold`/acento**: nenhum teste referencia `TextNormalizer` diretamente; não se varreu se
  o acento perdido muda semântica em algum consumidor do `:app`.
