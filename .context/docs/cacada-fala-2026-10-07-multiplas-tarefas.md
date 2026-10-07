# Caçada da fala — 2026-10-07 — DUAS OU MAIS TAREFAS NUMA SÓ FALA

**38 estados medidos, 83 violações** (9 delas perda SILENCIOSA de tarefa, 5 de item).

Eixo novo: nenhuma caçada anterior mediu o que acontece quando ela diz dois pedidos numa
fala só. O app **nunca cria duas tarefas** — em nenhum dos 38 estados. O melhor desfecho
possível hoje é um rascunho único marcado `ambiguous=true` com as duas tarefas coladas no
título, o segundo dia/hora jogados fora, e um aviso genérico. O pior é `ambiguous=false`:
um cartão com título contaminado, `canQuickConfirm=true`, salvo num toque.

## Resposta curta às perguntas centrais

1. **Cria duas?** Não. Nunca. Um rascunho → uma `TaskSeries` (`TaskRepository.kt:124`). Não
   existe API de múltiplos rascunhos (`parse` devolve `ParsedTaskDraft`, singular,
   `HomeViewModel.kt:763`; `HybridParser.parse` idem; o remoto `RemoteDraftParser.parse`
   também é singular). Não há função de "separar" em lugar nenhum do repo.
2. **Cada uma leva o seu campo?** Não. Quando o detector de duas tarefas dispara, ele **zera a
   hora** do rascunho (`LocalTaskParser.kt:168`) — as duas perdem a hora. Quando não dispara,
   as duas herdam a **primeira** data/hora e a segunda some. Há também campo cruzado: em
   `"ligar pro João amanhã e tomar água agora"` o rascunho fica `date=amanhã`, e o "agora"
   (=hoje) da segunda é perdido.
3. **A segunda sobrevive?** Não — some, e em 9 dos 38 estados **sem nenhum aviso**
   (`ambiguous=false`, sem nota). Perda silenciosa é o caso real e reproduzível.
4. **O que vai para o título?** A fala inteira, sem o verbo de ligação: `"Tomar remédio pagar
   conta"`. A lista de compras vira **uma** tarefa, e a vírgula ainda apaga o item do meio.

---

## Defeito 1 (P0) — a vírgula apaga o item do meio do título, CALADA

`domain/src/main/kotlin/com/theopadilha/falaagenda/domain/parser/LocalTaskParser.kt:1677`

Input exato: `"comprar pão, leite"`

- **O app faz:** `title="Comprar leite"`, `ambiguous=false`, `confidence=0.55`, sem nota. O
  "pão" some. Ela salva "Comprar leite" achando que anotou pão e leite.
- **Devia fazer:** título `"Comprar pão leite"` (é o que sai sem a vírgula) ou, no mínimo,
  sinalizar.

Causa: `extractTitle` monta o título a partir de `original.split(Regex("\\s+"))`. A palavra
`"pão,"` chega com a vírgula colada; ela é dobrada e limpa com `.trim(',', '.', '!', '?')`
virando `"pao"`, mas o match no `leftover` compara com `it` **sem** o mesmo trim:
`leftover.indexOfFirst { it == folded || folded.startsWith(it) }` — e `leftover` ainda guarda
`"pão,"` com a vírgula. Nenhum casamento → a palavra é descartada do título.

Família (todos silenciosos): `"comprar pão, leite, ovos"` → `"Comprar ovos"` (pão E leite
somem); `"comprar leite, pão e ovos"` → `"Comprar pão ovos"` (leite some); `"comprar pão,
leite, ovos e queijo"` → `"Comprar ovos queijo"` (pão e leite somem). Com a vírgula colada
(`"comprar pão,leite"`) o bug **não** ocorre — é a vírgula com espaço que dispara.

## Defeito 2 (P0) — conector textual cola duas tarefas com `qc=true`, CALADO

`LocalTaskParser.kt:1632` (o detector `looksLikeTwoTasks` só divide por `\be\b`)

Inputs exatos: `"tomar remédio às 8 depois pagar a conta amanhã"`, `"... também pagar ..."`,
`"... aí pagar ..."`, `"tomar água também caminhar"`.

- **O app faz:** `ambiguous=false`, `confidence=0.85`, `isComplete=true`,
  `canQuickConfirm=true`, `title="Tomar remédio pagar conta"`, `date=amanhã`, `time=08:00`.
  A caixa rápida salva as duas coladas num toque, sem aviso.
- **Devia fazer:** tratar como duas tarefas (ou no mínimo marcar `ambiguous`), como já faz com
  o "e".

Causa: `val clauses = glued.split(Regex("""\be\b"""))` — só o "e" divide orações. "também",
"depois", "aí" (e a vírgula) não são reconhecidos como conectores de duas tarefas. Nota: a
forma com "e" (`"pagar a conta e depois tomar remédio"`) **é** pega — pelo "e".

## Defeito 3 (P1) — segunda data/hora perdida, com aviso (segunda tarefa nunca nasce)

`LocalTaskParser.kt:159-169` (aplicação) e `:1618-1650` (detector)

Input exato: `"marcar médico terça e tomar remédio às oito"`

- **O app faz:** `ambiguous=true`, `localTime=null` (`:168` zera a hora), `date=terça`,
  `title="Médico tomar remédio"`. A segunda tarefa ("tomar remédio às oito") não existe como
  tarefa e o horário dela foi jogado fora.
- **Devia fazer:** duas tarefas — "marcar médico" terça, "tomar remédio" 08:00.

O detector marca, mas **não separa**: o desfecho é "Ambíguo para escalar/confirmar, nunca
adivinhar" (comentário `:161`). A segunda tarefa é perdida nos dois casos; a diferença é que
aqui ao menos há uma nota `"Parece haver mais de uma tarefa na mesma frase. Vamos separar?"`.
**Não há separação.** A pergunta fica sem resposta possível na UI (não existe fluxo de split).

## Defeito 4 (P1) — "marcar médico terça e fisioterapia quinta": as DUAS datas somem

`LocalTaskParser.kt:1192-1194`

Input exato: `"marcar médico terça e fisioterapia quinta"`

- **O app faz:** `date=null` (as duas datas descartadas), `ambiguous=true`,
  `title="Médico terça fisioterapia quinta"`.
- **Devia fazer:** terça para o médico, quinta para a fisioterapia (ou marcar cada data).

Causa: `if (days.size > 1) { return DateHit(null, remaining, true) }` — dois dias da semana
ditos viram "nenhuma data". Nem uma nem outra é preservada; a nota é a genérica
`"A data ficou ambígua."`. (Mesmo desfecho com `"marcar dentista terça e médico quinta"`.)

## Defeito 5 (P1) — "amanhã" engole o segundo dia, CALADO (campo cruzado)

`LocalTaskParser.kt:1082-1085`

Inputs exatos: `"caminhar amanhã e nadar sexta"`, `"caminhar amanhã e nadar sábado"`,
`"nadar sábado e caminhar amanhã"`, `"comprar pão hoje e leite amanhã"`.

- **O app faz:** `ambiguous=false`, `date=amanhã`, `title="Caminhar nadar sexta"`. O "sexta"
  (segundo dia) fica preso no título; a segunda tarefa herda a data da primeira. Silencioso.
- **Devia fazer:** duas tarefas, cada uma com o seu dia.

Causa: o ramo `\bamanha\b` casa primeiro (`:1082`) e devolve a data cedo, sem olhar o dia da
semana que sobra na frase; o `"sexta"` não é processado e sobra no `remaining`/título.
Em `"nadar sábado e caminhar amanhã"` o segundo dia (amanhã) é que manda, e o "sábado" da
primeira fica no título — a data do rascunho é a da **última** dita, não a de cada tarefa.

## Defeito 6 (P1) — vírgula entre duas orações cola tudo, CALADO

`LocalTaskParser.kt:1632` (vírgula não é conector)

Inputs exatos: `"pagar a conta, tomar remédio"`, `"pagar conta, tomar remédio, dormir"`,
`"levar a Maria, buscar o João"`.

- **O app faz:** `ambiguous=false`, `title="Pagar tomar remédio"` / `"Levar buscar João"` /
  `"Pagar tomar dormir"`. Silencioso, `qc` conforme data/hora.
- **Devia fazer:** duas tarefas (ou sinalizar).

Causa: mesma do Defeito 2 — só `\be\b` divide; a vírgula é ignorada como separador de oração.
Aqui há uma interação com o Defeito 1: `"pagar a conta, tomar remédio"` também perde "conta"
do título pelo mesmo `:1677`.

---

## O oráculo (independente) e o output cru

O oráculo está em `/tmp/oraculo.py`. Ele **não** deriva nada do parser: cada caso tem a
expectativa escrita à mão (nº de tarefas esperado, palavras-chave de cada tarefa, data/hora de
cada uma). A única coisa que ele lê do app é o **dump cru** do parser (título/data/hora/
ambiguous/qc/notas), produzido por um probe de teste no clone. Contagem de violações é feita
por regra explícita: tarefa perdida (esperado ≥2, parser devolve 1), item perdido (lista
única sem uma palavra), campo perdido (data/hora de um segmento que sumiu), título contaminado
(2+ segmentos representados no título único).

Hoje de referência do probe: 2026-08-20 (quinta). Segunda=24/08, terça=25/08, sexta=21/08,
sábado=22/08.

Output cru (`python3 /tmp/oraculo.py`):

```
ESTADOS MEDIDOS: 38
VIOLACOES: 83
POR TIPO: {'PERDA_SINALIZADA': 20, 'CAMPO_PERDIDO': 21, 'TITULO_CONTAMINADO': 28, 'PERDA_DE_ITEM': 5, 'PERDA_SILENCIOSA': 9}
```

- **PERDA_SILENCIOSA = 9**: duas tarefas esperadas, parser devolve 1, `ambiguous=false`, sem
  nota. Ex.: `"caminhar amanhã e nadar sexta"`, `"pagar a conta, tomar remédio"`,
  `"tomar remédio às 8 depois pagar a conta amanhã"`, `"tomar água também caminhar"`,
  `"comprar pão hoje e leite amanhã"`, `"nadar sábado e caminhar amanhã"`.
- **PERDA_SINALIZADA = 20**: duas tarefas, `ambiguous=true` — mas o segundo pedido não vira
  tarefa e o segundo dia/hora é descartado.
- **CAMPO_PERDIDO = 21**: a data/hora que pertencia a um segmento sumiu do rascunho único.
- **TITULO_CONTAMINADO = 28**: o título único carrega 2+ tarefas.
- **PERDA_DE_ITEM = 5**: item de lista de compras sumiu do título (Defeito 1).

Amostra do dump cru (do `OracleMultiplasTarefasProbe`, hoje 2026-08-20):

```
tomar remédio às 8 e pagar a conta amanhã      | title="Tomar remédio pagar conta" | date=2026-08-21 | time= | amb=true  | qc=false
comprar pão, leite e ovos                       | title="Comprar leite ovos"        | date=           | time= | amb=false | qc=false
caminhar amanhã e nadar sexta                   | title="Caminhar nadar sexta"      | date=2026-08-21 | time= | amb=false | qc=false
tomar remédio às 8 depois pagar a conta amanhã  | title="Tomar remédio pagar conta" | date=2026-08-21 | time=08:00 | amb=false | qc=true
tomar remédio às 8 também pagar a conta amanhã  | title="Tomar remédio também pagar conta" | date=2026-08-21 | time=08:00 | amb=false | qc=true
pagar a conta, tomar remédio                    | title="Pagar tomar remédio"       | date=           | time= | amb=false | qc=false
ligar pro João amanhã e tomar água agora        | title="Ligar pro João tomar água agora" | date=2026-08-21 | time= | amb=true | qc=false
```

---

## Prova por mutação (uma mutação por execução)

`LocalTaskParser.kt` no clone, sha256 pristine:
`cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34` (2331 linhas).
Restauração sempre por `cp /tmp/LocalTaskParser.orig.kt <destino>`; sha conferido após cada
restauração. `git checkout --` nunca foi usado.

### Mutação 1 — Defeito 1 (vírgula)

Linha 1677, de:
`val idx = leftover.indexOfFirst { it == folded || folded.startsWith(it) }`
para:
`val idx = leftover.indexOfFirst { it.trim(',') == folded || folded.startsWith(it.trim(',')) }`

- sha pós-mutação: `22b04d6e269fa353d28a14e05592b416e6cc58ad86040a51bdb7b076d7eae6a8`
- Resultado: os testes de vírgula viram PASS; os de conector continuam FAIL →
  `tests="6" failures="4"` (eram `6/6`).
- **Suite existente, só com a Mutação 1, sem meus testes:** `tests=439 failures=0 errors=0`
  (`BUILD SUCCESSFUL`). Ou seja: o defeito da vírgula é **inteiramente não testado** — a suíte
  verde não o vê.
- sha restaurado: `cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34` ✅

### Mutação 2 — Defeito 2 (conectores "também"/"aí"/"depois")

Linha 1632, de:
`val clauses = glued.split(Regex("""\be\b"""))`
para:
`val clauses = glued.split(Regex("""\b(e|tambem|ai|depois(?!\s+do\b|\s+da\b|\s+de\b))\b"""))`

- sha pós-mutação: `66a762e1eac569d993af2cc935c70dcc9b63c7588a34fb3edbbb1bd683872352`
- Resultado: os 3 testes de conector viram PASS → `tests="6" failures="3"` (eram `6/6`).
- **Suite existente, só com a Mutação 2 refinada:** `tests=439 failures=0 errors=0`
  (`BUILD SUCCESSFUL`). O defeito dos conectores também é não testado.
- sha restaurado: `cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34` ✅

Nota de honestidade sobre a Mutação 2: a **primeira** versão, sem o `(?!\s+do\b|\s+da\b|\s+de\b)`,
quebrou um teste **não relacionado** (`LocalTaskParserTest > f4DepoisDoJantarComHoraDaMadrugadaNaoViraTarde`)
porque "depois do jantar" é uma pista de período, não um conector de tarefas. Refinei o
lookahead para isolar o defeito real; com ele, a suíte fica verde e só os 3 testes de conector
viram PASS. Uma mutação por execução, como pedido.

### Verificação final

- Suíte completa no clone restaurado: `domain 439 / 0 falhas`, `app 706 / 0 falhas`
  (contadas pelos XMLs, não pelo "BUILD SUCCESSFUL"). Igual à linha de base.
- sha do parser final = pristine `cec4463…` ✅

---

## O que eu NÃO confirmei (honestidade)

- **Nada foi rodado no app real / emulador.** Toda a evidência é do parser (domínio, Kotlin
  puro) e da leitura do caminho de gravação. O `canQuickConfirm=true` e o "um rascunho → uma
  `TaskSeries`" são lidos do código (`HomeScreen.kt:1017`, `TaskRepository.kt:124`), não
  observados numa tela.
- **O caminho da IA (remoto) não foi exercitado.** O `HybridParser.mergeRemote` funde **um**
  rascunho remoto num **um** local; ele não cria duas tarefas. Não medi se a IA responde duas
  tarefas num payload só (o schema parece singular). Não afirmo comportamento do remoto.
- **A camada de intenção (`SpeechIntentClassifier`) não foi medida neste eixo.** Ela roda
  antes do parser; um dos casos do enunciado ("lembrar de tomar água e ligar pro João") é
  captura, mas frases com verbo de comando poderiam ser desviadas antes do parser. Não testei.
- **"depois de amanhã" como conector** não foi medido (a Mutação 2 o exclui de propósito por
  ser pista de período). O defeito de conector vale para "depois" isolado entre tarefas.
- **O número de itens de lista perdidos por vírgula** foi medido em 5 casos pontuais; não
  varri sistematicamente combinações de 4+ itens com vírgula em todas as posições.
- **A "segunda tarefa sobrevive?" foi respondida só pelo parser.** Não verifiquei se a UI de
  confirmação (`ConfirmDraftScreen`) oferece algum fluxo manual que recrie as duas tarefas;
  pelo que li, não — a tela só edita os campos do rascunho único.
- **Amostra de 38 estados** (20+18). O espaço real de falas é maior; 38 é o que medi.
