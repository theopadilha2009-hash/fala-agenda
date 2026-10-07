# Caçada — números, quantidades e valores na fala (2026-10-07)

Quinta leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06.md`, `cacada-fala-2026-10-06-novos.md`,
`cacada-fala-2026-10-07-correcao.md`, `cacada-fala-2026-10-07-dose-e-duracao.md`,
`cacada-fala-2026-10-07-pergunta-e-resposta.md` e
`cacada-fala-2026-10-07-ancoras-de-rotina.md`. Esta mede um eixo que **nenhuma** das
outras tocou: **o número dito — valor em reais, quantidade, dígito × extenso, grandes e
decimais.**

Medições em cópia descartável (`/tmp/fala-cacada-numeros`, base `be0b0bf`), parser real
(`LocalTaskParser`) com `FixedAppClock` em `2026-10-07 10:00 America/Sao_Paulo`.
O esperado de cada caso foi calculado **fora do código** (aritmética sobre o número que
gerou a frase — `(A*1000+B)*100`, `A*100+M`), nunca derivado da lógica do parser.

## Manchete

**926 estados medidos, 370 violações** (duas famílias de oráculo, valor e quantidade).
O valor errado **não gera nota nenhuma** e passa pela caixa rápida (`qc=true`): é a pior
classe de defeito do projeto, e ela está viva em três formas independentes.

| Oráculo | Casos | Violações |
|---|---|---|
| Valor misto dígito+extenso ("A mil e B") | 162 | 162 (100%) |
| Centavos falados (`A reais e M centavos`) | 594 | 198 |
| Quantidade falada (token no título) | 70 | 10 |
| Dinheiro vazando da quantidade | 100 | 0 |
| **Total** | **926** | **370** |

Suíte existente no clone: **1145 testes, 0 falhas** (contados nos XMLs, não no
"BUILD SUCCESSFUL"). O parser é bem testado — mas o oráculo do próprio repo
(`oraculoDoValorFalado`) só cruza dígito puro, cifrão e "N mil" *sem* o segundo número,
e por isso não vê nada disto.

## Defeito 1 (P0) — valor misto dígito+extenso: número errado, em silêncio

`LocalTaskParser.kt:258` — `if (precedidoDeDigito(text, m)) return null`

Ela mistura dígito e extenso o tempo todo ("2 mil e quinhentos"). Quando o **primeiro**
número é dígito e o **segundo** é extenso, o ramo `REAIS_NUMERIC` (que casa "2 mil")
vence, e o `precedidoDeDigito` derruba o `REAIS_EXTENSO` (que casaria "mil e quinhentos").
O valor sai só o primeiro pedaço.

| Input exato | O que o app faz | O que devia fazer |
|---|---|---|
| `pagar 1 mil e quinhentos reais amanhã às 10h` | `amountCents=null`, título `'Pagar 1 mil quinhentos reais'` | `150000` (R$1.500,00) |
| `pagar 2 mil e 500 reais amanhã às 10h` | `amountCents=50000` (**R$500,00**), título `'Pagar 2 mil'`, `qc=true` | `250000` (R$2.500,00) |
| `pagar 2 milhoes e 500 mil reais amanhã às 10h` | `amountCents=50000000` (R$500.000), título `'Pagar 2 milhoes'`, `qc=true` | `250000000` (R$2.500.000) |
| `pagar 2 mil e quinhentos reais amanhã às 10h` | `amountCents=null`, título `'Pagar 2 mil quinhentos reais'` | `250000` |
| `pagar 3 mil e duzentos reais amanhã às 10h` | `amountCents=null` | `320000` |

Dois desfechos, os dois ruins: **valor errado** (R$500 no lugar de R$2.500, `qc=true`,
sem nota) ou **valor perdido** (nulo, o número fica no título). O caso 100% dígito
(`pagar 2 mil e 500 reais`) é o pior: **erro de 5× confirmável em um toque.**

A mesma família com tudo por extenso funciona (`pagar dois mil e quinhentos reais` →
250000). A fronteira é só o primeiro número ser dígito.

## Defeito 2 (P1) — centavos ≥ 100: valor errado + "centavos" preso no título

`LocalTaskParser.kt:343` — `if (extra != null && extra in 0..99)`

O guard `0..99` existe para impedir que "5 reais e 1000" vire 5,1000. Mas quando ela diz
um valor de centavos **falado por extenso ou em dígito que passa de 99** — "cem centavos",
"cento e cinquenta centavos" — o guard descarta o valor e ele **some sem virar real**:

| Input exato | O que o app faz | O que devia fazer |
|---|---|---|
| `pagar 5 reais e 100 centavos amanhã às 10h` | `amountCents=500`, título `'Pagar 100 centavos'`, `qc=true` | `600` (R$6,00) — ou `null` **com nota**, nunca R$5,00 |
| `pagar cinco reais e cem centavos amanhã às 10h` | `amountCents=500`, título `'Pagar cem centavos'` | `600` |
| `pagar 1 reais e 150 centavos amanhã às 10h` | `amountCents=100`, título `'Pagar 150 centavos'` | `250` |
| `pagar 2 reais e 100 centavos amanhã às 10h` | `amountCents=200`, título `'Pagar 100 centavos'` | `300` |

Medido no oráculo de centavos: **198 de 594** violações, todas na coluna `M ≥ 100`
(`M ∈ {100, 150}`). A coluna `M ∈ {1, 5, 50, 99}` deu 0 violações.

Perda silenciosa: o valor é **truncado** para os reais, o "centavos" fica no título, e
`notes=[]`. Ela confirma R$5,00 achando que falou R$6,00.

## Defeito 3 (P2) — "um/uma" como quantidade some do título

`LocalTaskParser.kt:1668` — `(word !in FILLERS || (word == "meia" && ...))` com
`FILLERS` contendo `"um", "uma"` (`LocalTaskParser.kt:2323`)

Não há campo de quantidade (`ParsedTaskDraft` só tem `title`, `amountCents`,
`observation`), então a quantidade tem que **sobreviver no título** — é o único lugar
onde a palavra dela pode estar. Toda outra quantidade sobrevive; só "um"/"uma" é
engolido pelo filtro de FILLERS:

| Input exato | O que o app faz | O que devia fazer |
|---|---|---|
| `comprar um quilo de carne amanhã às 10h` | título `'Comprar quilo carne'` | `'Comprar um quilo carne'` |
| `comprar uma duzia de ovos amanhã às 10h` | título `'Comprar duzia ovos'` | `'Comprar uma duzia ovos'` |
| `tomar uma comprimidos amanhã às 10h` | título `'Comprar comprimidos'` | preserva `'uma'` |

**10 de 70** violações no oráculo de quantidade, todas na coluna `um/uma`. A comparação
"comprar **dois** quilos" (preserva) × "comprar **um** quilo" (some) prova que é o léxico
de FILLERS, não a extração de número — o valor está certo, o rótulo é que cai.

## Defeito 4 (P3) — "conto" é tratado de forma incoerente

`MEIO_REAL = \bmeio\s+(?:real|contos?)\b` (`LocalTaskParser.kt:2083`)

O parser recusa "conto" como unidade de dinheiro **em todos os ramos**, exceto o do
"meio" — então "meio conto" vira R$0,50 (que é o valor de "meio real"), enquanto "um
conto" não vira nada. Para uma falante que usa "conto" coloquialmente:

| Input exato | O que o app faz | O que devia fazer |
|---|---|---|
| `pagar meio conto amanhã às 10h` | `amountCents=50`, título `'Pagar'`, `qc=true` | R$500 (conto=1000) ou `null` **com nota** |
| `pagar um conto amanhã às 10h` | `amountCents=null`, título `'Pagar conto'` | coerente com o de cima |
| `pagar dois contos amanhã às 10h` | `amountCents=null` | idem |
| `pagar cinquenta conto amanhã às 10h` | `amountCents=null` | idem |

É o mesmo defeito do `MEIO_REAL`: o `contos?` da regex do "meio" aceita uma unidade que
o resto do parser rejeita. Uma falante que diz "meio conto" e "cinquenta conto" recebe
R$0,50 num e nada no outro, **sem nota**.

## O que NÃO é defeito (medido, 0 violações)

- **Dinheiro vazando de quantidade**: 100 de 100 frases (`comprar N caixas`,
  `tomar N comprimidos`, `caminhar N mil passos`, `correr N km`) → `amountCents=null`. O
  guard do `REAIS_NUMERIC` (unidade de dinheiro obrigatória) segura bem.
- **"cinco e meia"** → `ambiguous=true`, sem valor e sem hora cravada. Ambíguo é o certo.
- **Extenso puro** (sem dígito antes): `dois mil e quinhentos` → 250000; `mil e quinhentos`
  → 150000; `um milhão e duzentos mil` → 120000000; `meio mil` → 50000; `meio milhão` →
  50000000. Tudo certo.
- **Grandes e decimais por dígito**: `R$ 1.234,56` → 123456; `1.500 reais` → 150000;
  `15.000` → 1500000; `1,5 milhão de reais` → 150000000; `1.234.567 reais` → 123456700.
- **"N mil reais"** (com unidade): `5 mil reais` → 500000; `12 mil reais` → 1200000.
- **`pagar 1500 reais`** → 150000 (4 dígitos sem separador). `pagar 12345 reais` → `null`
  (ambíguo, por desenho).
- **"meia dúzia"**: `comprar meia duzia de ovos` → título `'Comprar meia duzia ovos'`
  (o "meia" e o "duzia" sobrevivem).
- **`R$ 5.000,00`** → 500000 (a vírgula decimal com milhar por ponto).

## O oráculo (independente) e o output cru

Esperado calculado por aritmética sobre o número que gerou a frase:

- Misto: `pagar {A} mil e {B} reais` → `(A*1000+B)*100`, `A∈1..9`, `B∈{100..900}` (dígito
  e por extenso). **162 casos, 162 violações.**
- Centavos: `pagar {A} reais e {M} centavos` → `A*100+M`, `A∈1..99`, `M∈{1,5,50,99,100,150}`.
  **594 casos, 198 violações.**
- Quantidade: `comprar {Q} {unidade}` → o token de `Q` tem que estar no título normalizado,
  `Q∈{um,uma,dois,duas,tres,quatro,cinco,seis,dez,1,2,3,6,12}`. **70 casos, 10 violações.**
- Dinheiro da quantidade: `comprar N caixas`/`tomar N comprimidos`/`caminhar N mil passos`
  → `amountCents` tem que ser `null`, `N∈1..20`. **100 casos, 0 violações.**

Output cru (amostra, do `<system-out>` do XML):

```
ORACULO_MISTO|total=162|violacoes=162
MISTO|pagar 1 mil e 500 reais amanhã às 10h|obt=50000|esp=150000|title='Pagar 1 mil'|amb=false
MISTO|pagar 1 mil e quinhentos reais amanhã às 10h|obt=null|esp=150000|title='Pagar 1 mil quinhentos reais'|amb=false
ORACULO_CENT|total=594|violacoes=198
CENT|pagar 2 reais e 100 centavos amanhã às 10h|obt=200|esp=300|title='Pagar 100 centavos'
CENT|pagar 2 reais e 150 centavos amanhã às 10h|obt=200|esp=350|title='Pagar 150 centavos'
QC|pagar 2 mil e 500 reais amanhã às 10h|amount=50000|qc=true|amb=false|notes=[]|title='Pagar 2 mil'
QC|pagar 1 mil e quinhentos reais amanhã às 10h|amount=null|qc=true|amb=false|notes=[]|title='Pagar 1 mil quinhentos reais'
QC|pagar 5 reais e 100 centavos amanhã às 10h|amount=500|qc=true|amb=false|notes=[]|title='Pagar 100 centavos'
QC|pagar 2 milhoes e 500 mil reais amanhã às 10h|amount=50000000|qc=true|amb=false|notes=[]|title='Pagar 2 milhoes'
ORACULO_QTD|total=70|violacoes=10
QTD|comprar um quilo de carne amanhã às 10h|title='Comprar quilo carne'|faltou='um'
ORACULO_QTD_DINHEIRO|total=100|violacoes=0
```

## Prova por mutação (uma por execução, `cp` para restaurar)

Base intacta: `sha256 cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34`.
Backup por `cp` para `/tmp/fala-cacada-numeros/LocalTaskParser.kt.orig`. Cada mutação:
editar → rodar `:domain:test --tests "*CacadaDefeitosTest*"` → restaurar por `cp` →
conferir o sha256. **Nunca** `git checkout --`.

**Mutação 1 — remove o guard `precedidoDeDigito` (linha 258):**
```
# comando: substituir "if (precedidoDeDigito(text, m)) return null" por comentário
# sha após mutar: 9f678d37ef8c1189459659445ca5c765cb33106d413daeb6598333da11c930d4
CacadaDefeitosTest > mistoExtenso PASS    <-- passa SÓ com o guard removido
tests=4 failures=3 (mistoDigito, centavosCem, quantidadeUm ainda falham)
# restaurado por cp -> sha256 cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34 (bate)
```

**Mutação 2 — alarga o guard de centavos `0..99` → `0..9999` (linha 343):**
```
# comando: substituir "if (extra != null && extra in 0..99) {" por "0..9999"
# sha após mutar: 869e4bea183bae759a7d182e3a586f24697cd2a925ff442f435454430cc8d3d5
CacadaDefeitosTest > centavosCemPalavra PASS    <-- a forma "cem centavos" passa com o guard alargado
tests=5 failures=4 (a de dígito "100 centavos" ainda falha: o token numérico casou no ramo numérico, não na cauda)
# restaurado por cp -> sha256 cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34 (bate)
```

**Mutação 3 — tira `"um","uma"` de FILLERS (linha 2323):**
```
# comando: remover os dois tokens da linha de FILLERS
# sha após mutar: ed545038f4157c2b2b6d49a9e792975bb48353b97528ecbcd5a61914ef7c7478
CacadaDefeitosTest > quantidadeUm PASS    <-- o título preserva "um" só com o token fora de FILLERS
tests=5 failures=4 (os 3 de valor ainda falham)
# restaurado por cp -> sha256 cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34 (bate)
```

Cada mutação é **condição necessária** do seu defeito (a asserção correta passa só com a
mutação) — nenhuma é provada como suficiente, porque o caminho real (ex.: centavos no ramo
numérico) ainda quebra por outra causa.

## O que NÃO confirmei

- **Fusão de orações / "duas tarefas"**: não medi o eixo de separação de orações
  (`looksLikeTwoTasks`) para números; só confirmei que "comprar 2 kg de arroz e três de
  feijão" mantém os dois números no título. Não sei se ela espera dois cartões.
- **Extenso acima de milhão** ("um bilhão", "dois bilhões"): `NUMBER_WORDS` não tem
  "bilhão" — não medi se "um bilhão de reais" vira valor, nota ou lixo. Fica para a próxima.
- **Unidades de medida**: "quilo", "litro", "dúzia", "caixa" não têm campo; ficam no
  título. Não medi se o app deveria ter campo de unidade — só que o número sobrevive
  (exceto "um/uma", Defeito 3).
- **"50 centavos" sozinho** (sem "reais"): `amountCents=null`, título preserva "50
  centavos". Não é claramente defeito (o campo é de reais; o texto dela está lá), mas não
  há nota dizendo "isso é dinheiro". Não contabilizei como violação.
- **A camada de IA (`HybridParser`)**: só li o merge (`amountCents = remoteDraft.amountCents
  ?: localDraft.amountCents`). Não rodei o remoto. Se a IA preencher o valor do misto
  corretamente, o defeito do local é mascarado no caminho online — mas o caminho offline
  (sem rede/sem IA) e a frase completa (que não escala, porque tem data e hora) ficam com o
  valor errado. Não medi a frequência de cada caminho.
- **`app` (Robolectric)**: os defeitos são todos no `domain`; rodei a suíte do `app` só
  como não-regressão (86 classes, 0 falhas), não procurei defeito de número na UI.
