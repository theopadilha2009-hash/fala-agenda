# Caçada — AS CONFIGURAÇÕES e o PRIMEIRO USO (2026-10-08)

Vigésima segunda leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

Vinte e uma levas mediram o parser, a IA, o classificador, o título, a recorrência, os
valores, as datas, o widget, a home, a caixa de confirmação, a fonte grande, a cadeia de
áudio, a voz de saída, o adiamento, a resposta ao usuário e a tela do mês. **A tela de
Ajustes, a gaveta da home e o onboarding nunca foram auditados** — e é exatamente onde uma
idosa se perde: o ajuste de fonte, o tema, o que ela pode mexer sem quebrar, o que o app
promete na primeira tela.

**Base:** `origin/main` @ `b78f8cf`. Cópia descartável (`/tmp/fala-caca-config`).
**Robolectric 4.14.1** com `@GraphicsMode(NATIVE)` + `@Config(fontScale = …)`, sdk 34,
`manifest = Config.NONE`. **16 medições de geometria real** (bounds do NÓ CLICÁVEL) em
1.0x/1.3x/1.5x/2.0x, duas janelas (360dp×800dp e 411dp×891dp = o aparelho dela). **3
mutações** de produção, uma por execução. O corpus (`CORPUS.md`) foi escrito **antes** de
abrir o código de produção.

## F1 [P1] — na gaveta da home, o item "Escuro" encolhe até 20 dp com a fonte grande

`HomeDrawer.kt:41` (`ModalDrawerSheet`, **sem rolagem**) + `:107-124` (os três itens de
Aparência). A `Column` da gaveta (`:42-45`) empilha **10 itens** mais o título e o
`HorizontalDivider`; a última linha é "Escuro". O `ModalDrawerSheet` não dá rolagem ao
conteúdo, então o que estoura é **recortado pela borda de baixo** — o `NavigationDrawerItem`
continua na árvore de toque (`clicavel=true`) mas com a altura que sobrou.

Medido (janela 360dp×800dp = 720×1488 px, gaveta aberta dentro do `ModalNavigationDrawer`):

| escala | "Seguir o celular" | "Claro" | **"Escuro" (último)** | top→bottom |
|---|---|---|---|---|
| 1.0 | 112 px = 56 dp | 112 px = 56 dp | **105 px = 52,5 dp** | 1343→1448 |
| 1.3 | 112 px = 56 dp | 112 px = 56 dp | **89 px = 44,5 dp** | 1360→1449 |
| 1.5 | 112 px = 56 dp | 112 px = 56 dp | **79 px = 39,5 dp** | 1370→1449 |
| **2.0** | 112 px = 56 dp | 112 px = 56 dp | **40 px = 20 dp** | 1408→1448 |

O `bottom` para de crescer (1448/1449 = a borda útil da gaveta) enquanto o conteúdo acima
cresce com a fonte: **todo o crescimento do 1.0x ao 2.0x é subtraído do último item.** Em
1.3x ele já está abaixo dos 48 dp da WCAG, em 1.5x cai para 39,5 dp e em 2.0x para **20 dp** —
um alvo de toque de um quinto do que devia ser. A árvore confirma o recorte:

```
Node #267 at (l=24.0, t=1520.0, r=696.0, b=1560.0)px   ← em 2.0x
  Role = 'Tab'  Text = '[Escuro]'  Actions = [OnClick, ...]
```

**Asserção de sonda** (positiva, no nó clicável — a assinatura do defeito):

```
GAVETA[1.0] "Escuro" altura=105px clicavel=true dp=52,5
GAVETA[1.3] "Escuro" altura=89px  clicavel=true dp=44,5   → FAIL: expected at least 96.0 but was 89.0
GAVETA[1.5] "Escuro" altura=79px  clicavel=true dp=39,5   → FAIL: expected at least 96.0 but was 79.0
GAVETA[2.0] "Escuro" altura=40px  clicavel=true dp=20,0   → FAIL: expected at least 96.0 but was 40.0
```

**Por que está errado para ela:** o tema é o ajuste que ela mexe por curiosidade, e a gaveta
é a superfície de um toque para isso. Com a fonte grande — o ajuste que ela **mais** usa — o
item que ela quer tocar ("Escuro") vira uma faixa de 20 dp. O `Role='Tab'` continua lá e o
TalkBack o alcança, mas o dedo dela, com a acuidade de uma idosa, erra a faixa. É a mesma
forma do F1 da leva de fonte grande (o "Concluir" com 0 dp): **o controle existe na árvore e
não existe na tela.**

**Escopo honesto:** na janela dela (411dp×891dp = 1233×2505 px) em 2.0x o item tem **168 px =
84 dp** — o defeito **não** aparece no aparelho alto. Ele é do layout sob janela curta + fonte
grande (o mesmo par do F1 do #113), e é alcançável em qualquer aparelho cujo alto útil fique
abaixo do que a coluna pede.

## F2 [P2] — o mesmo ajuste de tema: correto numa superfície, quebrado na outra

Este é o eixo da memória `mesmo-defeito-em-duas-superficies`, na direção inversa: **o ajuste
de aparência vive em DUAS telas** e as duas se comportam de forma diferente sob a fonte
grande.

| superfície | item | 1.0x | 2.0x |
|---|---|---|---|
| `SettingsScreen.kt:103-120` (Aparência, `FlowRow`) | chip "Escuro" | 176×96 | **250×96 px = 48 dp** ✔ |
| `HomeDrawer.kt:107-124` (gaveta) | item "Escuro" | 105 px | **40 px = 20 dp** ✘ |

Os chips da tela de Ajustes **sobrevivem** (o `heightIn(min = 48.dp)` de `SettingsScreen.kt:116`
e o `verticalArrangement` do `FlowRow` os mantêm inteiros, e eles quebram em duas linhas). A
gaveta não tem o mesmo cuidado. Fechar uma superfície não fecha o eixo — e aqui **não** foi
fechada nenhuma: as duas foram escritas, e só uma resiste.

**Evidência bruta (2.0x):**

```
MEDIDO [2.0] chip Celular  layout=254x96  bounds=(72, 815, 326, 911)
MEDIDO [2.0] chip Claro    layout=208x96  bounds=(342, 815, 550, 911)
MEDIDO [2.0] chip Escuro   layout=250x96  bounds=(72, 927, 322, 1023)   ← OK, 48 dp
MEDIDO [2.0] gaveta "Escuro" altura=40px  clicavel=true dp=20,0          ← o mesmo ajuste, quebrado
```

## F3 [P2] — o app não tem ajuste de fonte e não diz onde ele está

A tela de Ajustes tem um cartão chamado **"Aparência"** — o nome que uma pessoa usaria para
procurar "letra grande". Dentro dele há **só tema** (Celular/Claro/Escuro) e uma frase sobre
fundo creme. Nenhum ajuste de tamanho de letra, e nenhuma menção a que o tamanho se muda nas
configurações do celular.

Sonda: rolou-se a tela inteira até a última linha (a versão) e procurou-se por termo:

```
PROCURA "letra"    na tela de Ajustes -> 0 no(s)
PROCURA "tamanho"  na tela de Ajustes -> 0 no(s)
PROCURA "fonte"    na tela de Ajustes -> 0 no(s)
PROCURA "aumentar" na tela de Ajustes -> 0 no(s)
PROCURA "acessib"  na tela de Ajustes -> 0 no(s)
```

**Por que está errado para ela:** o corpus (Cena 2) pergunta o que ela faz quando não enxerga
bem. As opções são (a) aumentar a fonte do sistema — que ela pode não saber fazer —, (b)
procurar um ajuste de letra dentro do app — que **não existe** —, ou (c) sofrer. A tela que se
chama "Aparência" é exatamente onde ela vai bater, e ela não só não encontra como não é
avisada de que o caminho é outro. É o mesmo custo do `ManufacturerHint` que a home já tem
para bateria: o app manda ela ao ajuste certo do celular quando o assunto é a bateria, e fica
mudo quando o assunto é a letra — o ajuste que a usuária deste app de fato usa.

**Escopo honesto:** não é "faltou um botão"; um controle de escala dentro do app que não
reescala o `sp` de tudo seria pior. O achado é a **ausência de ponteiro** para o ajuste do
sistema, na tela onde ela procura.

## F4 [P2] — três mutações de produção sobrevivem à suíte: o eixo config é cego

Toda mutação foi rodada na suíte de app inteira (`:app:testDebugUnitTest --rerun-tasks
--max-workers=2`), contada **pelos XMLs**.

| # | Mutação | arquivo | app (783) |
|---|---|---|---|
| baseline | — | — | 783 / 0 falhas |
| **M1** | troca os rótulos `LIGHT to "Escuro"` / `DARK to "Claro"` | `SettingsScreen.kt:109-111` | 783 / **0 → VERDE** |
| **M2** | `setQuietHours` vira no-op e a tela segue dizendo "atualizado" | `SettingsViewModel.kt:70-73` | 783 / **0 → VERDE** |
| **M3** | `setThemeMode` vira no-op e a tela segue dizendo "Aparência salva" | `SettingsViewModel.kt:58-61` | 783 / **0 → VERDE** |

As três sobreviveram. **M1** é o mais direto: com os rótulos trocados, tocar em "Escuro" põe o
app no claro e tocar em "Claro" põe no escuro — o ajuste **mente**, e nenhum dos 783 testes vê.
**M2/M3** são a classe do "aceito ≠ saiu de fato": a tela anuncia sucesso que não houve.

A prova de que o instrumento funciona (sonda não-vacuosa): **M3 foi pega pela sonda**
`em2xAEscolhaDeTemaGravaEAMudancaChega` (que lê o DataStore real depois do toque), enquanto a
suíte pré-existente de 783 testes seguiu verde com a mutação aplicada. As coberturas de
Ajustes que existem (`SettingsQuietHoursTextTest`, `SettingsVoiceStatusTest`) prendem **texto**,
não **comportamento**: nenhum teste toca um chip e lê a preferência.

## Medido e OK (não refazer)

| Item | Medição | Veredito |
|---|---|---|
| Tela de Ajustes sob 2.0x | chips 96 px = 48 dp; "Começa"/"Termina" clicáveis; a `Column` tem `verticalScroll` (`SettingsScreen.kt:93`) | **sobrevive** |
| Diálogo do silêncio (TimePicker) | "OK" 116×112 (1.0x) / 133×119 (2.0x); "Cancelar" 193×112 / 288×119; ambos `clicavel=true`, dentro da janela | **OK** — o `heightIn(min = 56.dp)` (`SettingsScreen.kt:222,226`) segura o rodapé, que é o defeito que o `AlertDialog` do M3 causava na caixa "Pode salvar?" |
| Ajuste de tema grava | toque em "Escuro" (2.0x) → `themeMode == DARK` no DataStore real | **grava de fato** |
| Texto do silêncio × `ReminderPolicy` | "o primeiro aviso de cada tarefa ainda toca no horário marcado" — `ReminderPolicy.firstReminder` (`ReminderPolicy.kt:61-62`) devolve `fireAt = occurrenceScheduledAt` sem passar por `shiftOutOfQuietHours`; só as repetições (passo ≥ 1) são deslocadas (`:82`) | **promete e cumpre** |
| Tela de Atualizar sob 2.0x | rolagem presente (`areas-rolaveis=1`); "Voltar" 640×119 px após rolar; título 152 px | **OK** |
| Onboarding sob 1.5x/2.0x | `OnboardingAcessibilidadeTest` (3 casos, verdes) + `Column` rolável com `heightIn(min = maxHeight)` (`OnboardingScreen.kt:184-188`) | **já fechado** |
| Rótulos de `voiceOfflineMessage` | os 4 estados têm frase própria, sem jargão nem culpa | **OK** (`SettingsVoiceStatusTest`) |
| Fluxo de permissões do onboarding | mic negado → avisos ainda são pedidos (`OnboardingScreen.kt:156-166`); "Agora não" pula só o mic (`:263`) | **OK** |

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

- **app pristine** (`origin/main` @ `b78f8cf`, sem as sondas): **783 testes, 0 falhas**.
- **app com as sondas desta leva**: 797 testes, 0 falhas na parte que mede (as 4 sondas da
  gaveta falham de propósito em 1.3x/1.5x/2.0x — é o defeito do F1, não regressão).
- **Flake pré-existente, não desta leva:** na primeira execução cheia, `FalaComandoTest`
  (2 casos) e `HomeAlarmHealthCardTest` (1) falharam com `Dispatchers.Main is used
  concurrently with setting it` / `UncaughtExceptionsBeforeTest`; **passam isolados**
  (`--max-workers=1`). É o flake de corrotina entre classes já conhecido
  (`flake-de-corrotina-entre-classes`), e não tem relação com este eixo.

## Mutações — comando e restauração

Cada mutação: backup por `cp`, edição, `./gradlew :app:testDebugUnitTest --rerun-tasks
--max-workers=2`, restauração por `cp` do backup, `sha256` conferido **e** `git diff` vazio.
O backup foi refeito **a cada** edição de produção (`backup-velho-restaura-menos-que-o-devido`).

```
SettingsScreen.kt    sha256 7f89237cd37fa86b8d8f32ea77a2dbcc51b8857bfb6d268a05543b7ae1c357bf
SettingsViewModel.kt sha256 add555c3a8baae6c558ea7e4cfce7fee202aae180f533e946c25ddc55e86619d
git -C /tmp/fala-caca-config diff --stat -- app/src/main   → (vazio)
```

## Não confirmado

- **Não rodou fora do Robolectric** (não há device nesta máquina). A altura do item da gaveta
  é a que o Compose calcula; o que o launcher real faz com uma janela ainda mais curta (barra
  de navegação de 3 botões, por exemplo) não foi medido.
- **TalkBack real** — mediu-se o `Role='Tab'` e o `OnClick`, não a navegação por foco.
- **F1 no aparelho dela**: medido **OK** (84 dp em 2.0x na janela 411×891). O achado vale para
  janelas curtas; não se mediu quantas das aparelhos dela caem nesse caso.
- **F3 (ponteiro para a fonte do sistema)** é decisão de produto, não bug — registrado como
  achado, não como defeito.
- A mutação **M1** sobreviveu; não se varreu se **outro** teste (fora do `:app`, no `:domain`)
  a pegaria — o rótulo vive só na camada de UI, então não.
