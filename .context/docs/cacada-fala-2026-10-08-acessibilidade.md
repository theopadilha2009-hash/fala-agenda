# Caçada — A ACESSIBILIDADE: o que o TalkBack lê de uma tela que ela não vê (2026-10-08)

Vigésima terceira leva. As anteriores estão listadas em `cacada-fala-2026-10-07-intencao-e-comandos.md`.

Vinte e duas caçadas mediram o que ela **diz** e o que o app **responde**. Esta mede o que o app
**fala de volta sem ela pedir**: a árvore de semântica que o TalkBack percorre. Para uma idosa
que não enxerga a tela, essa árvore **é** o aplicativo — o que não está nela não existe.

**Base:** `origin/main` @ `b78f8cf`. Worktree `docs/cacada-a11y`. Medição com a árvore de
semântica **real**: telas compostas com Compose + Robolectric e inspecionadas por
`fetchSemanticsNodes()` / `SemanticsProperties` / `SemanticsActions` / `boundsInRoot`, e o widget
por `RemoteViews.apply()` com a janela **anexada** (`ActivityController…visible()`) lendo
`createAccessibilityNodeInfo()`. **Corpus escrito antes de abrir o código** (`CORPUS.md`, 15
perguntas da idosa). **2 mutações de produção**, uma por execução, contadas pelos XMLs.

## O que esta leva corrige da passada anterior

A passada anterior fez a caça **em modo somente-leitura** e **não compôs nenhuma tela**: os
achados dela eram leitura de fonte e `grep`, não medição. O próprio método do lote já tinha sido
reprovado antes por construir o corpus no espaço que a lista cobre
(memória `corpus-construido-no-espaco-que-a-lista-cobre`); a correção foi escrever o `CORPUS.md`
**antes** de abrir produção.

Medido: **dos 4 achados que ela levou, 3 não se sustentam** quando a árvore é composta de verdade.
Os dois que se sustentam estão abaixo, e os três que caíram também — com a evidência bruta, porque
um achado refutado por medição vale tanto quanto um confirmado.

## P1 — F1: os títulos das Configurações não são cabeçalho (e na home são)

**Medição.** `SettingsScreen` composta, árvore unmerged:

```
SONDA   #21 text=Aparência cd=- heading=false live=null role=null ...
SONDA   #39 text=Horário de silêncio cd=- heading=false live=null role=null ...
SONDA   #51 text=Voz do celular cd=- heading=false live=null role=null ...
SONDA   #55 text=Ajuda extra (opcional) cd=- heading=false live=null role=null ...
```

Controle na mesma execução, a home com uma tarefa do dia:

```
SONDA   #107 text=Bom dia. Próximo: Tomar remedio, hoje às 11:29. cd=- heading=true ...
SONDA   #133 text=Hoje cd=- heading=true ...
```

Os **quatro** cartões de `SettingsScreen.kt:98,126,167,174` são `titleMedium` sem
`Modifier.semantics { heading() }`; o título de seção da home (`HomeScreen.kt:1188`) e a manchete
(`HomeScreen.kt:527`) **são** cabeçalho. O app já conhece a marca e a usa na home — a assimetria é
o achado. Sem `heading()`, a navegação por cabeçalho do TalkBack atravessa as Configurações como
um bloco só: ela não pula de "Aparência" para "Horário de silêncio", ouve tudo em ordem.

**Mutação MUT1** — acrescentar `heading()` aos 4 títulos (`SettingsScreen.kt`), restaurado depois
por `cp`:

| | antes (produção) | MUT1 |
|---|---|---|
| `osTitulosDeSecaoDasConfiguracoesSaoCabecalho` | **FAILED** | **passa** |

Sonda derruba na produção e passa com o fix → achado **real e detectável**.

**Cobertura:** zero. `grep` em `app/src/test` + `domain/src/test` por
`heading|liveRegion|SelectableGroup|ProgressBarRangeInfo` acha **uma** ocorrência, e é um
comentário (`HomeHeadlineTest.kt:19`). Nenhum teste do repositório lê a marca de semântica.

## P1 — F2: os chips de escolha não estão num grupo selecionável

**Medição.** `ConfirmDraftScreen` composta, árvore unmerged, os chips de "Repetir":

```
SONDA   #145 text=- cd=- heading=false role=Checkbox selected=false onClick=true grupo=false ...
SONDA     #148 text=Só uma vez ...
SONDA   #149 text=- cd=- heading=false role=Checkbox selected=true  onClick=true grupo=false ...
SONDA     #152 text=Todo dia ...
SONDA   #153 text=- cd=- heading=false role=Checkbox selected=false onClick=true grupo=false ...
SONDA     #156 text=Dias úteis ...
```

Cada chip traz `role=Checkbox` e `selected` (o estado está lá), mas **nenhum nó da árvore declara
`SemanticsProperties.SelectableGroup`** (`grupo=false` em todos). O mesmo nos chips de tema de
`SettingsScreen.kt:113-118` e nos chips de dia de `ConfirmDraftScreen.kt:299`. O `FilterChip` do
Material3 marca `selectable`; o grupo é responsabilidade de quem o contém, e o `FlowRow` de
`ChipRow` (`ConfirmDraftScreen.kt:576`) não declara. Sem `selectableGroup`, o TalkBack não diz
"1 de 6" — ela ouve seis caixas soltas e não sabe que são alternativas de uma escolha.

**Mutação MUT2** — `Modifier.semantics { selectableGroup() }` no `FlowRow` do `ChipRow`
(`ConfirmDraftScreen.kt:576`), restaurado depois por `cp`:

| | antes (produção) | MUT2 |
|---|---|---|
| `osChipsDeRepeticaoEstaoNumGrupoSelecionavel` | **FAILED** | **passa** |

Achado **real e detectável**.

## P2 — F3: o widget é clicável no nó raiz e o nó raiz não tem rótulo (medido, não é o P0 da passada)

**Medição.** `AgendaWidgetProvider.views(...)` **aplicado** (`remote.apply()`) e anexado a uma
`Activity` visível; `createAccessibilityNodeInfo()` em cada view:

```
SONDA-NI LinearLayout id=widget_root classe=android.widget.LinearLayout text="null" desc="null" clickable=true focusable=true anexado=true visivel=true
SONDA-NI   TextView id=widget_kicker classe=android.widget.TextView text="Próxima" desc="null" clickable=false focusable=false
SONDA-NI   TextView id=widget_title  classe=android.widget.TextView text="Tomar remedio de pressao" desc="null"
SONDA-NI   TextView id=widget_when   classe=android.widget.TextView text="Hoje · 08:00" desc="null"
SONDA-NI   TextView id=widget_speak  classe=android.widget.TextView text="Falar" desc="null" clickable=true focusable=true
```

E o estado vazio:

```
SONDA-NI   TextView id=widget_kicker text="Agenda"
SONDA-NI   TextView id=widget_title  text="Nada marcado"
SONDA-NI   TextView id=widget_when   text="Toque para abrir a agenda"
```

O que isso diz: o nó **raiz** é `clickable=true focusable=true` com `text=null` e
`contentDescription=null` — ele abre o app (`AgendaWidgetProvider.kt:219`) e o TalkBack foca nele
sem rótulo. Os três `TextView` de conteúdo **têm `text` real**, e é o `text` que o TalkBack lê —
o widget **não** é mudo. O botão "Falar" (`widget_speak`) também tem `text="Falar"`.

Severidade P2, não P0: o conteúdo é lido; o que falta é o rótulo do alvo que abre o app.

## Refutado por medição (a passada anterior levou como P0/P1)

### R1 — "widget sem `contentDescription`" (P0 dela) → **não é o defeito que ela descreveu**

A conclusão saiu de `grep` (exit 1) e leitura de XML, sem compor. Medido: os `TextView` do widget
carregam `text` real nos dois estados (acima). O TalkBack lê o `text` de um `TextView`; a ausência
de `contentDescription` nele **não** é defeito — é o normal. O defeito real é outro e menor (F3:
o nó raiz clicável sem rótulo). **Não** se confirma o P0.

### R2 — "região viva não anuncia no estado inicial" (P1 dela) → **não é defeito**

Medido, a home recém-composta, sem interação:

```
SONDA   #83 text=Toque no microfone e fale cd=- heading=false live=Polite role=null ...
```

O nó existe, tem `liveRegion=Polite` e o texto IDLE. Uma região viva anuncia quando o **conteúdo
muda**; no primeiro frame não há mudança, então não há anúncio — que é o comportamento correto.
A premissa dela está certa e o desfecho que ela supôs como defeito é o esperado. **Não** se
confirma.

### R3 — "cartão sobrescreve a descrição sem mesclar filhos" (P1 dela) → **não se confirma**

Medido, árvore **merged** (a que o TalkBack percorre), com uma tarefa do dia:

```
SONDA   #135 text=Tomar remedio | Hoje · 11:29 · daqui 1 h 59 min cd=Tomar remedio, Hoje · 11:29 · daqui 1 h 59 min. Toque para editar. role=Button onClick=true
SONDA     #140 text=Concluir ... role=Button onClick=true
```

O cartão é **um** nó na árvore merged: o `text` dos filhos foi dobrado nele (aparece em `text=`
com `|`), e o único filho que sobra é o botão "Concluir", que é um alvo de toque próprio — como
deve ser. A duplicação que ela temia aparece só na árvore **unmerged** (crua), que não é o que o
TalkBack lê. A sonda `oCartaoNaoDuplicaATexturaDosFilhos` **passou** na produção intacta. **Não**
se confirma.

### R4 — "indicadores de progresso sem `progressSemantics`" (P2 dela) → **não se confirma**

Medido em isolamento, o indicador do M3 **com e sem** a anotação explícita:

```
SONDA     #3 CircularProgressIndicator (sem anotação) progress=ProgressBarRangeInfo(current=0.0, range=0.0..0.0, steps=0)
SONDA     #4 LinearProgressIndicator   (sem anotação) progress=ProgressBarRangeInfo(current=0.0, range=0.0..0.0, steps=0)
SONDA     #5 CircularProgressIndicator (com anotação) progress=ProgressBarRangeInfo(...)
SONDA     #6 LinearProgressIndicator   (com anotação) progress=ProgressBarRangeInfo(...)
```

E no ponto real do app, a `ConfirmDraftScreen` com `saving=true`:

```
SONDA   #172 text=- cd=- progress=ProgressBarRangeInfo(current=0.0, range=0.0..0.0, steps=0) ...
```

O `CircularProgressIndicator`/`LinearProgressIndicator` do Material3 **já** expõem
`ProgressBarRangeInfo` — a anotação manual não muda nada. O P2 dela **não** se confirma.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| suíte | testes | falhas |
|---|---|---|
| app (`origin/main` @ `b78f8cf`, sondas fora da árvore) | 783 | 1 |
| domain | 538 | 0 |

A falha única é `FonteGrandeGeometriaTest > em2xNaJanelaPequenaAListaEOConcluirSobrevivem`:

```
message="o &quot;Concluir&quot; em 2.0 (360x800) nao pode ter 0 dp de altura
expected to be at least: 96.0
but was                : 60.0"
```

É a falha **pré-existente do #113** (geometria sensível à fonte sob `GraphicsMode.NATIVE`),
declarada no briefing do lote como não sendo desta leva. **Descontada.**

## Não medido / não provado

- **O TalkBack de verdade não foi executado.** O que se mediu é a **árvore de semântica** que o
  TalkBack consome (`SemanticsProperties`/`AccessibilityNodeInfo`), não a fala sintetizada. Um
  `heading=true` medido é a marca que o leitor usa para navegar; não é a locução em áudio.
- **O widget na tela inicial de um aparelho real não foi visto.** A medição é o `RemoteViews`
  aplicado a uma janela Robolectric; o launcher de verdade pode compor a árvore de outro jeito.
- **Nenhum alvo de toque foi remedido nesta leva.** A passada anterior mediu `MicMark.kt` (88 dp) e
  `UiBits.kt` (56/64 dp) e disse OK; o briefing mandou não reabrir. A única geometria que apareceu
  nas árvores desta leva (chips 48 dp, botões 56 dp) é consistente com aquilo, mas não é medição
  nova.
- **Não se varreu toda a árvore de semântica do app.** Mediram-se as superfícies dos achados
  (home, ConfirmDraft, Settings, Update, widget). As demais telas (mês, onboarding) não foram
  compostas nesta leva.
