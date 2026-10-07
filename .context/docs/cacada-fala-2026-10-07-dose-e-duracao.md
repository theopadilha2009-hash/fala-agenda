# Caçada — dose, frequência e duração do remédio (2026-10-07)

Quarta leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06-novos.md` e `cacada-fala-2026-10-07-correcao.md`. Esta mede um
eixo que nenhuma das outras tocou: **como ela fala de um remédio — dose, frequência e
duração** — que é literalmente o antibiótico ("de 8 em 8 horas por 7 dias").

Medições em cópia descartável (`/tmp/caca-dose-rem`, base `106a5e6`), parser real
(`LocalTaskParser`) e `HybridParser`, com `FixedAppClock` em `2026-08-20 10:00
America/Sao_Paulo` (sexta). Espaço cartesiano: dose × frequência × duração × base da
tarefa.

## Manchete

**O app não tem onde guardar dose, frequência nem duração — e a informação some em
silêncio.** O modelo não tem campo para nenhuma das três (`ParsedTaskDraft`,
`RecurrenceRule`, `TaskSeries`, e o schema da IA). Medido: **540 de 540** frases de
frequência viram `recurrence=NONE`, e **504 de 504** frases de duração ficam sem nenhum
campo de fim.

O caso do antibiótico ("de 8 em 8 horas por 7 dias") **não é detectado como frequência**:
o intervalo é reconhecido e a hora fica nula/ambígua, então ela nunca chega a salvar; e
quando ela reformula sem o "de" — **"8 em 8 horas"** — o parser crava `time=18:00`,
`qc=true`, **um** lembrete por dia, sem aviso. A diferença entre "certo" (guardado) e
"errado em silêncio" é uma palavra.

## P0 — não existe campo para dose/frequência/duração

Medido por leitura do modelo (não há onde gravar):

- `ParsedTaskDraft` (`Models.kt:56-69`): `title, localDate, localTime, recurrence,
  confidence, missingFields, ambiguous, transcript, notes, source, amountCents,
  observation`. Nada de dose/frequência/duração.
- `RecurrenceRule` (`Models.kt:19-24`): `kind(NONE|DAILY|WEEKDAYS|WEEKLY|MONTHLY|YEARLY),
  weekDays, dayOfMonth, monthOfYear`. Não expressa "3×/dia" nem fim de tratamento.
- `TaskSeries` (`Models.kt:115-136`): **um** `localTime`. Uma série = um horário por dia.
- Schema da IA (`supabase/functions/_shared/openai.ts:1-37`): mesmos campos, `strict`,
  `additionalProperties:false`. O teto do que a IA pode devolver **não tem** dose,
  frequência nem duração.
- `LocalTaskParser` nunca preenche `amountCents` (só o merge o lê); o remoto também não
  (schema sem o campo) → `amountCents` fica sempre `null` vindo da fala.

Por que importa pra ela: um antibiótico de 8 em 8 horas por 7 dias **não tem
representação**. No melhor caso o app guarda 1 lembrete/dia; no pior, crava 18:00 calado.

## P0 — "8 em 8 horas" (sem o "de") vira 18:00 e salva calado

```
CONFIRM|«tomar remedio 8 em 8 horas»|title=«Tomar remedio»|date=2026-08-20|time=18:00|rec=NONE|amb=false|qc=true|missing=[]|amount=null|obs=«»|notes=[]
```

O número 8 é lido como hora, o período "horas" aplica +12 → 18:00. `ambiguous=false`,
`canQuickConfirm=true`. Controle que funciona ao lado:

```
CONFIRM|«tomar remedio de 8 em 8 horas»|title=«Tomar remedio»|date=null|time=null|rec=NONE|amb=true|qc=false|missing=[DATE, TIME]|notes=[“de 8 em 8 horas” é um intervalo, não um horário do dia. Diga o horário da primeira dose., ...]
```

A guarda `INTERVAL` (`LocalTaskParser.kt:1030-1033`) exige `de N em N <unidade>` ou
`a cada N <unidade>`. "8 em 8 horas" — que é como se fala — passa por fora. Se ela tocar
"Salvar" (1 toque), o app agenda **hoje 18:00**, uma vez, para um remédio de 8/8h. Não há
nota; nada na caixa rápida revela a troca.

## P1 — frequência é detectada mas nunca modelada; "três vezes por dia" nem é detectada

Oráculo 2 (base com hora dita, rascunho completo):

```
PROBE-RESUMO2|espaco|casos=630
PROBE-RESUMO2|frequenciaDitaMasRecurrenciaNONE=540
PROBE-RESUMO2|duracaoDitaSemCampoDeFim=504
```

**540/540** frases de frequência → `recurrence=NONE`. "de 8 em 8 horas" gera só a *nota*
de ambiguidade (não vira campo); "três vezes por dia" e "duas vezes ao dia" **nem isso**:

```
PROBE-FREQ|frase=«tomar remedio tres vezes por dia»|title=«Tomar remedio tres vezes dia»|time=null|rec=NONE|amb=false|notes=[Falta a data..., Falta o horário...]
PROBE-FREQ|frase=«tomar remedio duas vezes ao dia»|title=«Tomar remedio duas vezes dia»|time=null|rec=NONE|amb=false|notes=[...]
```

"vezes"/"dia" vazam para o título; nenhuma nota de frequência. Controle que funciona:
`"tomar remedio todo dia às 8"` → `rec=DAILY time=08:00 qc=true`.

## P1 — duração não vira fim e suja o título ("por 7 dias" → título "...dias")

```
PROBE-ANTIB|frase=«tomar o antibiotico de 8 em 8 horas por 7 dias»|title=«Tomar antibiotico dias»|date=null|time=null|rec=NONE|amb=true|qc=false
PROBE-QC|frase=«tomar remedio por 7 dias amanha as 8»|title=«Tomar remedio dias»|qc=true|rec=NONE|notes=[]
PROBE-RECDUR|frase=«tomar remedio todo dia as 8 por 7 dias»|title=«Tomar remedio dias»|rec=DAILY|date=2026-08-21|time=08:00|qc=true|notes=0
```

O "por 7 dias" **não corrompe data nem hora** (bom — hipótese descartada, ver "Medido e
OK"), mas também não vira fim de série: nenhuma nota, e o "dias" fica no título. Em
`todo dia às 8 por 7 dias` a série **repete para sempre** — sem campo de fim, ela nunca
encerra sozinha.

## P2 — "meia dose" perde o "meia" no título (vira "dose")

```
PROBE-DOSE|frase=«tomar remedio meia dose»|title=«Tomar remedio dose»|...
PROBE-DOSE|frase=«tomar remedio meio comprimido»|title=«Tomar remedio meio comprimido»|...
```

"meia" está em `FILLERS` (`LocalTaskParser.kt:1230`); "meio" não está. Assimetria: "meia
dose" → "Tomar remedio **dose**" (meia e um → não; a dose vira "a dose"), "meio comprimido"
preserva. Em `um comprimido e meio` o título sai `«Tomar remedio comprimido meio»`.

## P2 — outras formas de intervalo com vazamento (todas com hora dita a hora some)

```
PROBE-FREQ+TIME|frase=«tomar remedio as 8 de 8 em 8 horas»|time=null|amb=true|notes=[“de 8 em 8 horas” é um intervalo..., Falta a data..., Falta o horário...]
PROBE-COLOQ|frase=«tomar remedio de oito em oito horas»|time=null|amb=true
PROBE-COLOQ|frase=«tomar remedio a cada oito horas»|time=null|amb=true
```

Quando ela **diz a hora E o intervalo** ("às 8 de 8 em 8 horas"), o intervalo **descarta a
hora dita** (`time=null`) — o intervalo curto-circuita antes do relógio
(`LocalTaskParser.kt:352-361`). Ela nunca chega a salvar, mas o app não guarda nem a hora
nem a frequência. A frase completa "a partir das 8 de 8 em 8 horas" vaza "partir" no título.

## Oráculo (output colado)

**Oráculo 1** — base sem hora (3 bases × 7 doses × 7 freqs × 8 durs = 1176). Referência
independente: contagem esperada de doses/dia por frequência e regex própria de "tem hora
dita" (não a do app).

```
PROBE-RESUMO|espaco|casos=1176|bases=3|doses=7|freqs=7|durs=8
PROBE-RESUMO|invFREQ|casosDosesDiaMaiorQue1=1008|salvaCaladoCom1Lembrete=0|recurrenciaNONE(naoRepete)=1008|temNotaIntervalo=672
PROBE-RESUMO|invDOSE|casosComDose=1008|doseSumiuSemAviso=0
PROBE-RESUMO|invDUR|casosComDuracao=1029|duracaoSumiu=0|numeroDaDuracaoVirouHora=0
PROBE-RESUMO|invPHANTOM|casosSemHoraDita=168|horaInventada=0
PROBE-RESUMO|invINTERVAL|casosHoraDitaComIntervalo=0|horaEngolidaPeloIntervalo=0
```

Leitura: das **1008** combinações com frequência, **1008** viram `NONE` (nenhuma repete o
que ela disse); só **672** (as formas "de N em N"/"a cada N") ganham a nota de intervalo.
O oráculo de "hora fantasma" (168 casos) e "duração virou hora" (0) saíram **limpos** — as
hipóteses do enunciado sobre `por 7 dias` → 07:00 e sobre o dinheiro **não se confirmaram**
(ver "Medido e OK"); o defeito real está no buraco do modelo e na guarda de uma palavra.

**Oráculo 2** — base com hora dita (3 bases × 6 doses × 7 freqs × 5 durs = 630):

```
PROBE-RESUMO2|espaco|casos=630
PROBE-RESUMO2|rascunhoCompleto=270
PROBE-RESUMO2|quickConfirmTrue=270
PROBE-RESUMO2|qcTrueEComFrequenciaDita=180
PROBE-RESUMO2|qcTrueEComDuracaoDita=216
PROBE-RESUMO2|qcTrueEComDoseDita=225
PROBE-RESUMO2|frequenciaDitaMasRecurrenciaNONE=540
PROBE-RESUMO2|duracaoDitaSemCampoDeFim=504
```

**270 de 630** chegam a rascunho completo com `canQuickConfirm=true` — e **180** desses
têm frequência dita, **216** têm duração dita, **225** têm dose dita. Ou seja: a caixa
"Pode salvar?" abre pronta (1 toque) em frases que carregam dose/frequência/duração — e
**não renderiza nenhuma das três** (`QuickConfirmDialog.kt`: título, `promise.recap`,
`droppedChoice`, `amountCents`, `observation` — nada de dose/freq/duração; o campo
`observation` que poderia carregá-las o parser deixa vazio: `obs=«»` em todas as sondas).

## Medido e OK (não refazer)

- **"de N em N horas" e "a cada N horas" são detectados** (4/4): `"de 8 em 8 horas"`,
  `"a cada 8 horas"`, `"de 12 em 12 horas"`, `"de 6 em 6 horas"` → `time=null`,
  `ambiguous=true`, `qc=false`, com a nota "é um intervalo, não um horário do dia". A
  guarda `INTERVAL` funciona quando a frase tem o "de"/"a cada".
- **"por 7 dias" NÃO vira 07:00** (hipótese descartada): `numeroDaDuracaoVirouHora=0` em
  1029 casos; `"por 7 dias"` → `time=null`.
- **"meio comprimido" NÃO vira dinheiro**: `amountCents=null` em 100% das sondas. O parser
  não tem léxico de reais; `Money.parseReais` só roda sobre o campo digitado na tela de
  confirmação (`ConfirmDraftScreen.kt:292,371`), nunca sobre a fala. `MEIO_MIL_ESCALA` e
  `conectorSemCentavos` **não existem nesta base** — só num worktree não mergeado
  (`fala-valores-e-faixas`).
- **Sem hora fantasma** quando ela não diz hora e não diz frequência: `horaInventada=0`
  em 168 casos.
- **"em 3 horas"** (relativo) resolve certo: `"tomar remedio em 3 horas"` → `time=13:00`,
  `qc=true`.
- **Controle limpo**: `"tomar remedio amanhã às oito"` → `date=2026-08-21 time=08:00
  qc=true`; `"tomar remedio todo dia às 8"` → `rec=DAILY time=08:00 qc=true`.

## Não confirmado

- **A IA real (OpenAI) sobre dose/frequência/duração**: não medida (sem rede/chave). O que
  se mede é o **teto do schema** (`openai.ts`): como não há os campos, a IA **não pode**
  devolvê-los. Chamada real não executada.
- **O disparo de alarme real** (`AlarmManager`) para uma série de 3×/dia: não exercitado.
  Inferido do modelo (`TaskSeries` tem um `localTime` só).
- **A renderização da caixa rápida** (truncamento do título longo "…de 8 em 8 horas por 7
  dias"): não medida — sem render Compose nesta caçada.
- **PR #75** (adiciona `draft.notes` à `QuickConfirmDialog`): não está nesta base; não medido.

## PENDENTE (decisão de produto)

1. Criar campo de **dose/frequência/duração** (ou múltiplos `localTime` por série) — sem
   isso o antibiótico é inexprimível.
2. Decidir se **"8 em 8 horas"** (sem o "de") deve entrar na guarda `INTERVAL` — hoje ele
   crava 18:00 calado.
3. Decidir se a **nota de intervalo** deve aparecer na caixa rápida (hoje a caixa não
   renderiza `draft.notes`; a nota só existe na tela de confirmação).
4. O léxico de dinheiro (`MEIO_MIL_ESCALA`) existe só em worktree não mergeado; se for
   mergeado, decidir se "meio comprimido" pode colidir com "meio mil".
