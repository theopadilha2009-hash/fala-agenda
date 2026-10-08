# Caçada — A SEGUNDA PORTA: editar a tarefa que já existe (2026-10-08)

Décima sétima leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

Todas as caçadas anteriores mediram a **primeira porta**: ela fala e o app **cria** algo. A
tarefa que já existe tem outra porta — ela abre o cartão, corrige, salva. Essa porta tinha
sido tratada só de lado, por um contrato de teste
(`EditarDoseDaSerieNaoPerdeAsOutrasTest`) escrito para um caso específico. Ninguém tinha
varrido **o que a edição destrói**.

**Base:** `origin/main` @ `55f7448`. Cópia descartável (`/tmp/caca-porta2-edicao-9f3a`).
**Room real**, `ReminderScheduler` real com `ShadowAlarmManager` (mede o instante entregue ao
`AlarmManager`, não a intenção), `Clock` fixo, 4 sondas. **34 estados**, **5 mutações** com
contagem dos XMLs.

## A raiz dos quatro achados: o `materialize` do cartão editado é chamado SEM `existing`

`TaskRepository.kt:769` chama `OccurrenceLifecycle.materialize` para a ocorrência tocada sem
passar a linha que já existia. O que a linha carregava — `status`, `completedAt`,
`nextReminderAt`, `snoozedUntil`, `reminderStep`, `lastReminderAt` — é descartado e
reconstruído do zero a partir da série. Três dos quatro achados são o mesmo descarte visto de
ângulos diferentes.

## P0 — a dose que ela TOMOU vira "não realizada": o registro some

Série única "Remédio" 08:00, hoje 20/08, relógio 10:00. Ela toca **Concluir**. Depois abre a
tarefa e **corrige o horário para 09:00**:

```
DEVE:     a linha continua COMPLETED, com completedAt preservado.
ACONTECE: antes  = COMPLETED completedAt=2026-08-20T13:00:00Z
          depois = MISSED    completedAt=null missedAt=2026-08-20T13:00:00Z
          alarme = null
```

O `completedAt` é zerado, o `missedAt` recebe o `now`, e a linha sai de **Concluídas** e entra
em **Não realizadas** — o app passa a acusar a mãe de não ter tomado o remédio que ela tomou.
Sem alarme, porque MISSED não carrega nenhum.

**`arquivo:linha`:** `TaskRepository.kt:772-776` — o `expired` arquiva a escolha vencida **sem
perguntar se ela já estava COMPLETED**. O único caminho que preservaria é o fast path de
`TaskRepository.kt:379` (`finished && sameWhen && recurrence == series.recurrence`), que exige
data, hora **e** regra idênticas — mudar qualquer campo sai dele.

**Cobertura: zero.** `editarConcluidaSoValorNaoDesfaz` (`TaskRepositoryTest.kt:78`) edita para
a **mesma data e o mesmo horário**, cai no fast path e **nunca chega no `expired`**. A mutação
**M2** (o conserto candidato) deixa a suíte oficial verde: `app 761 / domain 500`, 0 falhas.

**Cenário:** ela toma o remédio das 8h e toca "Concluir". À noite lembra que o médico mudou
para as 9h e corrige. O app apaga o registro e passa a mostrar "Não consegui avisar" para a
dose que ela tomou.

## P1 — a dose que já tocou ganha um SEGUNDO alarme

Rotina "Remédio" todo dia 08:00. O aviso de hoje **já tocou** (`lastReminderAt=11:00Z`),
relógio em 10:00. Ela corrige a dose de **amanhã** para as 14:00 (a série passa a valer 14:00).
Depois abre o cartão de **hoje** — que a tela mostra às 14:00 (ver o P1 seguinte) — e corrige
**só o título**:

```
DEVE:     nada é armado; o aviso de hoje já tocou.
ACONTECE: série localTime=14:00
          alarme 20/08 antes  da edição de título = 2026-08-20T11:00:00Z
          alarme 20/08 depois                        = 2026-08-20T17:00:00Z
          20/08 status=PENDING lastReminderAt=2026-08-20T11:00:00Z
```

**`arquivo:linha`:** `TaskRepository.kt:769` → `OccurrenceLifecycle.kt:78`
(`else -> first.fireAt`). **Cobertura: não coberto.** A mutação **M5** (preservar o progresso do
cartão tocado com `existing = previous`) deixa a suíte oficial **verde** — `app 761 / 0 falhas`,
`domain 500 / 0 falhas`; as 4 falhas foram só da sonda.

**Cenário:** ela é avisada às 8h, toma o remédio, e à noite corrige o horário da série para as
14h. No dia seguinte o celular apita às 14h para a dose que ela já tomou.

## P1 — a tela mostra um horário e o alarme toca outro

Rotina 08:00. Ela corrige a dose de amanhã para as 14:00 — a série passa a valer 14:00 e a dose
de hoje continua armada às 08:00, **de propósito**:

```
DEVE:     o horário que o cartão mostra para a dose de hoje é o horário em que o alarme dela toca.
ACONTECE: tela diz 14:00, alarme toca 08:00
          rascunho localTime=14:00 (o que a tela mostra)
          alarme real=2026-08-20T11:00:00Z
```

O cartão (`HomeScreen.kt:1153`, `AgendaFormat.time(item.series.localTime)`) e o rascunho da
edição (`FalaAgendaRoot.kt:176`, `localTime = item.series.localTime`) leem o horário da
**série**, não o da **ocorrência**.

**Cobertura: não coberto.** `doseDeHojePreservadaPelaEdicaoMantemOHorarioDepoisDoRestart`
(`EditarDoseDaSerieNaoPerdeAsOutrasTest.kt:294`) prende o **alarme** de hoje, mas nenhum teste
confere o horário **mostrado**.

**Cenário:** a mãe olha a lista, lê "Remédio, hoje · 14:00", e o remédio toca às 8h. Ela perde
a dose — ou toma duas. **É o mesmo defeito do widget** (`cacada-fala-2026-10-07-widget.md`,
P1-a/b/c): a escolha vem de um campo, o rótulo de outro. A caçada do widget fechou a superfície
de fora; esta mostra que a **tela de dentro** tem o mesmo defeito.

## P1 — a tela promete uma data que o app não grava (e a criação do mesmo caso não promete)

Série diária, ela abre a dose de 22/08 (um sábado) e escolhe "Toda semana" + quinta:

```
DEVE (é o que a CRIAÇÃO do mesmo caso diz):
  "Vai avisar Quinta-feira, 27 de agosto de 2026 às 08:00. Toda quinta."
  + "Você escolheu 22/08, e 'Toda quinta' não cai nesse dia: o primeiro aviso é Quinta-feira, 27..."

ACONTECE (edição):
  recap   = "Vai avisar Sábado, 22 de agosto de 2026 às 08:00. Toda quinta."
  dropped = null
  label   = "Salvar · 22/08 08:00"

ACONTECE (criação):  recap = "...Quinta-feira, 27..."  dropped = "Você escolheu 22/08, e 'Toda quinta' não cai..."
```

"Sábado, 22 de agosto" e "Toda quinta" na mesma frase. O dado **gravado** está certo (a edição
mantém a data tocada e a série segue dela, que é o contrato documentado) — é só a **frase**.

**`arquivo:linha`:** `AgendaFormat.kt:87-93` — o ramo `if (editing)` usa `plan.date` (a data da
escolha) e só marca `movedBecause` quando `plan.expired`.

**Cobertura: não coberto — e o teste existente prende a frase contraditória.**
`editandoADataEscolhidaEADataQueVale` (`PromessaDaTelaBateComOAgendamentoTest.kt:271`) usa
exatamente WEEKDAYS + sábado e afirma `droppedChoice` **nulo**. A mutação **M4** (a frase da
edição passar a respeitar a regra) deixa a suíte verde.

**Cenário:** ela muda o dia da consulta de sábado para "toda quinta". A tela diz "Vai avisar
Sábado, 22 de agosto ... Toda quinta"; ela salva achando que o aviso é quinta e ele toca sábado.

## P2 — editar só o título apaga o adiamento da PRÓPRIA dose

A dose de amanhã está adiada para as 08:30 (`snoozedUntil=11:30Z`, `reminderStep=3`). Ela abre
**essa** dose e corrige **só o título**:

```
DEVE:     o adiamento sobrevive — ela não mexeu no horário.
ACONTECE: snooze antes=2026-08-21T11:30:00Z  depois=null
          step   antes=3                    depois=0
          alarme depois=2026-08-21T11:00:00Z
```

O adiamento some, o degrau volta a 0 e o alarme volta para as 08:00 — o aviso que ela pediu
para depois toca na hora que ela adiou.

**`arquivo:linha`:** `TaskRepository.kt:769` (mesma raiz). **Cobertura parcial:**
`editarOutraDoseNaoApagaOAdiamentoDaDoseDeAmanha` (`EditarDoseDaSerieNaoPerdeAsOutrasTest.kt:192`)
prende o adiamento quando ela edita **outra** dose; nenhum teste edita a **própria** dose adiada.

**Cenário:** ela toca "Adiar 30 min", depois lembra de corrigir o nome do remédio, abre a mesma
tarefa e muda o título — o aviso volta para as 8h.

## Medido e OK (não refazer)

1. **Mudar o horário reagenda** — `alarme 20/08 = 2026-08-20T23:00:00Z` (20:00 local); as doses
   de 21 e 22 continuam armadas. O alarme velho morre e o novo nasce.
2. **Trocar a recorrência não deixa órfã** — `depois = [(20/08,THURSDAY), (27/08,THURSDAY),
   (03/09,THURSDAY)]`, `ÓRFÃS = []`; o banco fica só com `[2026-08-20, 2026-08-27, 2026-09-03]`.
   E a regra nova materializa os dias que faltavam (semanal→diária: `[20/08, 21/08, 22/08]`).
3. **Alvo falado depois do título novo** — renomear "Remédio" → "Losartana 50mg": `'já tomei a
   losartana' -> One(...)`. Pelo caminho real (classificador + `eleitasPorSerie`): `'já tomei o
   remédio da pressão' -> One(...)`. O título muda em todas as doses ao mesmo tempo. O nome
   antigo deixa de casar (`'já tomei o remédio' -> None`), que é o correto; e o alvo vazio
   devolve `None`, o desfecho seguro.
4. **Valor e observação sobrevivem** — `valor=8000 obs=levar a carteirinha`.
5. **Editar só o título de uma dose concluída de rotina preserva o registro** — `status depois =
   COMPLETED`.
6. **Editar uma não realizada de mesma data** — `status=MISSED lastReminderAt=2026-08-19T11:00:00Z`,
   estado e último aviso intactos.
7. **Cancelar a edição** — `datas iguais = true`. O `onCancel` (`FalaAgendaRoot.kt:445`) só
   limpa `editingItemId` e desce a rota; nada é gravado, nada fica pendurado.
8. **Dose concluída MOVIDA de dia** — `20/08=COMPLETED 21/08=PENDING`: o registro de 20/08 não é
   destruído. Aqui o app acerta — e é a prova de que o P0 é o **caso estreito** (mover a data
   preserva; mover a **hora** na mesma data apaga).

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

Baseline (`55f7448`, produção restaurada): **domain 500 / app 761, 0 falhas, 0 erros.**

| Mutação (uma por execução) | domain | app | falhas | veredito |
|---|---|---|---|---|
| M1 — fast path estendido a pendentes | 500 / 0 | 761 / 0 | **2** | contido, mas por razão lateral |
| M2 — escolha vencida não arquiva o que já foi feito | 500 / 0 | 761 / 0 | **0** | **invisível** |
| M3 — dose já entregue não é rearmada | 500 / 0 | 761 / 0 | **0** | **invisível** |
| M4 — frase da edição respeita a regra | 500 / 0 | 761 / 0 | **0** | **invisível** |
| M5 — cartão tocado preserva o progresso | 500 / 0 | 761 / 0 | **0** | **invisível** |

M1 derrubou `editarNaoRearmaInstanteVencidoHerdadoDaProxima` e
`aEdicaoRecorrenteComTombstoneAnunciaAProximaDataViva` — mas por consequência **lateral** (o fast
path não move `startLocalDate`), não pelo eixo do defeito. M2 e M5 foram provadas **vivas**
rodando as sondas com a mutação aplicada: a sonda cai, a suíte não.

**4 de 5 mutações invisíveis.** As duas que consertam o P0 e o P1 do segundo alarme passam
inteiramente verdes na suíte de 1261 testes. É a medida da cegueira da porta de edição: o
contrato `EditarDoseDaSerieNaoPerdeAsOutrasTest` mede o que ela **não perde** em edições de
outras doses, e nunca mediu o que ela **perde** na própria.

## Não confirmado

- **Ela percebe que a edição pegou** (eixo 6, só anotação): o `edit()` publica o desfecho por
  `DraftSaveOutcome` com `announceOfEdit` (`HomeViewModel.kt:736-742`) e a confirmação navega de
  volta ao consumi-lo (`FalaAgendaRoot.kt:334-349`). Não se mediu a tela — é o eixo da caçada
  do recado.
- **`rescheduleAll` num aparelho real** depois de uma edição: mediu-se o restart só no cenário
  que o teste existente já cobre.
- **Outros consumidores do `scheduledAt` da linha** (que fica no horário antigo): não se varreu
  todos; confirmou-se o cartão e o rascunho da edição.
