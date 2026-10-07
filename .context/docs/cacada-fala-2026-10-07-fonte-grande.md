# Caçada — FONTE GRANDE e acessibilidade (2026-10-07)

Décima terceira leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

Nenhuma caçada mediu este eixo, e é o mais provável de estar quebrado em silêncio:
**idoso aumenta a fonte do sistema**. É a primeira coisa que se faz quando não se enxerga
bem, e é exatamente o perfil da usuária. Se o layout não aguenta, o texto corta, os botões
saem da tela, o alvo de toque encolhe — e ela não consegue nem reclamar direito porque o
app não avisa que quebrou.

**Base:** `origin/main` @ `e0b721c` (`app/src/main` conferido idêntico ao `e23edfe` por
`diff -r`). Cópia descartável (`/tmp/caca-fonte`). **32 medições de geometria real**
(bounds + `size` de layout) em **1.0x / 1.3x / 1.5x / 2.0x**, em duas janelas
(360dp×800dp e 411dp×891dp = 1080×2400, o aparelho dela). Robolectric com
`GraphicsMode.NATIVE` e `@Config(fontScale = …)`. **3 mutações** de produção, uma por
execução. **7 cálculos de contraste WCAG** a partir dos `Color` reais do tema.

## F1 [P1] — em 2.0x o "Concluir" fica com 0 dp: o caminho de um toque some

`HomeScreen.kt:1089-1113` (a `MicDock`) + `:1055-1056`. A `MicDock` é o `bottomBar` do
`Scaffold` e **não é rolável nem limitada** — só 12 dp de padding vertical e
`spacedBy(12.dp)`. Em 2.0x o conteúdo dela mede 681 px; com o `bottomBar` +
`navigationBarsPadding()` sobram **228 px** para a `LazyColumn` (de 1488), e o conteúdo
fica atrás dela. Medido (janela 720×1488):

| escala | lista (px) | fração da janela | "Concluir" rolado (layout → bounds) | altura útil |
|---|---|---|---|---|
| 1.0 | 669 | 45,0% | 185×112 → (455, 662, 640, 774) | 112 px = 56 dp OK |
| 1.3 | 631 | 42,4% | 210×112 → (430, 601, 640, 713) | 112 px = 56 dp OK |
| 1.5 | 608 | 40,9% | 231×112 → (409, 546, 640, 658) | 112 px = 56 dp OK |
| **2.0** | **228** | **15,3%** | 276×119 → **(0, 0, 0, 0)** | **0 px** |

Em 2.0x cabem 228 px de lista — menos que um cartão (280 px), então **nenhuma tarefa
aparece inteira**, e o atalho "Concluir" do cartão sai da árvore de toque
(`boundsInRoot = (0,0,0,0)`). No aparelho alto (1233×2505) em 2.0x o "Concluir" **existe e
tem alvo** (89 dp) — o colapso é do layout, não da janela de teste.

**Cenário:** com a fonte no máximo, ela abre o app e vê só o cabeçalho, os chips de 5/15 min
e o microfone. A lista do dia está atrás, com a altura de meia polegada. Não há gesto que a
alcance. **Cobertura: não coberto** — `CartaoAtrasadoConcluiTest` mede esse botão só na
escala default.

## F2 [P1] — em 1.5x o "Mudar" da caixa "Pode salvar?" sai da tela, e a caixa não rola

`QuickConfirmDialog.kt:73` (`AlertDialog`), conteúdo em `:88-115`, "Mudar" em `:114`. O
`AlertDialog` do M3 não dá rolagem ao conteúdo: o que estoura empurra o rodapé e sai da
janela. Medido (360dp×800dp, rascunho cheio):

| escala | raiz | "Mudar" layout → bounds | `assertIsDisplayed()` |
|---|---|---|---|
| 1.0 | 720×1248 | 624×112 → (48, 788, 672, 900) | passa |
| 1.3 | 720×1486 | 624×112 → (48, 1015, 672, 1127) | passa |
| **1.5** | 720×1600 | **624×0 → (0, 0, 0, 0)** | **FALHA: "The component is not displayed!"** |
| 2.0 (aparelho dela) | 1233×2673 | 624×0 → (0, 0, 0, 0) | **FALHA** |

**"Mudar" é o único caminho para corrigir o recado:** `onDismissRequest` só cancela e o
"Salvar" grava o que está. Em 1.5x ela perde a correção — e, no aparelho dela, também em
2.0x. **Cenário:** fala "tomar remédio amanhã de manhã", o app entende a data errada, ela
toca em "Pode salvar?" e **não existe "Mudar" na caixa**. Só resta cancelar tudo ou salvar
errado.

## F3 [P2] — em 2.0x "Salvar" e "Cancelar" se sobrepõem em 8 px

Medido (360dp×800dp, 2.0x): `salvar = (48, 1258, 672, 1409)`, `cancelar = (48, 1401, 672, 1520)`
→ sobreposição de **8 px** (12 px no aparelho alto). Em 1.5x não há sobreposição. P2 porque
o Robolectric não resolve hit-testing por pixel — só se sabe que os retângulos se cruzam.

## Medido e OK (não refazer)

| Item | Medição | Veredito |
|---|---|---|
| `configChanges` no manifest | ausente; `MainActivity` sem declaração | a Activity **recria** na troca de fonte |
| Rascunho sobrevive à recriação | `rememberSaveable` + `DraftSaver` (`HomeScreen.kt:256`, `FalaAgendaRoot.kt:94`, `WriteTaskScreen.kt:51`); `DraftSaverTest` prende o parcel ida-e-volta | **o P0 hipotético não existe** |
| `allowBackup` | `false` | OK |
| Alvos ≥ 48 dp | Menu 96, Voltar 96, Mês anterior 96, microfone 176, "Escrever tarefa" 112, chips 96, "Concluir" 112 (1.0–1.5x), "Salvar"/"Cancelar" 112–151 | OK, exceto F1/F2 |
| Texto em `dp` | **zero** `fontSize = …dp` | tudo em `sp` |
| `contentDescription` | Voltar, Mês anterior, Próximo (com estado), Menu, microfone, cartão inteiro, silêncio | ícones sem rótulo são decoração ao lado de texto |
| Contraste (claro e escuro) | Muted/branco 6,69 · Muted/creme 5,92 · NightMuted/superfície 7,52 · Ink 17,43 · onPrimary/primary 5,56 e 8,78 · Missed 7,65 e 6,31 · desabilitado/outlineVariant 5,03 e 5,34 | **todos ≥ 4,5:1** |
| Título do `TopAppBar` em 2.0x | 90 px, sem corte | OK |
| Widget (`RemoteViews`) | 16/18/18 sp, `widget_speak` `minHeight="56dp"`; `WidgetFonteTest` prende | OK |
| Abertura (onboarding) | `OnboardingAcessibilidadeTest` prende 1.5x e 2.0x com rolagem | já fechado |

Só **4 `maxLines`** no app inteiro: `HomeScreen.kt:1218` (observação, 2 linhas, sem
`overflow` → corta com `…`) e 3 campos de entrada, onde cortar é o correto. Nenhuma altura
fixa em volta de texto.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| # | Mutação | domain | app |
|---|---|---|---|
| baseline | — | 489 / 0 | 761 / 0 |
| M1 | `configChanges="fontScale\|density\|…"` | 489 / 0 | 761 / 0 → **VERDE** |
| M2 | apaga `labelLarge` do `typography` | 489 / 0 | 761 / 0 → **VERDE** |
| M3 | `heightIn(max = 300.dp)` no `Column` da caixa | 489 / 0 | 761 / 0 → **VERDE** |

As três sobreviveram: **nenhum dos 1250 testes mede geometria sob `fontScale` fora do
onboarding.** M3 é a mais reveladora: com o teto de 300 dp o diálogo passa a rolar e
**"Mudar" deixa de existir em todas as escalas, inclusive 1.0x** — e a suíte segue verde.
Ou seja: mesmo um dano total na caixa rápida é invisível para os 761 testes de app. Só o
`OnboardingAcessibilidadeTest` cobre esse eixo, e cobre uma tela só.

## Não confirmado

- **Fonte real do fabricante.** O `@Config(fontScale)` escala o `sp`; o que Samsung/Xiaomi
  entregam com "Fonte máxima" pode passar de 2.0x. Em 2.0x o F1 já colapsa.
- **TalkBack real** — mediu-se a presença de `contentDescription`, não a navegação por foco.
- **Qual dos dois botões vence o toque** na sobreposição de 8 px do F3.
- **`Mudar` fora da tela = toque impossível?** No papel o `AlertDialog` recorta o conteúdo
  fora dos bounds; não se provou o comportamento de toque no pixel. Classificado pelo que é
  inequívoco: o `assertIsDisplayed()` falha e o nó tem altura zero.
- **Nada rodou fora do Robolectric** (não há device nesta máquina).
