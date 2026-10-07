# Caçada — a RECORRÊNCIA e a repetição (2026-10-07)

Nona leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06.md`, `cacada-fala-2026-10-06-novos.md`,
`cacada-fala-2026-10-07-correcao.md`, `cacada-fala-2026-10-07-dose-e-duracao.md`,
`cacada-fala-2026-10-07-pergunta-e-resposta.md`,
`cacada-fala-2026-10-07-ancoras-de-rotina.md`,
`cacada-fala-2026-10-07-numeros-e-valores.md`,
`cacada-fala-2026-10-07-cadeia-de-audio.md`,
`cacada-fala-2026-10-07-instalacao-e-atualizacao.md`,
`cacada-fala-2026-10-07-multiplas-tarefas.md`,
`cacada-fala-2026-10-07-titulo.md` e
`cacada-fala-2026-10-07-caminho-de-volta.md`.

Esta mede o eixo mais crítico do app: **a fala que diz que a tarefa se repete**. É
assim que ela registra remédio, e uma cadência errada significa um remédio que não
toca ou que toca demais.

Medições em clone descartável (`/tmp/caca-recorrencia/repo`), parser real
(`LocalTaskParser`, classes de `domain/build/classes/kotlin/main` recompiladas do HEAD,
dirigidas por `jshell`), `FixedAppClock(2026-08-20 10:00 America/Sao_Paulo)` =
**quinta-feira**. ~185 falas medidas.

## Manchete

**8 defeitos, e as 8 mutações deixaram a suíte inteiramente verde** (`domain tests=439
failures=0 errors=0`). Nenhum dos oito é coberto por teste.

São dois tipos, e os dois piores são silenciosos — `ambiguous=false`, `canQuickConfirm=true`,
`notes=[]`, salvos num toque:

1. **Repetição inventada** — ela fala a cadência mensal e o app grava uma cadência
   **diferente e mais frequente** (diária ou semanal), calado.
2. **Repetição perdida calada** — ela fala um intervalo ou uma cadência e o app grava
   **uma vez só**, sem nota.

Dos 43 casos do oráculo: **8 com o `kind` errado calado** e **17 que deviam escalar ou
ambiguar e não o fizeram**.

## P0 — "às 8 da manhã e 8 da noite" vira `08:08` (hora inventada)

```
F|tomar remedio todo dia as 8 da manha e 8 da noite|DAILY date=2026-08-21 time=08:08 amb=false qc=true notes=[]
F|tomar remedio toda segunda as 8 da manha e 8 da noite|WEEKLY[MONDAY] time=08:00 amb=false qc=true notes=[]
```

**Hoje sai:** `time=08:08` — o "8" da **segunda** tomada virou **minuto** da primeira.
A segunda tomada (20h) não existe em campo nenhum. Com `qc=true` e sem nota, a caixa
"Pode salvar?" mostra 08:08 e ela confirma em um toque.

**Devia sair:** ambíguo com nota (duas tomadas não cabem em `TaskSeries.localTime`).

**Causa:** o relógio acha **uma** hora (`found.size == 1` em `LocalTaskParser.kt:794`,
porque só o primeiro `8` casa `CLOCK_NUMERIC`), e o `trailingMinutes` (`:867`) lê o
`e 8 …` como minuto. Com o **segundo "às"** explícito (`…e as 8 da noite`) o app ao menos
marca ambíguo; **sem** ele — a forma como se fala — ele crava.

Família: `…as 8 da manha e 8 da tarde` → `08:08`; `…as 8 da manha e de noite` → `08:00`.

**Cobertura:** grep por `"da manha e"` / `"e as N"` → **0 hits**. Mutação M8 → suíte verde.

## P0 — "de 8 em 8 horas" com hora dita perde o intervalo CALADO

```
F|tomar remedio de 8 em 8 horas comecando amanha as 8h|kind=NONE time=08:00 amb=false qc=true notes=[]
F|tomar remedio a cada 12 horas comecando amanha as 8h|kind=NONE time=08:00 amb=false qc=true notes=[]
F|tomar remedio a cada 8 horas comecando hoje as 14h|kind=NONE time=14:00 amb=false qc=true notes=[]
```

**Hoje sai:** `kind=NONE` (única), `ambiguous=false`, `qc=true`, **`notes=[]`** — salva
**um** lembrete às 08:00. O "de 8 em 8 horas" some por inteiro.

**Devia sair:** ambíguo — que é o desfecho que o app **já dá** quando não há hora dita
(o controle que funciona):

```
F|tomar remedio de 8 em 8 horas|kind=NONE time=null amb=true qc=false
   notes=["de 8 em 8 horas" é um intervalo, não um horário do dia. Diga o horário da primeira dose., …]
```

**Causa:** `LocalTaskParser.kt:629-639` — quando o ramo `INTERVAL` acha um relógio depois,
devolve `clockHit.copy(remaining=…)` **sem propagar `ambiguous=true` nem a nota**.

**Cobertura:** grep por `"comecando"` → **0 hits**. Mutação M2 → suíte verde.

## P0 — "todo dia 5" vira DIÁRIO calado (repetição inventada)

```
F|tomar remedio todo dia 5 as 8h|kind=DAILY date=2026-08-21 time=08:00 amb=false qc=true notes=[]
F|tomar remedio todo dia 31 as 8h|kind=DAILY amb=false qc=true notes=[]
F|tomar remedio todo dia 30 as 8h|kind=DAILY amb=false qc=true notes=[]
```

**Hoje sai:** `kind=DAILY`, `dayOfMonth=null`, `ambiguous=false`, `qc=true`, sem nota —
a tela descreve "Todos os dias".

**Devia sair:** `MONTHLY dayOfMonth=5` (o dia 5 de cada mês), ou no mínimo ambíguo. É
exatamente como se registra remédio mensal de pressão ("todo dia 5").

**Causa:** `LocalTaskParser.kt:555` — a regex
`daily = \b(?:todos?\s+os\s+dias|todo\s+dia|diariamente)\b` casa `todo dia` e consome o `5`
junto do título antes de qualquer ramo mensal. O ramo mensal (`:501`) só dispara com o
"do mês" explícito.

**Nuance honesta — é doutrina disputada, não acidente:** `LocalTaskParserTest.kt:553-559`
(`todoDiaComNumeroSemMesContinuaDiario`) **afirma `DAILY` de propósito**, e o comentário
revela que uma versão anterior lia como mensal. Para a usuária-alvo, porém, é a leitura
perigosa: ela pede o dia do mês e o app toca todo dia. O que é indiscutível é o **desfecho
silencioso** (`qc=true`, `notes=[]`) — ele é perigoso nas duas leituras.

**Cobertura:** o teste acima **trava o defeito**. Mutação M3 → suíte verde.

## P0 — "toda primeira segunda do mês" vira TODA SEGUNDA calado

```
F|toda primeira segunda do mes tomar remedio as 8h|WEEKLY[MONDAY] date=2026-08-24 amb=false qc=true
   title=«Primeira tomar remedio» (o ordinal vaza no título)
F|toda ultima sexta do mes tomar remedio as 8h|WEEKLY[FRIDAY] amb=false qc=true
```

**Hoje sai:** `WEEKLY[segunda]`, `qc=true`, sem nota, `desc=«Toda segunda»` — e o
"Primeira" vaza no **título**. Toca **toda semana** em vez de uma vez por mês.

**Causa:** `LocalTaskParser.kt:580-595` — o ramo `\btoda\s+` extrai os dias da semana e
**descarta o resto** (`remaining.replaceRange(...)` com `stripWeekDays(after)`), sem olhar
o `primeira`/`ultima`.

**Cobertura:** grep por `primeira (segunda|sexta)` nos testes → **1 hit, e é um comentário**
(`LocalTaskParserTest.kt:1947`). Mutação M5 → suíte verde.

## P1 — repetição sinalizada, mas o dia/período descartado

```
F|tomar remedio todo santo dia as 8h|kind=NONE amb=false qc=false miss=[DATE]
   title=«Tomar remedio todo santo dia»   ← perde a recorrência E deixa a frase no título
F|tomar remedio de segunda a sexta as 8h|kind=NONE amb=true qc=false miss=[DATE]
F|tomar remedio segunda, quarta e sexta as 8h|kind=NONE amb=true qc=false miss=[DATE]
```

- `todo santo dia` é uma das formas naturais: `:555` só conhece `todo dia` — a recorrência
  se perde **por completo**.
- `de segunda a sexta` e `segunda, quarta e sexta` (a vírgula impede o ramo `toda`): os
  dias saem do texto e viram **nenhuma data**. Note que `toda segunda, quarta e sexta`
  **funciona** (`WEEKLY[MON,QUA,SEX] qc=true`) — é a ausência do "toda" que quebra.
- **Cobertura:** grep por `"de segunda a"` / `"segunda, quarta"` / `"santo dia"` → **0 hits**.
  Mutações M1 e M7 → suíte verde.

## P1 — perda de repetição calada (sem ambiguidade, sem nota)

| Fala | Sai hoje | Devia sair | Causa |
|---|---|---|---|
| `dia 5 de todo mes as 8h` | `kind=NONE date=2026-09-05 qc=true notes=[]` | MONTHLY dia 5 | `:470` só casa `de cada mes` |
| `dia 10 de todo mes as 10h` | `kind=NONE qc=true notes=[]` | MONTHLY dia 10 | `:470` |
| `todo dia menos domingo as 8h` | `kind=DAILY qc=true notes=[]` (título "…menos domingo") | semanal exceto domingo / ambíguo | `:555` ignora a exclusão |
| `todo dia exceto sabado e domingo as 8h` | `kind=DAILY qc=true notes=[]` | idem | `:555` |
| `todo dia tirando domingo as 8h` | `kind=DAILY qc=true notes=[]` | idem | `:555` |
| `todo mes as 10h` | `kind=NONE notes=[Falta a data…]` | mensal no mesmo dia | `:481` exige número após "todo mes"; `:561` casa "todo " mas não vê dia |
| `mensalmente as 10h` | `kind=NONE notes=[Falta a data…]` | MONTHLY | nenhum ramo cobre "mensalmente" |
| `por 7 dias` / `durante uma semana` | `kind=NONE`, sem campo de fim, sem nota | fim de série (ou nota) | não há campo de fim no modelo |

O `dia 5 de todo mes` crava a data certa (05/09) mas **não repete** — em março o remédio
não toca. É a mesma classe da "repetição que se perde calada".

## P2 — dia do mês impossível / ano

```
F|tomar remedio todo dia 0 do mes as 8h|kind=MONTHLY:dia0 amb=true notes=[A recorrência ficou ambígua.]
F|tomar remedio todo dia 30 de fevereiro as 8h|kind=YEARLY:dia30:mes2 date=2027-02-28 amb=true notes=[…]
```

- `dia 0`: o guard `day !in 1..31` **deixa passar 0** — marca ambíguo, mas ainda constrói a
  regra `MONTHLY dayOfMonth=0`, que `describePtBr()` renderiza como "Todo dia 0 do mês".
  Ambíguo salva, mas a regra sem sentido existe.
- `30 de fevereiro`: `YEARLY 30/02` é aceita e o motor a **arredonda para 28/02**
  (`RecurrenceEngine.clampToValidDate`, `:139-153`) — a série existe num dia que ela não
  disse, e `isCoherent` (`Models.kt:45-50`) retorna `true` porque só olha `1..31`/`1..12`.
  O `describePtBr` diz "Todo 30 de fevereiro" — **nota mentirosa de renderização**.
- **Cobertura:** M3 e M6 → verdes; nenhum teste cobre `todo dia 0`/`30 de fevereiro` como
  **recorrência** (só como data avulsa, `:2176`).

## O que NÃO foi achado (o que sustenta a conclusão)

- **Nenhuma nota mentirosa no eixo de recorrência.** Cada nota emitida foi verificada:
  todas descrevem o que de fato aconteceu. O defeito aqui é **nota ausente**, não nota
  falsa. O padrão de "nota mentirosa" já visto duas vezes neste repo não reaparece.
- **"de 8 em 8 horas" sozinho está OK** (sem hora dita): `time=null, amb=true, qc=false`,
  com nota — é o controle que funciona.
- **Corretos:** `todo dia 15 do mês`, `todo mês no dia 10`, `dia 5 de cada mês`,
  `nos dias úteis`, `toda semana na terça`, `toda segunda e quarta`.
- **Nenhuma repetição inventada "do nada"** — a inventada é sempre **troca** de cadência
  (mensal→diária, mensal→semanal), nunca criação a partir de fala sem repetição.

## Método e prova por mutação

8 mutações, uma por execução, restauradas por `cp` (nunca `git checkout --`), sha conferido
após cada uma. sha pristine do parser:
`cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34` (2331 linhas).

| # | Mutação | Suíte `:domain` |
|---|---|---|
| M1 | `santo dia` no ramo daily | **439 / 0 / 0** |
| M2 | intervalo-com-hora → ambíguo | **439 / 0 / 0** |
| M3 | `dia N de todo mes` → mensal | **439 / 0 / 0** |
| M4 | `menos/exceto/tirando` → ambíguo | **439 / 0 / 0** |
| M5 | `primeira/ultima N do mes` → ambíguo | **439 / 0 / 0** |
| M6 | `todo mes`/`mensalmente` → MONTHLY | **439 / 0 / 0** |
| M7 | `de segunda a sexta` → WEEKLY range | **439 / 0 / 0** |
| M8 | segunda hora do dia → ambígua | **439 / 0 / 0** |

Todas deixaram **a mesma contagem verde** → nenhum dos 8 defeitos é pego pela suíte.

## Não confirmado

- **Não rodou em aparelho/emulador.** O `canQuickConfirm=true` é lido do predicado real
  (`ParsedTaskDraft.canQuickConfirm`), não observado numa tela; o caminho de gravação
  (um rascunho → uma `TaskSeries`) não foi exercitado.
- **A IA real não foi chamada** — o que se mediu do `HybridParser` foi o caminho offline
  (`isAiEnabled=false`). Não se sabe o que o modelo faz com "toda primeira segunda do mês".
- **O `SpeechIntentClassifier` não foi medido** — roda antes do parser e poderia desviar
  alguma fala.
- Dois dos "defeitos" (`todo dia 5` e `às 8 e às 20`) tocam **decisões de produto já
  gravadas em teste** — sinalizados como disputa de doutrina, não bug inequívoco. O que é
  inequívoco nos dois é o **desfecho silencioso**.
