# Caçadas — a segunda leva (06/10/2026)

Segunda leva de caçadas, feita **depois** de mergear a primeira (PR #71). Base: `c22b588`.
Onde a medição foi contra um PR aberto, o PR está nomeado.

Método: parser rodado com **relógio fixo** (quinta 20/08/2026 10:00, `America/Sao_Paulo`, o mesmo
do `LocalTaskParserTest`), ~494 frases cruzadas, e **oráculos que contam violações** — não exemplos
soltos. É a técnica que achou os últimos achados reais quando a suíte verde não pegou nenhum.

Duas seções: **(A)** fala/entendimento, **(B)** o que já estava correto — para não virar trabalho à
toa depois. O que não virou PR está marcado como tal.

---

## Parte A — itens novos

### N1 [ALTA] A resposta dela ao alarme vira tarefa nova — 35 de 40

`SpeechIntent.kt:184` (`jaFiz`) exige o **`já`** antes do verbo. Sem ele o gatilho não existe e a
fala cai em `Capture`.

| ela fala (o que ela responde quando o alarme toca) | o app faz hoje |
|---|---|
| `tomei` | tarefa **"Tomei"** — `Capture`, `data=null`, `hora=null`, `qc=false` |
| `tomei o remédio` | tarefa **"Tomei remédio"** |
| `tomei o remédio da pressão` | tarefa **"Tomei remédio pressão"** |
| `tomei a Losartana` | tarefa **"Tomei Losartana"** |
| `tomei sim` | tarefa **"Tomei sim"** |
| `já tomei o remédio` (controle) | `Complete(target=remedio)` — funciona |

`tomei` é a forma mais curta e a mais provável de uma idosa respondendo a um alarme. O app cria um
rascunho com título "Tomei" e **a dose segue pendente, com o alarme ainda armado**. Ela respondeu,
achou que registrou, e o remédio continua tocando.

**N1b [MÉDIA] — `já tomei` sem alvo nunca conclui nada.** `SpeechIntent.kt:282` devolve `Complete("")`
e `SpeechTargetMatcher.resolve` devolve `None` para alvo vazio (`:68`, `alvo.length < 3`) →
`HomeViewModel.kt:873` responde **"Não achei nenhuma tarefa com esse nome."** Existe teste prendendo
o comportamento (`FalaComandoTest.jaTomeiNaoCriaTarefaJaTomei`), então é decisão — mas o desfecho
para ela é um "não achei" logo depois de ela dizer que tomou.

**N1c [MÉDIA] — não existe intenção de soneca.** `UnsupportedKind` (`SpeechIntent.kt:40-49`) só tem
`CHANGE` e `ERASE`. Medido, tudo virou `Capture` com título-lixo: `depois` → título **vazio**;
`depois eu tomo` → **"Eu tomo"**; `espera` → **"Espera"**; `espera um pouco` → **"Espera pouco"**;
`não posso agora` → **"Não posso agora"**; `mais tarde` → **"Mais"**; `deixa pra depois` →
**"Deixa"**; `tomo depois do almoço` → **"Tomo"**. O alarme tem o botão "Adiar 30 min" na
notificação (`strings.xml:20`) mas **não tem o equivalente por voz** — e "espera"/"depois" é
exatamente o que ela diria.

### N2 [MÉDIA-ALTA] Marco do dia que não é hora: vira título calado — 17 de 17

`extractPeriodHint` (`LocalTaskParser.kt:727-764`) só conhece `depois do almoço`, `jantar`, as
faixas `meio/começo/fim de X` e `à noite/à tarde/de manhã`. Qualquer outro marco do dia **não é
consumido, não marca ambíguo e entra no título**:

| ela fala | o app faz hoje |
|---|---|
| `tomar remédio depois da novela` | título **"Tomar remédio novela"**, `hora=null`, `amb=false` |
| `tomar remédio na hora do almoço` | título **"Tomar remédio almoço"**, `amb=false` |
| `tomar remédio quando eu acordar` | título **"Tomar remédio quando eu acordar"**, `amb=false` |
| `tomar remédio bem cedinho` | título **"Tomar remédio bem cedinho"**, `amb=false` |
| `tomar remédio de tardinha` | título **"Tomar remédio tardinha"**, `amb=false` |
| `tomar remédio na hora de dormir` | título **"Tomar remédio dormir"**, `amb=false` |
| `tomar remédio depois do almoço` (controle) | título **"Tomar remédio"**, `amb=true` + nota |

A assimetria é a prova: `depois do almoço` é tratado, `depois da novela` não. O marcador fica **no
nome da tarefa** e o app não liga "Falta o horário" a ele.

### N3 [MÉDIA] Faixa de dias não existe — só a forma com "toda"

| ela fala | o app faz hoje |
|---|---|
| `tomar remédio de segunda a sexta às oito` | `rec=NONE`, título **"Tomar remédio segunda sexta"**, `amb=true` |
| `tomar remédio todo sábado e domingo às oito` | `rec=NONE`, título **"... todo sábado domingo"** |
| `tomar remédio nas segundas e quintas às oito` | `rec=NONE`, título **"... segundas quintas"** |
| `tomar remédio toda segunda e quinta às oito` (controle) | `rec=WEEKLY [MON, THU]`, `qc=true` |

`extractRecurrence` (`:301-335`) exige o prefixo `todas as`/`todos os`/`toda`. Sem ele, os dias ficam
no texto e `extractDate` devolve `DateHit(null, …, ambiguous=true)` (`:717-719`). A frase mais
natural — **`de segunda a sexta`** — é justamente a que não casa. `nos dias úteis` funciona
(`WEEKDAYS`), o que mostra que a intenção existe no modelo e só falta a expressão.

### N4 [MÉDIA] Hora por referência ao relógio — e um caso crava a hora errada com `qc=true`

`CLOCK_BARE_WORD` (`:983`) só aceita `X e <minuto>`; `um quarto pras três` e `dez pras três` não têm
ramo nenhum.

| ela fala | o app faz hoje |
|---|---|
| `tomar remédio amanhã um quarto pras três` | `hora=null`, `amb=false`, título **"... quarto pras três"** |
| `tomar remédio amanhã dez pras três` | `hora=null`, `amb=false`, título **"... dez pras três"** |
| `tomar remédio amanhã às três menos vinte` | `hora=**03:00**`, título **"... menos vinte"** |
| **`tomar remédio amanhã meio-dia e um quarto`** | **`hora=12:00`** (devia 12:15), **`qc=true`**, `amb=false`, título **"Tomar remédio quarto"** |

O último é o pior da família: **12:00 em vez de 12:15, sem ambiguidade e com a caixa rápida
disponível** — confirma em silêncio uma hora que ela não disse. Causa: `trailingMinutes` (`:528`) só
reconhece `MINUTE_TAIL_WORDS` = `meia/quinze/vinte/trinta/quarenta/cinquenta`; `um quarto` não está
lá, então o "quarto" sobra e a hora fica no valor base.

### N5 [MÉDIA] Dígito no título é descartado — a dose some do nome da tarefa

`extractTitle` (`:920`) filtra todo token que casa `\d+h?`, não só a hora:

| ela fala | título hoje |
|---|---|
| `tomar metformina 850 amanhã às oito` | **"Tomar metformina"** — o 850 sumiu |
| `tomar Losartana 50 miligramas amanhã às oito` | **"Tomar Losartana miligramas"** — o 50 sumiu |
| `tomar 2 comprimidos amanhã às oito` | **"Tomar comprimidos"** |
| `comprar 2 quilos de arroz amanhã às oito` | **"Comprar quilos arroz"** |
| `pagar 30 reais amanhã às oito` | **"Pagar reais"** |

`metformina 850` é literalmente como a caixa do remédio dela diz. Nenhum teste prende isso. O PR #67
introduz `amountCents` para "30 reais", mas o título continua "Pagar reais" — são fixes diferentes.

### N6 [MÉDIA] Dúvida vira tarefa agendada — 12 de 12

| ela fala | o app faz hoje |
|---|---|
| `não é amanhã a consulta` | título **"Não consulta"**, `data=2026-08-21`, `amb=false` |
| `acho que é amanhã a consulta` | título **"Acho consulta"**, `data=2026-08-21` |
| `será que eu tomo o remédio amanhã` | título **"Será eu tomo remédio"**, `data=2026-08-21` |
| `não sei se é amanhã` | título **"Não sei se"**, `data=2026-08-21` |

Sobreposição reconhecida: o item 1 do catálogo anterior cobre `não é`. O que é **novo** é a família
`acho que / será que / não sei se / talvez / me confirma se`, que não é correção e não está em
nenhum PR aberto.

### N7 [MÉDIA] `à uma da tarde` não é hora nenhuma

`CLOCK_WORD` (`:978`) e `CLOCK_BARE` (`:988`) exigem `\bas\s+`, e `TextNormalizer.fold` transforma
`à` em `a`, que não é `as`. Medido: `à uma da tarde` e `à uma da manhã` → `hora=null`, `amb=true`
(não inventa hora errada — bom), enquanto `às onze da manhã` → 11:00. A forma "à uma" é comum na
fala dela.

### N8 [BAIXA-MÉDIA] Resíduos de data

- **`fisioterapia todo dia primeiro às nove`** → `rec=DAILY`, `qc=true`, título **"Fisioterapia
  primeiro"** — a palavra `primeiro` não é consumida. A parte `DAILY` é **decisão presa por teste**
  (`LocalTaskParserTest.kt:555-556`, "todo dia 5 é todo dia"); o resíduo no título é defeito.
- **`pagar o aluguel todo dia 5 às dez`** → `rec=DAILY`, `data=hoje`, `qc=true`. Uma conta mensal
  vira tarefa **diária começando hoje**. Mesma regra presa por teste — é decisão de produto a
  revisitar, não bug acidental.
- `fisioterapia semana que vem na quinta` → **`data=hoje`** com "semana vem" no título, `amb=false`.

### N9 [MÉDIA] Datas nomeadas na ordem real da fala — medido no head do PR #68 (`b38ebc1`)

A guarda `commonNounUse` (`:956-967`) exige que a palavra antes do nome de festa esteja em
`DATE_DETERMINERS`. **Quando o nome da tarefa vem antes, a guarda rejeita a data** e o nome fica no
título, calado:

| ela fala | app no #68 |
|---|---|
| `fisioterapia no Natal` | **`data=null`**, título "Fisioterapia Natal", `amb=false` |
| `consulta no Natal às nove` | **`data=null`**, título "Consulta Natal" |
| `dia de Natal` | `data=2026-12-25` ✅ mas título **"Dia"** (devia "Natal") |
| `no Natal fisioterapia` (controle) | `data=2026-12-25`, título "Fisioterapia" ✅ |

`fisioterapia no Natal` é a ordem em que ela **realmente fala** (a tarefa primeiro). A guarda existe
para separar "terra natal", mas "fisioterapia" antes de "no Natal" não é substantivo comum.
**Depende de o #68 landar antes** — o fix é no arquivo que o #68 está reescrevendo.

---

## Parte B — o que já estava correto (não refazer)

- **Hora exata, por extenso ou dígito: 20/20 corretos** com `qc=true` e título limpo — inclui
  `às 8 da noite` → 20:00, `às doze da noite` → 00:00, `meia-noite e meia` → 00:30, `às 8h30`.
- **Hora por extenso sem o "às"** (`oito e meia`, `duas e vinte da tarde`) funciona.
- **Períodos que já têm ramo**: `depois do almoço`, `à noite`, `de manhã`, `à tarde`, `à noitinha`,
  faixas `meio/começo/fim de X` — todos com nota de inexatidão e título limpo.
- **Duas tarefas**: o guard `looksLikeTwoTasks` pega 6/8 (`amb=true`). As 2 falhas têm vírgula — é o
  mesmo eixo do item 3 do catálogo anterior, não um achado novo.
- **Dose repartida**: 12/12 marcaram `amb=true`. O título fica sujo ("Tomar meio") mas o aviso existe.
- **Datas que já funcionam**: `depois de amanhã`, `semana que vem`, `no dia 5`, `dia 5 de setembro`,
  `na segunda que vem`, `próxima segunda`, `no começo do mês`, `depois do dia 10`, `toda segunda e
  quinta`, `nos dias úteis`, `toda quinta que vem`.
- **Títulos com o nome real do remédio**: `Losartana`, `metformina`, `comprimido branco`,
  `remedinho`, `remédio da pressão`, `colírio`, `insulina`, `pomada`, `injeção` — todos preservados.
  Nomes de médico com "Dr."/"Dra." também.
- **Datas relativas que o #68 cobre**: `daqui a três dias`, `daqui a duas semanas`, `no meio do mês`,
  `no fim do mês` — passam a resolver no head do #68. Não reportados como defeito de `main`.

---

## Números

Comando (idêntico em todas as rodadas; XMLs são a fonte, nunca o "BUILD SUCCESSFUL"):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :domain:test --rerun-tasks --max-workers=2 --tests "…ProbeCacaFala*"
```

Contadores de violação em `main` @ `c22b588`:

```
ORACLE_HORA      total=20 erradas=0  nulas=0  comResiduoNoTitulo=0
ORACLE_VAGA      total=20 inventouHora=1 naoAmbiguo=17 vazouNoTitulo=14
ORACLE_ALARME    total=40 viraramCapture=35
ORACLE_TOMEI     total=24 viraramCapture=16
ORACLE_ANCORA    total=17 vazouOuCalado=17
ORACLE_DUVIDA2   total=12 virouTarefa=12
ORACLE_REF       total=12 semHora=7 tituloComExpressaoDeHora=10
ORACLE_DATA      total=30 semData=16 vazouNoTitulo=18
ORACLE_DATAREL   total=20 semData=18
ORACLE_DIAPRIMEIRO total=10 naoVirouMensal=8
ORACLE_DUAS      total=8  colouSemAviso=2
ORACLE_REPARTE   total=12 semAviso=0
```

**Diferencial que prova que N1–N7 são novos** — os mesmos oráculos rodados no head do PR #68
(`b38ebc1`) dão **exatamente os mesmos números** para `ALARME`, `TOMEI`, `ANCORA`, `VAGA`,
`FAIXADIA`, `REF`, `NUMERO` e `HORA2`. Só os oráculos de data mudam (`DATAREL` 18 → 11,
`DATA` 16 → 10). Ou seja: os itens de fala/alarme **não** são o que os PRs abertos já corrigem.

---

## PENDENTE: (decisões de produto, não bug)

1. **N1** — aceitar o verbo em primeira pessoa no passado (`tomei`) **só quando o alvo casa com a
   agenda**? Recomendação: sim, com alvo obrigatório — evita "Comprei pão" virar conclusão.
2. **N2** — tratar marco do dia (`depois da novela`) como "não é hora exata" (ambíguo, limpa o
   título) ou como âncora real (≈21h)? Recomendação: o ambíguo — o app já se recusa a inventar hora,
   e cravar a novela é chute.
3. **N8** — manter `todo dia 5` como diário (regra presa por teste) ou tornar mensal? Recomendação:
   manter e só limpar o resíduo de `primeiro`, porque mudar a regra regride o teste de `:555`.
4. **N9** — depende de o #68 landar. Abrir em cima depois.
