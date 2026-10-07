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

---

## Parte C — o caminho do alarme

Esta parte mede o eixo que as sete caçadas anteriores só tinham **lido** e declarado OK. Método:
`AlarmManager` real em Robolectric, notificação inspecionada nó a nó, dose semeada direto no DAO, e
a invariante *"instante futuro marcado ⇒ existe alarme armado"* cruzada sobre 42 ocorrências.

### F1 [ALTA] O alarme não fala e não tem som próprio — é o "áudio" que ela reclama

**Medido: zero referências a `TextToSpeech`/`tts`/`synthes`/`utterance` em todo `app/src` e
`domain/src`.** E a notificação, inspecionada:

```
canal: importance=4 sound=content://settings/system/notification_sound
notif: sound=null vibrate=null defaults=0 fullScreenIntent=null category=reminder ongoing=0
```

`NotificationHelper.showReminder` (`reminders/NotificationHelper.kt:119-129`) monta a notificação
**sem `setSound`, sem `setVibrate`, sem `setDefaults`, sem `setFullScreenIntent`, sem
`setOngoing`**. O único áudio é o som padrão do canal (`ensureChannel`, `:40-51`) — o mesmo plim de
qualquer mensagem, que **toca uma vez e para**. Não existe voz dizendo "está na hora do remédio".

**Cenário dela:** o remédio das 08:00 chega como uma notificação com o plim padrão. Um plim de um
segundo, na cozinha, com o celular na sala, não é um alarme. Este é o endereço mais provável da
queixa literal *"o áudio nunca funciona"* — o áudio **do alarme**, não o reconhecimento da fala dela.

### F2 [ALTA] O app diz "avisos OK" com o canal mudo

`NotificationHelper.reminderAlerts` (`:80-86`) só olha três coisas: permissão de notificação, canal
!= `IMPORTANCE_NONE`, e `importance < IMPORTANCE_DEFAULT`. Medido nos três ramos:

```
HIGH + som + vibra       -> alerts=OK  silent=false
HIGH + SEM SOM + vibra   -> alerts=OK  silent=false   <<<
HIGH + SEM SOM + SEM VIBRA -> alerts=OK  silent=false <<<
```

Um canal `IMPORTANCE_HIGH` cujo som foi posto em "Nenhum" **passa como saudável**. O cartão "sem
som" da home não aparece e o lembrete do remédio sai mudo, com o app achando que avisou. É a mesma
classe do defeito já corrigido para `IMPORTANCE_NONE` — o "HIGH mas mudo" ficou de fora.

### F3 [ALTA] Permissão de alarme exato negada ⇒ alarme inexato, e o único aviso é um cartão na home

Medido com `canScheduleExactAlarms=false`:
```
usedInexactAlarm no salvar = true
  trigger=...T11:00:00Z alarmClock=false allowWhileIdle=true
```
O app declara `SCHEDULE_EXACT_ALARM` (não `USE_EXACT_ALARM`) — `AndroidManifest.xml:6`. Essa
permissão **não é pré-concedida** em instalações novas com targetSdk ≥ 33. O onboarding pede
(`OnboardingScreen.kt:95-118`) e há cartão na home (`HomeScreen.kt:671`), mas o texto promete "pode
atrasar **alguns minutos**" — sob Doze/OEM o atraso não tem esse teto. Se ela tocar "Agora não",
todo lembrete passa a ser inexato.

### F4 [MÉDIA] A dose que não pôde ser avisada se perde 6 h depois — e a dose da noite se perde inteira

Medido com contador (dose semeada no DAO, celular desligado a noite toda, volta às 09:00):
```
TOTAL=24 PERDIDAS=24 TOCAM_ATRASADAS=0
+1h: rearmado=true  +5h: rearmado=true  +6h: rearmado=false  +24h: status=MISSED
```
`TaskRepository.JANELA_ENTREGA_PENDENTE = Duration.ofHours(6)` (`:871`), aplicada em
`OccurrenceLifecycle.entregaPendente`/`valeRearmar` (`:245-276`). Para um remédio, 6 h é janela
curta e o número não está em lugar nenhum que ela veja. **É decisão de produto** — já registrada
como item 2 das decisões abertas.

### F5 [MÉDIA] Tocar na notificação abre o formulário de edição, não o remédio

Medido: `contentIntent` é a activity com `OPEN_OCCURRENCE` + `occurrence_id`, e
`FalaAgendaRoot.kt:179-185` resolve via `agendaNotice` chamando **`openForEdit(pedido.item)`**. Ela
toca no aviso do remédio e cai num formulário, com o "Concluir" no fim de uma tela que rola (o PR
#70 mediu ~460 dp abaixo da dobra). O caminho "tocar no aviso e marcar tomei" não existe; os botões
"Concluir"/"Adiar" da própria notificação é que compensam.

### F6 [MÉDIA-BAIXA] A notificação do lembrete é apagável por swipe e não tem categoria de alarme

Medido: `ongoing=0`, `category=reminder`, `fullScreenIntent=null`. Ela passa o dedo e apaga o aviso
sem ler; nada re-toca até o próximo degrau (15 min) e, se era o último do dia, o lembrete morre
calado.

### F7 [BAIXA] Com notificação negada o app fica completamente mudo, inclusive no "não deu para adiar"

Medido: canal desligado → `lembrete=BLOCKED aviso=BLOCKED`. Ela toca "Adiar", nada é agendado e
nenhuma tela diz nada.

### Medido e OK neste eixo (não refazer)

- **A API é a certa**: `setAlarmClock` no primeiro aviso e `setExactAndAllowWhileIdle` nas
  repetições (`ReminderScheduler.kt:44-52, 135-141`), medido com `alarmClock=true` e `showIntent=true`.
- **A invariante do agendamento tem zero violações**: `TOTAL ocorrencias=42 futuras=35 VIOLACOES=0`.
- A dose das 22:00 fica armada exatamente às 22:00, com o alarme da virada do dia às 00:05 ao lado.
- **Nenhum `SecurityException`**: `canScheduleExact()` guarda todos os caminhos (`:44, 61, 81`).
- **`goAsync` + `withTimeout(8s)`** no receiver: ANR não é o defeito.
- **Boot re-arma de verdade** (medido com a `FalaAgendaApplication` de produção), e
  `RECEIVE_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` e
  `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` estão declarados e tratados.
- **A entrega funciona ponta a ponta** e o disparo atrasado pelo Doze não perde a dose.
- **DST** se comporta, e o cold start não deixa alarme órfão.

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

## Confirmação independente

As duas manchetes desta leva foram reconferidas pelo coordenador, com sonda própria rodada em
`origin/main` (`c22b588`), relógio fixo 20/08/2026 10:00:

```
N1|tomei                    |intent=Capture
N1|tomei o remédio          |intent=Capture
N1|já tomei o remédio       |intent=Complete(target=remedio)   <- controle
N4|meio-dia e um quarto     |time=12:00 title="Tomar remédio quarto" qc=true amb=false
N4|meio-dia e meia          |time=12:30 title="Tomar remédio"        <- controle
N4|um quarto pras três      |time=null  title="Tomar remédio quarto pras três"
N4|metformina 850           |title="Tomar metformina"
```

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
5. **F1 (o alarme não fala)** — é a decisão de maior impacto desta leva, porque é a que dá endereço
   à queixa literal do dono. Um alarme de remédio que toca o plim padrão **uma vez** e não insiste
   não é um alarme. Recomendação: voz (TTS `pt-BR`, "está na hora do remédio") **e** som próprio no
   canal, com `setOngoing` para o aviso não sair por swipe. Precisa do dono bater o martelo sobre o
   que a voz deve dizer.
6. **F3** — o texto do onboarding promete "pode atrasar alguns minutos" quando a permissão de alarme
   exato é negada. Sob Doze o atraso não tem esse teto. Recomendação: ou prometer o que acontece, ou
   insistir na permissão.
