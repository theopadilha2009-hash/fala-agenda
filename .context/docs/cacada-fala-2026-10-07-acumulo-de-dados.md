# Caçada — O TEMPO PASSA: acúmulo de dados e o app do sexto mês (2026-10-07)

Décima quinta leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

Todas as caçadas anteriores mediram o app **recém-instalado**, com duas ou três tarefas.
Nenhuma mediu o app **depois de meses de uso real**. O que quebra num app de saúde que roda
todo dia não é o caso de 3 tarefas — é o de 300. E o defeito aparece **quando já é tarde**:
ela não vai reinstalar.

**Base:** `origin/main` @ `e0b721c`. Cópia descartável (`/tmp/fala-cacada-tempo`).
**~40 mil ocorrências** geradas em 12 cenários com Room real + `Clock` avançando
30/180/365/730/1000 dias. **5 mutações** de produção, uma por execução. `sectionsOf`
instrumentado temporariamente para provar thread e custo.

## P0 — a lista cresce para sempre, sem poda e sem janela

`TaskRepository.sectionsOf` (`app/.../data/repo/TaskRepository.kt:117-121`) filtra por
**status**, nunca por data. Não existe cláusula de janela em lugar nenhum do caminho da
home. Volume medido (3 tarefas diárias, uma varredura por dia):

```
MARCO dia=30   ocorrencias=99   pendentes=9
MARCO dia=180  ocorrencias=549  pendentes=9
MARCO dia=365  ocorrencias=1104 pendentes=9
MARCO dia=730  ocorrencias=2199 pendentes=9
```

O que a home renderiza (1 tarefa diária, 180 dias):

```
HOME 180d hoje=1 proximas=2 concluidas=0 naoRealizadas=180 TOTAL=183
```

Concluídas crescem igual (180 dias marcando a dose como feita):

```
CONCLUIDAS apos180d concluidas=181 naoRealizadas=0
  primeira=2026-01-01  ultima=2026-06-30
```

Com banco de 10.030 ocorrências (10 séries diárias, 1000 dias):

```
LEITURA observeAgenda hoje=10 proximas=20 concluidas=0 naoRealizadas=10000
```

O banco em arquivo, 5 séries diárias: `30d=550KB → 180d=862KB → 365d=1222KB → 730d=1918KB`.

**DEVE:** no sexto mês a lista não pode carregar 180 doses de meses atrás — deveria ter
janela, ou seção recolhida/fechada por padrão. **ACONTECE:** todas as 10.000 vão para a
lista. **Cobertura: nenhuma.** Mutação A (`.take(30)` nas seções Concluídas e Não
realizadas) → suíte **verde**, 1261 testes, 0 falhas.

**Cenário:** abrir a home no sexto mês e ter ~180 cartões de "Tomar remédio" já vencidos
empilhados abaixo do de hoje. O "Hoje" está no topo, então o remédio não se perde — o dano
é a lista virar arquivo morto e o custo. **Isto é decisão de produto** (janela? recolher?
purgar?), não de parser.

## P1 — nenhuma política de retenção de ocorrências

Os únicos DELETEs são `WHERE id = :id` e `WHERE seriesId = :seriesId` (`Daos.kt:83`,
`:86`, `:30`). Sem `purge`, `prune`, WorkManager ou worker periódico (grep vazio em
`app/src/main`). O **tombstone tem** teto (`SKIPPED_RETENTION_DAYS = 90`,
`MAX_SKIPPED_DATES = 120`) — é a única coisa com poda. Mutação C (desligar a poda do
tombstone) → **pega**: `OccurrenceLifecycleTest.skipDateGuardaADataEDescartaAsMuitoAntigas`.
A poda que existe está presa; a que não existe não tem teste porque não existe. 1,9 MB em
2 anos é benigno em si — o risco é o eixo tempo × aparelho cheio, não simulado.

## P1 — `sectionsOf` roda na Main Thread sobre a tabela inteira

Instrumentação da thread (banco de 10.030 linhas, home assinando):

```
1 SDK 34 Main Thread @coroutine#1|rows=10030
```

As duas `observeAll()` do Room emitem na main dispatcher e o `combine` monta as seções ali,
dentro do `stateIn(viewModelScope)` (`HomeViewModel.kt:342-354`). Custo por chamada:
**6-11 ms** com 10.030 linhas. Com home **e** widget assinando (como no aparelho), UMA
varredura de boot:

```
EMISSAO banco=10030 chamadasSectionsOf=4 somaMsNaMain=107
```

Boot completo: `rescheduleAll` **7 ms** com banco novo → **28 ms** com 4.398 ocorrências.
`observeAgenda().first()` 14-84 ms. **Cenário:** cada escrita dela (concluir, excluir,
adiar) reemite e a home remonta 10.000 itens na main thread — jank no toque. 107 ms é
**uma** varredura; a soma de um boot real no aparelho não foi medida.

## P2 — o widget computa o Snapshot antes de saber se há widget

`AgendaWidgetProvider.paint` (`AgendaWidgetProvider.kt:80-86`): `if (ids.isEmpty()) return`
acontece **depois** de `snapshotPendentes()` já ter rodado. Medido 0-7 ms com 10.030 linhas
— irrelevante hoje, mas é leitura da tabela toda descartada. O caminho do widget está bem
fechado: mutação B (`snapshotPendentes` usando `getAll()`) → **pega**,
`TaskRepositoryTest.retratoDoWidgetNaoLeOHistoricoInteiro`.

## Medido e OK (não refazer)

- **Duplicatas/órfãos**: 736 ocorrências → `idsDistintos=736 dosesComMaisDeUmaLinha=0
  orfaos=0`. Apagar a série leva as ocorrências (CASCADE + `deleteSeries`): 368 restantes,
  0 da série apagada.
- **Fuso**: SP → Europe/Lisbon com 180 dias de histórico → 3 pendentes reescritas para o
  instante local novo (o mesmo 08:00 de Lisboa); home segue 1 hoje / 2 próximas / 180 não
  realizadas, 0 órfãos. Mutação D (desligar a cura) → **pega 2**:
  `disparoUsaOFusoDoRelogioAgoraENaoOFusoGravadoNaSerie`,
  `HomeAlarmHealthCardTest.semProblemaNaoHaCartaoNaHome`.
- **Fala**: "já tomei o remédio" com 3.000 não realizadas → desfecho em **48 ms**, "Feito."
  (10 → 23 ms). O histórico **não** envenena o alvo.
- **Rolagem**: LazyColumn com 500 → 26 ms; com 5.000 → 21 ms até o fim. A lista é lazy;
  rolar não é o problema.
- **`AgendaSections.find`**: 0,35 ms/chamada com 10.000 itens.
- **Índices**: `Index("seriesId")`, `Index("status")`, `Index("localDate")` em `Entities.kt`.
  Nenhum `LIKE` no caminho quente.
- **DataStore**: 4 chaves fixas + 1 token (`SettingsStore.kt:36-39`). Não acumula.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| Execução | domain | app | total | falhas |
|---|---|---|---|---|
| Baseline | 489 | 761 | 1250 | 0 |
| Mutação A (`.take(30)`) | 489 | 772 | 1261 | **0** |
| Mutação B (widget lê tudo) | 489 | 772 | 1261 | 1 |
| Mutação C (sem poda de tombstone) | falha no domain | — | — | 1 |
| Mutação D (sem cura de fuso) | 489 | 772 | 1261 | 2 |
| Mutação E (home só PENDING) | 489 | 775 | 1264 | 3 |
| **Final restaurado** | **489** | **761** | **1250** | **0** |

O `app` infla nos runs intermediários porque as classes de medição estavam no fonte; o run
final, sem elas, bate o baseline exato.

## Não confirmado

- **Armazenamento cheio / "o app não abre"** — o Robolectric não simula disco cheio. O
  crescimento medido (1,9 MB/2 anos/5 séries) é benigno em si.
- **Tempo real de composição no aparelho dela** — mediu-se 8 ms para a home aparecer com
  2.000 não realizadas (LazyColumn só compõe o visível). Não mede aparelho idoso.
- **Soma das emissões de `sectionsOf` num boot completo** — 107 ms é UMA varredura.
- **Horário de verão com transição** (hora sobreposta/inexistente) — só a troca estável
  SP→Lisboa foi exercitada.
- **Extrapolação além de 10.000 linhas.**
