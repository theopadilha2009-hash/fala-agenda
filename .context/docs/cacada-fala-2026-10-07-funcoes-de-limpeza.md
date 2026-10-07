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

| Fala | DEVE sair | SAI (real) | Cenário |
|---|---|---|---|
| `pagar 30,50 reais toda segunda` | R$30,50 | título `Pagar`, **VALOR 5000 (R$50,00)**, `ambiguous=false` | a fatura de R$30,50 vira R$50,00 calada |
| `pagar 12,90 reais toda segunda` | R$12,90 | título `Pagar 12,90`, **VALOR 9000 (R$90,00)**, `ambiguous=false` | idem |
| `toda segunda pagar 30,50 reais` | R$30,50 | título `Pagar 30,50`, **VALOR 5000**, `ambiguous=false` | idem |
| `toda segunda pagar 2 mil e 500 reais` | R$2.500 | título `Pagar 2 mil`, **VALOR 50000 (R$500,00)**, `ambiguous=false` | idem |
| `toda segunda tomar remédio oito e meia` | 08:30 | **HORA null**, título `Tomar remédio oito` | alarme de dose sem hora |
| `toda quarta tomar remédio às sete e meia` | 07:30 | **HORA 07:00**, título `Tomar remédio` | alarme meia hora errado |
| `toda segunda tomar remédio nove e vinte` | 09:20 | **HORA null**, título `Tomar remédio nove vinte` | dose sem hora |
| `toda segunda pagar 30 reais e 50 centavos` | R$30,50 | título `Pagar 50 centavos`, **VALOR 3000** | valor errado + lixo no título |

**Prova de que a linha CAUSA, não só coexiste:** sob a mutação (removendo o `e` da regex,
mantendo a vírgula), `pagar 30,50 reais toda segunda` → VALOR **3050** e
`toda segunda pagar 12,90 reais` → VALOR **1290**, os dois **corretos**. A limpeza é a causa
única.

**Prova de invisibilidade:** mutar `\b(e|,)\b` → `\b(,)\b` e depois → `\b(e)\b` deixa a
suíte **inteira verde** (domain 489 / app 761) nas duas. **1250 testes não veem a diferença.**

**Cobertura: zero.** Não existe teste no repo que combine dia da semana com valor em reais,
hora por extenso, ou centavos (`grep` de `reais`/`e meia` cruzado com
`segunda|terça|…|toda` nos dois módulos: vazio). As falas que exercitam esses tokens
(`LocalTaskParserValoresEFaixasTest`, `LocalTaskParserValorMistoECentavosTest`) usam
`amanhã às 10h` — que **não passa por `stripWeekDays`**.

## P1 — o título perde `e` e vírgula em toda fala com dia da semana, sem nota

Com `ambiguous=false` e sem nota, a caixa rápida confirma calada:

- `comprar pão e leite toda segunda` → `Comprar pão leite`
- `comprar dois quilos de tomate e um de cebola toda terça` → `Comprar dois quilos tomate cebola`
- `comprar arroz e feijão toda segunda` → `Comprar arroz feijão`

## P2 — a conjunção `e` some do título mesmo SEM data

- `comprar pão e leite` → `Comprar pão leite`
- `falar com a Maria e com o João` → `Falar com Maria com João`
- `tomar água e remédio` → `Tomar água remédio`

**Não coberto** — o único teste que afirma `e` no título
(`LocalTaskParserListaPorVirgulaTest:43`) espera `Comprar leite pão ovos`, ou seja,
**codifica** a remoção do `e`.

## O inventário

| Função | `arquivo:linha` | O que apaga | Efeito colateral medido |
|---|---|---|---|
| `TextNormalizer.fold` | `TextNormalizer.kt:6` | acento (NFD + `\p{M}`) | **nenhum** — só alimenta comparação dobrada; o título sai do `original` |
| `TextNormalizer.compactSpaces` | `TextNormalizer.kt:11` | espaços múltiplos | nenhum |
| `stripWeekDays` (corpo) | `LocalTaskParser.kt:1789` | dias + `\b(e,)\b` | **P0/P1** acima |
| └ o `\b(e\|,)\b` | `LocalTaskParser.kt:1795` | toda conjunção e vírgula pós-dia | **o defeito** |
| `stripFeiraSuffix` | `LocalTaskParser.kt:1809` | `\bfeiras?\b` com guard de preposição | nenhum — `ir na feira de ciências do neto sábado` → `Ir feira ciências neto` OK |
| `stripDayWords` | `LocalTaskParser.kt:1492` | `\bamanha\b\|\bhoje\b` | nenhum — `comprar o jornal de amanhã` → `Comprar jornal`, data certa |
| `FILLERS` (uso) | `LocalTaskParser.kt:2385` / `:1711` | `de do da um uma e` do título | **P1/P2**; remover `de/do/da/um/uma` derruba **13 testes** → essa metade está pinada |
| `withoutFiller` / `FILLER_PREFIX` | `SpeechIntent.kt:305/318` | preâmbulo de cortesia | nenhum no que se testou; abertura fora da lista (`e aí,` `bom dia,` `ó,` `aí,`) cai em `Capture` |
| `stripTrailingCourtesy` | `SpeechIntent.kt:618/622` | `por favor/obrigada/sim/ok/ta` no fim | nenhum medido |
| `stripLeadingArticles` | `SpeechIntent.kt:639` | artigo/demonstrativo que abre o alvo | nenhum medido |
| `looksLikeTwoTasks` / `NUMBER_E` | `LocalTaskParser.kt:1660/2365` | cola `NUMBER_E` antes de dividir | nenhum — protege `vinte e cinco` |
| `dateTimeDigitPositions` | `LocalTaskParser.kt:1760` | dígito usado como data/hora | nenhum |
| `extractTitle` `.trim(',','.','!','?')` | `LocalTaskParser.kt:1713/1727` | pontuação de borda | nenhum — o caso `,` colada tem teste (#92) |

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

## Não confirmado

- **`SpeechIntent`:** `cancela a consulta do cardiologista e o exame` mantém o `e` no alvo —
  o `de/do/da` **não** é removido ali, diferente do `FILLERS` do parser. Não se mediu se
  isso gera alvo que não casa.
- **`:app` não foi rodado sob MUT 3** (FILLERS).
- **`fold`/acento**: nenhum teste referencia `TextNormalizer` diretamente; não se varreu se
  o acento perdido muda semântica em algum consumidor do `:app`.
