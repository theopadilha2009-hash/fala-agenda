# Caçada — A TELA DE FORA: o widget (2026-10-07)

Décima sexta leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

O que ela vê na tela do celular **sem abrir o app** é uma superfície inteira que nenhuma
caçada tinha medido. É o que ela olha ao acordar, antes de qualquer toque.

**Base:** `origin/main` @ `e0b721c`. Cópia descartável (`/tmp/caca-widget`). Rodou o
**pipeline real**: `RemoteViews` **aplicado** (`remote.apply()`) com o conteúdo lido de
`TextView.text`, o `BootCompletedReceiver` de verdade com o `FalaAgendaApplication` (Room
real), o `ReminderActionReceiver`, o `AlarmScheduler` real com `ShadowAlarmManager`, e o
`collectWidgetUpdates` acoplado ao repositório real. **7 eixos, ~30 estados, 3 mutações de
produção + 1 controle negativo.**

## O eixo dos três P1: o widget mostra a hora da SÉRIE, não a do AVISO

`AgendaWidgetProvider.kt:102-104` (filtro/ordenação só olha `scheduledAt`) e `:114`:

```kotlin
whenLabel = "${AgendaFormat.dateLabel(next.occurrence.localDate, today)} · ${AgendaFormat.time(next.series.localTime)}"
```

A **escolha** de qual tarefa mostrar vem de `occurrence.scheduledAt`; o rótulo de **hora**
vem de `series.localTime`. Os dois divergem por desenho em três situações — e nas três o
widget mente sobre a hora do próximo aviso.

### P1-a — a edição de uma dose de outra data

O contrato `EditarDoseDaSerieNaoPerdeAsOutrasTest` prende: editar a dose de **amanhã**
preserva o `scheduledAt` das doses que ela não tocou, enquanto a **série** passa a carregar
o horário novo. Medido (relógio fixo às 07:00; série "Remédio" todo dia às 08:00, doses de
hoje..+3 armadas; ela edita a de **amanhã** para as 14:00):

```
DEVE aparecer:  (Próxima, Remédio, Hoje · 08:00)   ← o alarme de hoje, setAlarmClock 08:00
APARECE:        (Próxima, Remédio, Hoje · 14:00)
Alarme real entregue ao AlarmManager: 2026-10-07T08:00-03:00[America/Sao_Paulo]
```

**Cenário:** ela está às 7h, olha o widget para saber a que hora tomar o remédio. O widget
diz 14:00. O alarme toca às 8h. Ela perde a dose ou toma duas.

### P1-b — o adiamento

O `snooze` grava `nextReminderAt`/`snoozedUntil` e **não toca** em `scheduledAt` nem em
`series.localTime` — e o widget não lê nenhum dos dois. Medido: às 08:00 ela toca
"Adiar 30 min" (`scheduledAt=08:00`, `snoozedUntil=08:30`, `nextReminderAt=08:30`):

```
DEVE aparecer:  hora 08:30
APARECE:        (Próxima, Tomar remédio, Hoje · 08:00)
```

**Cenário:** ela adia para 08:30, olha o widget, vê "Hoje · 08:00", conclui que já passou e
toma duas vezes — ou não toma.

### P1-c — o silêncio noturno

Mesma linha, com o silêncio do `ReminderPolicy` (`TaskRepository.fire`). Medido: remédio às
22:00, silêncio padrão (22:00–08:00). O primeiro disparo entrega; a repetição seguinte cai
no silêncio e é deslocada:

```
reminderStep=1, nextReminderAt=2026-10-08T08:00, scheduledAt=2026-10-07T22:00
DEVE aparecer:  a hora do aviso que vai tocar (08:00 de amanhã)
APARECE:        (Próxima, Tomar remédio, Hoje · 22:00)   ← "Próxima" num instante já passado
```

**Cenário:** ela vai dormir às 22:00, olha o widget, vê "Próxima — Tomar remédio — Hoje ·
22:00". Acha que o app vai avisar às 22:00. O aviso só chega às 08:00 do dia seguinte — e a
tela de fora mentiu a noite inteira.

### P2 — sem nada à frente, o widget fica preso em "Atrasada"

Quando a próxima ocorrência pendente é a **de amanhã** (o `DailySweep` ainda não rodou), o
widget escolhe pelo `scheduledAt` e a pendente de **hoje**, já vencida, ganha da de amanhã.
Fica em "Atrasada" enquanto a próxima dose real está a poucas horas.
`AgendaWidgetSnapshotTest.atrasadaQuandoNaoHaNadaAFrente` cobre o caso simples; a interação
com a ocorrência de amanhã já materializada **não é medida**.

## Medido e OK (não refazer)

| Eixo | Medição | Veredito |
|---|---|---|
| 1 — atualização por escrita | `collectWidgetUpdates` + repositório real: criar → `Próxima/Vitamina/Hoje · 23:30`; concluir → `Agenda/Nada marcado`; criar outra → `Próxima/Cabelo`; adiar → repinta; editar → `Cabelo no salão`; apagar → `Agenda/Nada marcado` | **as seis escritas repintam**; `AgendaWidgetSyncTest` prende a retomada e o tema |
| 2 — reboot | `BootCompletedReceiver` real + Application real + Room: `BOOT_COMPLETED` → `(Próxima, Tomar remédio, Hoje · 23:50)`; `MY_PACKAGE_REPLACED` → `(Próxima, Cabelo, Hoje · 23:50)` | OK |
| 3 — vazio | `(Agenda, Nada marcado, Toque para abrir a agenda)`; falha de leitura: `(Agenda, Não consegui ler a agenda, Toque para abrir o app)` | explícito e **distinguível** |
| 4 — toque | o widget **não usa lista remota nem `setPendingIntentTemplate`** — não existe índice obsoleto por desenho. Cartão → `MainActivity`/`ACTION_MAIN` (`requestCode=1`); "Falar" → `ACTION_SPEAK` (`requestCode=2`) | sem colisão |
| 5 — hora, `Clock` fixo | 07:59 com dose 08:00 → `Hoje · 08:00`; 08:00 → `Próxima`; 08:01 → `Atrasada`; meia-noite com dose de ontem → `Atrasada · Ontem · 08:00` | limiares OK — o defeito está na **divergência**, não no limiar |
| 6 — o que repinta | `updatePeriodMillis=1800000` (30 min, o mínimo do Android) + `collectWidgetUpdates` (toda escrita) + `refreshNow` no `DailySweepReceiver` e no boot | OK, com a dependência abaixo |
| 7 — datas | `Hoje`/`Amanhã`/`Ontem`; longe, `dd/MM` (`09/10`, `06/11`); hora `HH:mm` (`08:00`, `00:00`, `23:59`); virada do dia muda o rótulo | OK |

**Dependência do eixo 6 (registrar):** o fluxo de repintura é o **banco**. Quando o aviso é
`BLOCKED` (permissão negada / canal desligado), o banco não muda e o widget **não repinta** —
medido: `banco antes=[(s1, PENDING)]` → `depois do aviso BLOQUEADO=[(s1, PENDING)]` →
`o banco mudou? false`.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| Run | Mutação | Total | Falhas |
|---|---|---|---|
| Controle negativo | hora → `"00:00"` | 1274 | **1** (`AgendaWidgetSnapshotTest.mostraProximaDeHoje`) |
| **MUT-A** | hora ← instante real do próximo aviso | 1274 | **0 — invisível** |
| **MUT-B** | ordenar/filtrar por `nextReminderAt` | 1274 | **0 — invisível** |
| Restaurado | `cp` + `sha256` | 1277 | 0 |

O controle negativo mata 1 teste, então a suíte não é cegamente verde. As **duas mutações
que consertam o P1 são invisíveis** — nenhum dos 1274 testes prende que a hora mostrada é a
do aviso que vai tocar. Essa é a medida da cegueira do eixo.

## Não confirmado

- **Launcher real.** O Robolectric não tem launcher: o `RemoteViews` foi aplicado e lido em
  processo, mas quem resolve `targetCellWidth`/`targetCellHeight`, o tema do host e o
  `reapply` parcial é o launcher do aparelho (Android 13+). `WidgetFonteTest` mede a
  geometria inflando o layout, não o que o launcher entrega.
- **Reboot real.** Simulou-se o broadcast com o `Application` real; não se simulou o launcher
  re-inflando o widget com o processo morto, nem a ordem entre `BOOT_COMPLETED` e o primeiro
  `onUpdate`.
- **Doze / bateria** — não se mediu o atraso real de entrega do `AlarmManager`.
- **`goAsync()` fora de dispatch real** — no Robolectric devolve `null` e o `onUpdate` estoura
  NPE (não é defeito de produção; o sistema sempre dá um `PendingResult`). Contornado
  chamando `refresh`/`onReceive` direto.
- **O P2** foi medido só pelo `snapshotOf` com seções construídas à mão.
