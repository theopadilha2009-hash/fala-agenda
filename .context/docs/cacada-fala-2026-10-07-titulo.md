# Caçada — o TÍTULO, o que o app escreve como nome da tarefa (2026-10-07)

Oitava leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06.md`, `cacada-fala-2026-10-06-novos.md`,
`cacada-fala-2026-10-07-correcao.md`, `cacada-fala-2026-10-07-dose-e-duracao.md`,
`cacada-fala-2026-10-07-pergunta-e-resposta.md`,
`cacada-fala-2026-10-07-ancoras-de-rotina.md`,
`cacada-fala-2026-10-07-numeros-e-valores.md`,
`cacada-fala-2026-10-07-cadeia-de-audio.md`,
`cacada-fala-2026-10-07-instalacao-e-atualizacao.md` e
`cacada-fala-2026-10-07-multiplas-tarefas.md`.

Esta mede um eixo que nenhuma das outras mediu por inteiro: **o título — a fala que
entra e o nome que sai, medido em centenas de falas naturais.** As caçadas anteriores
esbarraram nele por acidente (o "feira" apagado, a vírgula que engole item, o valor preso
nele); aqui ele é o objeto.

Medições em cópia descartável (`/tmp/fala-cacada-titulo`, base `a614185`), parser real
(`LocalTaskParser`) com `FixedAppClock` em `2026-10-06 10:00 America/Sao_Paulo`. O título
esperado de cada fala foi calculado **fora do código** (à mão: os substantivos que ela
disse e que uma pessoa leria), nunca derivado da lógica do parser.

## Manchete

**183 falas medidas, 50 violações de perda de conteúdo.**

O título **nunca mente e nunca reordena** — em 73 falas a propriedade "o título é
subsequência da fala" segurou 73/73 (não inventou nome de outra tarefa, data, valor nem
nome próprio). O defeito vivo é o oposto: **ele come pedaços do que ela disse, e o
pedaço que ele come é sempre o primeiro item de uma lista com vírgula.**

| Oráculo | Casos | Violações |
|---|---|---|
| Item de lista antes da vírgula (`arroz, feijão e macarrão`) | 50 | 50 (100%) |
| Mesma lista sem vírgula (controle) | 30 | 0 |
| Conector `de` semântico (`conta de luz`, `remédio de pressão`) | 20 | 20 (100%) |
| Nome próprio / acento (João, Conceição, café, água, São Paulo) | 15 | 0 |
| Propriedade: título ⊆ fala (invenção/reordenação) | 73 | 0 |
| **Total** | **183** | **50** |

Suíte existente no clone: **1152 testes, 0 falhas** (contados nos XMLs, não no
"BUILD SUCCESSFUL"). O defeito da vírgula **não tem um único teste** que o pegue: nenhuma
fala da suíte usa `parse("... , ...")` como lista de compras — as únicas vírgulas testadas
são o decimal do dinheiro (`pagar R$ 1.234,56`), que é outro caminho.

## Defeito 1 (P0) — a vírgula de lista engole o primeiro item

`LocalTaskParser.kt:1674-1684` — `val rebuilt = original.split(Regex("\\s+")).filterIndexed { ... }`

O `extractTitle` reconstrói o título casando cada palavra da fala **original** com o
`leftover` (o texto já dobrado e sem os fillers). A palavra é dobrada e o casamento é
feito com `indexOfFirst { it == folded || folded.startsWith(it) }` — e o `folded` da
palavra **não tira a vírgula**. Só o `leftover` tira (linha 1676: `.trim(',', '.', '!', '?')`).

Resultado: a palavra com vírgula gruda nela (`arroz,`), e o casamento funciona **uma única
vez** (contra o `leftover` `arroz`), mas na **segunda** ocorrência — `feijão,` quando a
lista é "arroz, feijão e macarrão" — o `leftover` já não tem mais `feijão` (a vírgula o
consumiu antes, no índice 0 do `leftover`)... e a partir daí o casamento re-ancora no
**primeiro** item seguinte e **os itens anteriores à vírgula somem**. A regra é
"o primeiro item de cada segmento separado por vírgula é perdido".

| Input exato | O que o app faz | O que devia fazer |
|---|---|---|
| `comprar arroz, feijão e macarrão sábado` | título `Comprar feijão macarrão` — **"arroz" sumiu** | `Comprar arroz feijão macarrão` |
| `pagar luz, água e telefone sexta` | título `Pagar água telefone` — **"luz" sumiu** | `Pagar luz água telefone` |
| `comprar leite, pão e queijo amanhã` | título `Comprar pão queijo` — **"leite" sumiu** | `Comprar leite pão queijo` |
| `pagar água, luz, gás e internet` | título `Pagar gás internet` — **"água" e "luz" sumiram** | `Pagar água luz gás internet` |
| `comprar tomate, cebola, alho e batata` | título `Comprar alho batata` — **"tomate" e "cebola" sumiram** | `Comprar tomate cebola alho batata` |
| `comprar pão, leite, ovos e manteiga amanhã` | título `Comprar ovos manteiga` — **"pão" e "leite" sumiram** | `Comprar pão leite ovos manteiga` |

**Por que dói:** ela vai ao mercado com "arroz, feijão e macarrão" na lista e a agenda diz
`Comprar feijão macarrão`. O item perdido é o **primeiro** — justamente o que ela mais
provavelmente falou primeiro. É perda **silenciosa**: o título não fica vazio, não gera
nota, e o rascunho pode ficar `isComplete` (com data/hora), confirmando pela caixa rápida
em um toque. A fala mais natural da mãe é uma lista corrida — é o caminho comum, não a
borda.

Medido: **50/50 falas com vírgula perdem conteúdo; 0/30 idênticas sem vírgula perdem
qualquer coisa.** O defeito é a vírgula, e só ela.

## Defeito 2 (P1) — o "de" conector sai do título e muda o sentido

`LocalTaskParser.kt:2322` — `FILLERS` inclui `"de"` (e `"do"`, `"da"`, `"dos"`, `"das"`)

O `de` é filler **por design** — "me lembrar **de** tomar" é ruído. Mas nas falas abaixo o
`de` é o conector entre dois substantivos e **carrega sentido**: `conta de luz` ≠ `conta
luz`; `remédio de pressão` é a leitura natural de `remédio pressão`, mas `pão de queijo`
vira `pão queijo` (outra coisa). Ele não distingue o `de`-ruído do `de`-conector.

| Input exato | O que o app faz | O que devia fazer |
|---|---|---|
| `pagar a conta de luz amanhã` | título `Pagar conta luz` | `Pagar conta de luz` |
| `tomar remédio de pressão hoje às 20h` | título `Tomar remédio pressão` | `Tomar remédio de pressão` |
| `comprar pão de queijo` | título `Comprar pão queijo` | `Comprar pão de queijo` |
| `comprar leite de coco` | título `Comprar leite coco` | `Comprar leite de coco` |
| `festa de aniversário do João sábado à tarde` | título `Festa aniversário João` | `Festa de aniversário do João` |
| `comprar presente de aniversário pra neta` | título `Comprar presente aniversário neta` | `Comprar presente de aniversário pra neta` |

Medido: **20/20** falas com `de`-conector perdem o `de`. É menos grave que o Defeito 1
(a pessoa ainda entende "conta luz"), mas é a resposta à pergunta 3: **sim, palavras curtas
somem, e quando somem o sentido pode mudar.** Este é um trade-off de design (o filler é
desejado no `de`-ruído); fica registrado como o custo medido, não como bug de P0.

## O que NÃO é defeito (medido, para não inventar caça)

- **Nomes próprios e acentos sobrevivem.** `Maria`, `João`, `São Paulo`, `Conceição`,
  `Antônio`, `Inês`, `Zé`, `Bispo`, `café`, `água`, `açaí`, `Vitória`, `Belo Horizonte` —
  **15/15** intactos. O `fold` normaliza só para comparar; a reconstrução parte da fala
  original, então o acento e a caixa sobrevivem. Exceção cosmética: o título faz
  `replaceFirstChar` (linha 1687) e come o ponto do "Dr." (`consulta com o Dr. João` →
  `Consulta com João`, o "Dr." vira "João" sem o ponto) — o nome sobrevive, o tratamento
  não. Cosmético, não conta como violação.
- **O título não mente.** Nas 73 falas do teste de propriedade, o título foi **sempre uma
  subsequência** da fala (0/73 fora). Não troca o nome por outro, não inventa data, valor
  nem nome próprio, não reordena. A resposta à pergunta 4 é **não** para o que foi medido.
- **A ordem das palavras sobrevive.** Nenhuma reordenação medida; `levar a Maria no médico`
  → `Levar Maria médico` é perda de preposição, não troca de ordem.
- **O título não fica vazio** em nenhuma das 73 falas (0 vazios). O modo de falha é
  parcial (come pedaços), nunca total.

## Defeito 3 (P2) — o título trunca no widget a partir de ~37 caracteres

`app/src/main/res/layout/widget_agenda.xml:23-24` — `android:ellipsize="end"` + `android:maxLines="2"`

O widget entrega 3x2 células (203x220dp, `targetCellWidth=3`/`targetCellHeight=2`). Medi
inflando e medindo o layout em `GraphicsMode.NATIVE` com os títulos reais do corpus.

| Fato | Medida |
|---|---|
| Maior título que **não** corta | 40 caracteres (`Comprar remédio de pressão e de diabetes`) |
| Menor título que **corta** | 37 caracteres (`Pagar gasolina pedágio estacionamento`, perde 4 chars em reticências) |
| Limite de linhas | 2 (rotação típica) → ~2x20 chars de conteúdo |
| Títulos do corpus com >40 chars | poucos, mas existem (o caso da fisioterapia com 45) |

O widget tem `WidgetFonteTest` que prende que "Tomar remédio de pressão" (24 chars) não
corta — mas **nenhum título do tamanho real de uma lista de compras é testado.** Com o
Defeito 1 o título já chega mutilado; com uma lista longa o que sobra ainda pode ser
cortado. Não é o defeito central (a maioria dos títulos do corpus cabe em 2 linhas), mas é
o teto medido da pergunta 6: **acima de ~37-40 caracteres ela perde o fim do título.**

## Prova por mutação

Arquivo: `domain/src/main/kotlin/com/theopadilha/falaagenda/domain/parser/LocalTaskParser.kt`
sha256 original: `cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34`
Backup por `cp` (nunca `git checkout --`): `/tmp/LocalTaskParser.kt.orig`

Oráculo (`CacadaTituloCommaGuardTest`, escrito à mão, fora do código): a fala
`comprar arroz, feijão e macarrão sábado` diz três coisas; o título tem que conter
`arroz`, `feijão` e `macarrão`.

**1) Código original — o guard falha pelo motivo certo (o item antes da vírgula sumiu):**

```
$ ./gradlew :domain:test --tests "*CacadaTituloCommaGuardTest*" --rerun-tasks
CacadaTituloCommaGuardTest > oPrimeiroItemAntesDaVirgulaSobreviveAoTitulo FAILED
CacadaTituloCommaGuardTest > aContaDeLuzAntesDaVirgulaSobrevive FAILED
CacadaTituloCommaGuardTest > osTresItensSobrevivem FAILED
3 tests completed, 3 failed
```

**2) Mutação (uma só): neutralizar a vírgula nos dois pontos do `extractTitle`.**

```
linha 1664: - remaining.split(" ")
            + remaining.replace(",", " ").split(" ")
linha 1674: - original.split(Regex("\\s+"))
            + original.replace(",", " ").split(Regex("\\s+"))
sha256 mutado: 160cc2a0d403e0d5648b03261db92f29a5ed1ebcf8bc4ecea0ba8483abfa20aa
```

Resultado colado ao lado do comando:

```
$ ./gradlew :domain:test --tests "*CacadaTituloCommaGuardTest*" --rerun-tasks
BUILD SUCCESSFUL
```
XML: `TEST-...CacadaTituloCommaGuardTest.xml tests=3 fail=0 err=0`
(os 3 passam — prova que o defeito é a vírgula, e só ela.)

**3) Restauração por `cp` e conferência do sha:**

```
$ cp /tmp/LocalTaskParser.kt.orig <arquivo>
$ shasum -a 256 <arquivo>
cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34
SHA MATCH - restored
```

## O que NÃO confirmei

- **Não medi o caminho da IA.** Todo o corpus passou pelo `LocalTaskParser` (offline). O
  `HybridParser` pode trocar o título pela resposta do LLM (o defeito do
  `HybridParserTituloTest`); se a IA devolver a lista inteira, o Defeito 1 pode ser
  mascarado no aparelho **quando a rede e a IA estiverem ligadas** — e reaparecer offline.
  Não medi com `RemoteDraftParser` real.
- **Não medi o ASR (Vosk).** A vírgula pressupõe que o reconhecedor a emita. O Vosk pt-BR
  costuma não pontuar; se ele nunca emitir vírgula, o Defeito 1 só dispara com entrada
  digitada ou com um ASR que pontue. A fala "arroz, feijão e macarrão" com pausas pode
  chegar sem vírgula — e aí o título sai correto (`comprar arroz feijão e macarrão` passa).
  **Este é o elo mais fraco da caçada e o primeiro a medir antes de priorizar o fix.**
- **Não medi truncamento com fonte do sistema aumentada.** A medição do widget foi na
  densidade/fonte padrão do Robolectric; a mãe usa fonte grande (o app é para idosa) e o
  teto de 2 linhas cai. O limite de ~37-40 chars é otimista.
- **Não contei vírgula na notificação.** A notificação usa o mesmo `series.title`
  (`NotificationHelper.kt:249`), mas não medi se o sistema trunca o título do canal.
- **Não testei listas de 5+ itens com vírgula em todas** — a regra "perde o primeiro item
  de cada segmento" foi inferida de 50 casos e explica 100% deles, mas não provei para
  todo N.
