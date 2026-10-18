# Caçada — DOIS HORÁRIOS SEM PERÍODO na primeira ("às três e 8 da noite" = 03:08) (2026-10-18)

Vigésima quarta leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

Achado no review do #126, **pre-existente**: a regra das duas tomadas
(`segundaTomadaNoMesmoDia` em `LocalTaskParser.kt`) exige `hadPeriod` na PRIMEIRA hora. Quando ela
diz a primeira hora SEM período ("às três") e a segunda COM ("e 8 da noite"), o "e 8" entra de
MINUTO pela `trailingMinutes` e o "da noite" é consumido como período DA PRIMEIRA — e o app crava
03:08 confirmável num toque, um horário que ninguém falou.

**Base:** `origin/main` @ `3ff5922`. Worktree descartável
(`~/WORKSPACES/fala-caca-dois-horarios`). **Produção intocada** — só sonda de teste
(`ZZProbeDoisHorariosSemPeriodoTest`, apagada depois de medir). 26 formas naturais medidas,
`--rerun-tasks --max-workers=2`, contagem pelos XMLs.

---

## P0 — "às três e 8 da noite" crava 03:08 com `ambiguous=false` (e `qc=true` com data)

```
"tomar remédio às tres e 8 da noite"       → 03:08 · amb=false · notes='Falta a data.'
"tomar remédio às 3 e 8 da noite"          → 03:08 · amb=false
"tomar remédio às duas e 8 da noite"       → 02:08 · amb=false
"tomar remédio às cinco e 8 da noite"      → 05:08 · amb=false
"tomar remédio tres e 8 da noite"          → 03:08 · amb=false   (sem o "às" — o CLOCK_BARE_WORD casa igual)
"tomar remédio todo dia às tres e 8 da noite" → 03:08 · amb=false · QC=TRUE
```

Ela disse DUAS horas (3 e 8, ou 15 e 8) e o app crava uma madrugada 03:08. Com o "todo dia" a data
existe, `isComplete` fecha, e a caixa **"Pode salvar?"** confirma 03:08 em um toque. É o dano
exato que o #126 fechou para "às 8 da manhã e 8 da noite" — sobrevivendo na variante em que a
primeira hora vem nua.

**Prova do defeito:** sonda doutrina (`assertThat(d.ambiguous).isTrue()`) → **falha na linha 1**
(`expected to be true` para "às tres e 8 da noite"). Domain 602 / 1 falha, e a ÚNICA falha da
suíte é a sonda.

**Mecânica:** o relógio casa "às tres" (`CLOCK_WORD`, sem período), a `trailingMinutes` engole o
"e 8" como MINUTO, e a `TRAILING_PERIOD` consome o "da noite" como período da tomada — com
`hourRaw=3`, o `applyPeriodHour` **não move** 1–6 para a noite (só 7–11 e 12→0), sobra 03:08. Como
o período chegou pela cauda, `hadPeriod=true`, e nem a ambiguidade geral de 1–6 (`noPeriod`)
nem a segunda tomada (`timeHit.hadPeriod` agora é true, mas a `CLOCK_PERIOD` só encontra o "8 da
noite" na frente, sem tail) disparam.

**Segunda forma, mais silenciosa ainda — extenso + extenso:**

```
"tomar remédio às tres e oito da noite"    → 03:00 · amb=false · título='Tomar remédio oito'
"tomar remédio às 3 e oito da noite"       → 03:00 · amb=false · título='Tomar remédio oito'
```

O `MINUTE_TAIL_WORDS` não tem "oito": a hora fica 03:00 e o segundo número SOBRA NO TÍTULO — a
20h (ou a 15h) desaparece e o nome da tarefa fica poluído. Mesma raiz: o "da noite" que pertence
ao "oito" foi roubado para a primeira hora.

**Fechado pelo follow-up** (`fix/fala-extenso-na-cauda`): o `HOUR_WORD_TAIL` casa a cauda
"e <nome de hora>" (os mesmos nomes de `CLOCK_WORD`/`CLOCK_BARE_WORD`; "meia" e frações ficam de
fora — minuto por natureza, B4) e só entra no gatilho quando o período vem COLADO na cauda e a
coerência falha (noite × 1–6). Desfecho medido: `null · amb=true · nota de dois horários ·
título "Tomar remédio"`. Fora do gatilho ("às tres e oito" sem período, "… da manha") o título
sujo permanece como estava — travado em teste (`LocalTaskParserExtensoNaCaudaTest`).

---

## A tabela medida (26 formas)

| Fala | Resultado | amb | Veredito |
|---|---|---|---|
| `às tres e 8 da noite` | 03:08 | false | **DEFEITO** — "da noite" contradiz 03:08 |
| `às 3 e 8 da noite` | 03:08 | false | **DEFEITO** |
| `às duas e 8 da noite` | 02:08 | false | **DEFEITO** |
| `às cinco e 8 da noite` | 05:08 | false | **DEFEITO** |
| `às tres e oito da noite` | 03:00 · título "…oito" | false | **DEFEITO** (variante extenso) — **fechado pelo follow-up**: `null · amb=true · título "Tomar remédio"` |
| `às 3 e oito da noite` | 03:00 · título "…oito" | false | **DEFEITO** — **fechado pelo follow-up**: `null · amb=true · título "Tomar remédio"` |
| `às tres e 8 da manha` | 03:08 | false | OK — 3:08 da manhã é coerente |
| `às tres e meia da noite` | 03:30 | false | OK — fração fora do gatilho: "meia" é minuto por natureza, nunca abre tomada; corpus B4 prevalece (`LocalTaskParserTest` 607/1422/1737, travado no #129) |
| `às tres e 12 da noite` | 03:12 | false | **DEFEITO** |
| `todo dia às tres e 8 da noite` | 03:08 · **qc=true** | false | **DEFEITO com dano máximo** |
| `tres e 8 da noite` (sem "às") | 03:08 | false | **DEFEITO** (CLOCK_BARE_WORD) |
| `3 e 8 da noite` (dígito, sem "às") | null · amb=true | true | OK — escala, não crava |
| `às nove e 8 da noite` | 21:08 | false | OK — o período ALCANÇA (9→21) |
| `às 15 e 8 da noite` | 15:08 | false | FRONTEIRA — ver "Não confirmado" |
| `às 12 e 8 da noite` | 00:08 | false | OK — meia-noite coerente |
| `às 8 e 20 da noite` | 20:20 | false | OK (travado por teste) |
| `às 8 e meia da noite` | 20:30 | false | OK (travado por teste) |
| `às 8 e 12` | 08:12 | false | OK (travado por teste) |
| `às 3 e meia da tarde` | 15:30 | false | OK (travado por teste) |
| `às 3 e meia da madrugada` | 03:30 | false | OK — coerente |
| `às 3 da manhã e 8 da noite` | null · duas tomadas | true | OK (caminho do #126) |
| `às tres da manha e 8 da noite` | null · duas tomadas | true | OK (idem, extenso) |
| `às 3 da manhã e 8` | 03:08 | false | OK — minuto nu, coerente |
| `às 3 e 8` (sem período) | 03:08 | **true** | OK — ambiguidade geral de 1–6 |
| `às tres e 8` (sem período) | 03:08 | **true** | OK |

Nota da penúltima linha: SEM período, "às 3 e 8" é ambíguo (a regra de 1–6 escala). COM o "da
noite" o app fica MENOS cuidadoso — o período dito apaga a ambiguidade e crava uma hora que ele
mesmo contradiz.

---

## Doutrina proposta: o período tem que ALCANÇAR a hora cravada

O que separa minuto-de-mesma-tomada de segunda-tomada (ou de hora incoerente) quando a primeira
hora não tem período próprio **não é o valor do número nem a estrutura do "e"** — é a **coerência
entre o período dito e a hora que ficaria cravada**:

> Se o período chegou pela cauda do "e <num>" (`TRAILING_PERIOD`, não colado no relógio) e o
> `applyPeriodHour` NÃO move a hora cravada para dentro do período que ela disse — "às 3 … da
> noite" deixando 03:08, que não é da noite — o número não é minuto: o "e <num> da noite" é uma
> segunda tomada (ou a única, incoerente), e o desfecho é AMBÍGUO com time=null. Se o período
> alcança ("às 8 … da noite" → 20), o minuto legitimo continua somando.

**O par que prende:** `às tres e 8 da noite` (03:08, deve virar ambíguo) × `às tres e 8 da manha`
(03:08, deve CONTINUAR 03:08 cravado). Mesma estrutura, mesmo horário cravado, mesmo "e <num>" —
só o período difere, e a coerência período↔hora é o único critério que separa os dois sem tocar
nada mais. O segundo par de apoio: `às 8 e 20 da noite` (20:20 — o período alcança, minuto fica) ×
`às tres e 8 da noite`.

**Verificação contra os controles travados:**

| Controle | Esperado | Doutrina dá | Vivo? |
|---|---|---|---|
| `às 8 e 12` = 08:12 (`numeroSoltoQueCabeComoHoraContinuaSendoMinuto`) | 08:12 | sem período na cauda → não dispara → 08:12 | ✓ |
| `às 8 e meia da noite` = 20:30 (`minutoDitoNaMesmaTomadaComPeriodoDepoisContinuaSomando`) | 20:30 | 8→20, período alcança → não dispara → 20:30 | ✓ |
| `às 3 e meia da tarde` = 15:30 (idem) | 15:30 | 3→15, período alcança → 15:30 | ✓ |
| `às 8 e 20 da noite` = 20:20 (idem) | 20:20 | 8→20, período alcança → 20:20 | ✓ |
| `às 8 e 12 da noite` = duas tomadas (`numeroComPeriodoProprioAbreSegundaTomada`) | amb | primeira tem período próprio → caminho do #126 segue | ✓ |
| `às 8 da manhã e depois e 8 da noite` = 08:00 (`eDistanteNaoAbreSegundaTomada`) | 08:00 | "depois" entre o "e" e o período → o "da noite" não é da cauda → não dispara | ✓ |
| `às 3 da manhã e 8` = 03:08 | 03:08 | sem período na cauda → não dispara | ✓ |
| `às 3 e meia da madrugada` = 03:30 | 03:30 | "madrugada" × 03:30 é coerente → não dispara | ✓ |

**Critérios candidatos que MORRERAM** (quebram controle travado — morto é morto):

- **Teto numérico** ("e <num>" com num que cabe em 7–23 é hora): mata `às 8 e 12` = 08:12. É a
  mesma alternativa medida e descartada no #126 (`SEGUNDA_TOMADA`: 26 desvios no corpus). Morto.
- **Estrutura pura** ("e <num> + qualquer período depois" abre segunda tomada quando a primeira
  hora não tem período): mata `às 8 e meia da noite` = 20:30 e `às 8 e 20 da noite` = 20:20 —
  nestas o período da cauda PERTENCE à primeira hora (a alcança). Morto.
- **`hadPeriod` na primeira (o status quo)**: é o próprio defeito — o `TRAILING_PERIOD` fabricia
  um `hadPeriod=true` com o período que pertence ao SEGUNDO número, e a regra das duas tomadas
  nem chega a enxergar o caso.

**Forma de fix sugerida (não implementada):** dentro do `extractClock`, quando o período veio da
`TRAILING_PERIOD` (e não do casamento do relógio) e `applyPeriodHour(hourRaw, period) == hourRaw`
com o período sendo "noite" — a única faixa que não alcança 1–6 —, devolver `TimeHit(null,
remaining, true)` com nota de dois horários (a nota `NOTA_DUAS_TOMADAS` já existe). A variante
extenso+extenso ("e oito") cai no mesmo ramo, pois a hora cravada 03:00 também contradiz a noite.
(No #129 a frase acima era aspiração: a cauda em extenso nem chegava ao ramo — o `MINUTE_TAIL`
não casa "oito". O follow-up a fez cair, com um detector próprio para a cauda em extenso.)
Zone de extensão a medir antes: primeira hora 13–23 com "da noite" (`às 15 e 8 da noite` → 15:08
hoje; "15 da noite" também é incoerente, mas "21 da noite" é coerente — o critério de coerência
resolve, o de faixa fixa não).

---

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

Produção intocada; a sonda (3 testes) foi apagada após a medição e o baseline re-rodado fresco.

| Run | O que | domain | Falhas | Veredito |
|---|---|---|---|---|
| Sonda (com produção atual) | 26 formas + doutrina + controles | 602 | **1** | a ÚNICA falha é `probe - doutrina proposta` — oráculo do defeito |
| Baseline (sonda apagada) | produção intacta | **599** | **0** | limpo |

A forma `todo dia às tres e 8 da noite` dá `qc=true`: a caixa "Pode salvar?" oferece 03:08 para
confirmação em um toque — o dano exato que o #126 descreveu para a variante com período.

## Não confirmado

- **Primeira hora 13–23 com "da noite"** (`às 15 e 8 da noite` → 15:08, `às 21 e 15 da noite` →
  21:15): medi os valores, não medi o juízo de coerência para essas faixas — "15 da noite" parece
  incoerente, "21 da noite" é coerente; a extensão do critério precisa de corpus próprio.
- **"às 3 da noite" direto (sem "e")** → 03:00, mesma incoerência pela leitura simples — é outro
  eixo (período colado no relógio, sem "e <num>" no meio) e não foi medido aqui além do registro.
- **A fala ponta a ponta por voz** (Vosk transcrevendo): medi o texto do parser; a transcrição
  real de "três" pode vir "3", "trez" etc. — os dois formatos foram medidos por texto.
- ~~**Título na variante extenso+extenso com fix proposto**~~ **MEDIDO pelo follow-up**: no
  gatilho (noite × 1–6) o título sai limpo ("Tomar remédio"); fora dele ("às tres e oito" sem
  período, "… da manha") o título sujo permanece — pré-existente, fora do gatilho, travado em
  teste como estado atual.
- **A faixa `noite` incluindo "noitinha"/"noitezinha"** (`PERIOD_PHRASE` as tem, a
  `TRAILING_PERIOD` não): não medi essa divergência.
