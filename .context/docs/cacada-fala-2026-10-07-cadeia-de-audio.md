# Caçada — a cadeia de áudio de ponta a ponta (2026-10-07)

Sexta leva de caçadas de fala. As anteriores mediram **peças** da cadeia (o canal #77, o volume
#81, o TTS #80). Esta mede o **caminho inteiro**, do alarme armado até a onda sonora, e mede o
que ninguém mediu: o **produto cartesiano dos sete elos**.

## Manchete

**De 128 estados do produto cartesiano dos sete elos, 14 violam o invariante — e os sete elos nunca
foram medidos juntos.** São dois defeitos distintos:

- **2 estados dizem "avisos OK" com o alarme mudo.** É a **combinação** de dois elos meio-quebrados
  que nenhum guard olha junto: um **canal alto com o som em "Nenhum" mas ainda vibrando** (elo 5 — o
  `channelSilenced` só conta o canal como mudo quando `sound == null && !shouldVibrate`) mais um
  **aparelho sem voz em português** (elo 7 — **zero** verificação de TTS no caminho). O cartão da
  home não aparece, a notificação não tem som, a voz não sai.
- **12 estados pedem a voz com o stream de alarme em zero.** O aplicativo *sabe* que o volume está
  zerado (o veredito é `APARELHO_MUDO`/`QUIET`) e manda falar assim mesmo, no mesmo stream mudo.

O elo 7 é o único **sem guarda nenhuma** em toda a cadeia. E o resíduo assíncrono dele mente na
barra em **2 de 2** estados: a notificação afirma "Avisando em voz alta" quando a síntese falha
depois de o motor aceitar a frase.

## Método

Clone descartável (`git clone` de `main` @ `7c83992`), árvore do **produto** formada com o
`git merge` de `feat/fala-alarme-que-fala` (#80) sobre `main` — ver a seção *Nota de escopo* sobre
por que a voz não está em `main`. Robolectric 4.14.1, sdk 34. Três classes de oráculo em
`app/src/test/java/.../platform/` (descartáveis, não fazem parte do produto):

- `CadeiaDeAudioOracleTest` — **ParameterizedRobolectricTestRunner**, um estado por método, sobre o
  produto cartesiano de {permissão} × {importância do canal} × {som} × {vibração} × {volume do
  alarme 0|1}. Mede dois invariantes:
  - **A**: `reminderAlerts == OK` ⇒ o alarme **soa** (o "soa" é o fato do setup: permissão,
    importância ≥ DEFAULT, canal com som, volume > 0 — nunca recalculado com o código do
    `reminderAlerts`).
  - **B**: a voz só é pedida (`deveFalarOlembrete(POSTED)`) quando o stream de alarme pode levá-la
    (volume > 0).
- `CadeiaDeAudioAsyncTest` — o resíduo **assíncrono** do elo 7: o motor aceita a frase e a síntese
  falha depois (`onError`), e o motor aceita mas o stream está em 0.
- `CadeiaDeAudioCombinacaoTest` — a **combinação** (elo 5 × elo 7) com caso de controle.

> **Shadow que faltava, escrito:** `ShadowAudioManagerComMinimoDeAlarme` (mínimo do alarme = 1,
> o real do Android 9+) já existia em `main` e foi usado. O `ShadowTextToSpeech` não foi usado no
> eixo do aceite — ele **sempre devolve SUCCESS no `speak`**, então o ramo `ERROR` do
> `VozDoAparelho` só se mede pela costura `SintetizadorDeVoz`, que é o que `CadeiaDeAudioAsyncTest`
> faz.

## Oráculo — o espaço medido (elo × estado) e a contagem

`CadeiaDeAudioOracleTest`: **128 estados** (2 permissões × 4 importâncias × 2 som × 2 vibração ×
2 volumes), 256 asserções (A e B por estado). **14 violações.** Output cru (resumo e falhas):

```
### CadeiaDeAudioOracleTest: tests=128 failures=14 errors=0

[FAIL] A: [perm=true canal-padrao som=false vibra=true vol=1] veredito=OK diz OK mas o alarme NAO soa
[FAIL] A: [perm=true canal-alto   som=false vibra=true vol=1] veredito=OK diz OK mas o alarme NAO soa
[FAIL] B: [perm=true canal-baixo  som=true  vibra=true  vol=0] veredito=QUIET        pede a voz com volume=0
[FAIL] B: [perm=true canal-baixo  som=true  vibra=false vol=0] veredito=QUIET        pede a voz com volume=0
[FAIL] B: [perm=true canal-baixo  som=false vibra=true  vol=0] veredito=QUIET        pede a voz com volume=0
[FAIL] B: [perm=true canal-baixo  som=false vibra=false vol=0] veredito=QUIET        pede a voz com volume=0
[FAIL] B: [perm=true canal-padrao som=true  vibra=true  vol=0] veredito=APARELHO_MUDO pede a voz com volume=0
[FAIL] B: [perm=true canal-padrao som=true  vibra=false vol=0] veredito=APARELHO_MUDO pede a voz com volume=0
[FAIL] B: [perm=true canal-padrao som=false vibra=true  vol=0] veredito=APARELHO_MUDO pede a voz com volume=0
[FAIL] B: [perm=true canal-padrao som=false vibra=false vol=0] veredito=QUIET        pede a voz com volume=0
[FAIL] B: [perm=true canal-alto   som=true  vibra=true  vol=0] veredito=APARELHO_MUDO pede a voz com volume=0
[FAIL] B: [perm=true canal-alto   som=true  vibra=false vol=0] veredito=APARELHO_MUDO pede a voz com volume=0
[FAIL] B: [perm=true canal-alto   som=false vibra=true  vol=0] veredito=APARELHO_MUDO pede a voz com volume=0
[FAIL] B: [perm=true canal-alto   som=false vibra=false vol=0] veredito=QUIET        pede a voz com volume=0
```

Contagem: **invariante A = 2 violações**, **invariante B = 12 violações**. As 64 linhas com
`perm=false` (notificação bloqueada ⇒ o app não diz OK e a voz não é pedida) passam todas — é o
**caso de controle** do eixo: a medição distingue os dois mundos. A violação A só aparece no
degrau `vol=1` (audível) porque no `vol=0` o veredito cai em `APARELHO_MUDO`, que já não é OK.

`CadeiaDeAudioAsyncTest` (resíduo assíncrono): **2 de 2 falham** —

```
### CadeiaDeAudioAsyncTest: tests=2 failures=2
[FAIL] motorAceitaESinteseFalha_aBarraAfirmaQueFalou
       VIOLACAO: soou=false; barra afirma 'falando' antes=true depois=true
[FAIL] motorAceitaMasOStreamDeAlarmeEstaMudo_aBarraAfirmaQueFalou
       VIOLACAO: volume de alarme=0 (audivel=false); barra afirma 'falando'=true
```

`CadeiaDeAudioCombinacaoTest`: **1 de 2 falha** (a outra é o controle, verde) —

```
### CadeiaDeAudioCombinacaoTest: tests=2 failures=1
[PASS] controle_canalComSomMaisVozQueSai_appDizOkESomSai
[FAIL] canalVibrandoSemSomMaisAparelhoSemVoz_appDizOkESomNaoSai
       VIOLACAO (combinacao): veredito=OK diz OK; canal.sound=false, falas que soaram=0; nada soa
```

## P0 — A combinação: canal vibrando sem som **mais** aparelho sem voz → `OK` mudo

**Estado exato:** canal `IMPORTANCE_HIGH` com `setSound(null,null)` e `enableVibration(true)`
(o "Nenhum" que ainda vibra), volume do alarme = **1** (audível, para o veredito não ser salvo pelo
cartão do volume), e o aparelho **sem voz em português do Brasil** (o motor nunca fica pronto).
**Obtido:** `reminderAlerts == OK` (nenhum cartão na home), `canal.sound == null`, `falas que
soaram == 0`. **Esperado:** não-OK, ou uma superfície que diga que a voz não sai.
**Output:** `VIOLACAO (combinacao): veredito=OK diz OK; canal.sound=false, falas que soaram=0; nada soa`.

**Por que importa pra ela:** o celular dela tem a voz do sistema em pt-BR? Ninguém sabe — o
aplicativo nunca pergunta. Se não tiver (ou se o motor falhar), o único som é o do canal; com o
canal em "Nenhum", não há som nenhum, e a home diz que está tudo bem. É literalmente "o áudio
nunca funciona" com o app afirmando o contrário.

**A raiz:** os dois elos meio-quebrados (o `channelSilenced` que tolera canal vibrando, e a ausência
de qualquer guarda de TTS) são **independentes** e nenhum guard olha os dois — é o produto
cartesiano que as caçadas por peça não veem.

## P0 — Elo 7 sem guarda nenhuma: a voz é pedida num aparelho de alarme mudo

**Estado exato:** permissão ok, canal saudável, **volume do alarme = 0**, e o app pede a voz.
**Obtido:** `deveFalarOlembrete(POSTED) == true` e `LembreteFaladoService.falar(...)` é chamado —
a fala sai no `STREAM_ALARM`, que está em zero. O veredito é `APARELHO_MUDO` (ou `QUIET`), mas o
caminho do alarme **não lê o veredito**: ele chama `showReminder` → `POSTED` → `falar`.
**Esperado:** com o stream de alarme em 0, a voz não teria como ser ouvida; ou o app não a pede, ou
diz que não vai sair. **Contagem:** 12 dos 128 estados.

**Output cru (amostra; os 12 estão listados acima):**
```
[FAIL] B: [perm=true canal-alto som=true vibra=true vol=0] veredito=APARELHO_MUDO
       pede a voz (deveFalarOlembrete=true) com o volume de alarme em 0; nada impede a fala inaudivel
```

**Por que importa pra ela:** o aplicativo **sabe** que o volume está em zero (foi o #81 que
ensinou isso) e, mesmo assim, manda a voz falar no mesmo stream mudo. É uma guarda no elo 6 que
não chega ao elo 7.

## P1 — Elo 5, guarda no lugar errado: canal alto sem som mas vibrando passa por `OK`

**Estado exato:** `IMPORTANCE_HIGH`/`DEFAULT`, `setSound(null,null)`, `enableVibration(true)`,
volume = 1. **Obtido:** `reminderAlerts == OK`. **Esperado:** o canal não tem som — o "Nenhum" com
vibração não é "avisos valendo" para quem está longe do celular. **Output:**
`[perm=true canal-padrao som=false vibra=true vol=1] veredito=OK diz OK mas o alarme NAO soa`.
**Contagem:** 2 estados (canal-padrao e canal-alto).

**A raiz:** `channelSilenced` exige `sound == null && !shouldVibrate()`. A vibração é tratada como
prova de que o canal não está mudo — mas vibração não é som, e ela não é avisada por vibração com o
celular na sala. O KDoc diz "um canal que ainda vibra não está mudo"; para o invariante do áudio,
está.

**Por que importa pra ela:** o cartão "sem som" não aparece, ela não sabe onde arrumar, e o
lembrete sai sem fazer barulho.

## P1 — Elo 7, a barra afirma "Avisando em voz alta" quando a síntese falha depois

**Estado exato:** o motor **aceita** a frase (`speak` devolve SUCCESS) e a síntese falha depois,
pelo `onError` — voz corrompida, idioma listado sem dado de fala, motor ocupado. **Obtido:** o
`aoFalar()` dispara na aceitação, a notificação de primeiro plano passa a dizer "Avisando em voz
alta", e **nada retrata a afirmação quando o erro chega**. `soou=false`, barra afirmando antes e
depois. **Esperado:** a barra só afirma a fala quando ela sai.
**Output:** `VIOLACAO: soou=false; barra afirma 'falando' antes=true depois=true`.

**Por que importa pra ela:** é o resíduo explícito desta caçada — a falha **assíncrona** do motor. O
#80 fechou a metade do motor que não sobe (`quandoPronto(false)`); esta é a outra: o motor sobe,
aceita, e falha na síntese. Para ela, a barra dizendo "Avisando em voz alta" enquanto o celular
está calado é indistinguível de "o áudio nunca funciona".

## P1 — Elo 7, a mesma barra afirma com o stream em zero

**Estado exato:** volume do alarme = 0, motor aceita e "fala". **Obtido:** a barra afirma "Avisando
em voz alta" (`afirmou=true`) com `audivel=false`. **Esperado:** não afirmar. **Output:**
`VIOLACAO: volume de alarme=0 (audivel=false); barra afirma 'falando'=true`. Mesma raiz do P1
acima: `aoFalar` não olha se o som tem por onde sair.

## P2 — Elo 1 sem guarda: `SchedulerOutcome.scheduled` nunca é lido

**Medido por leitura do fonte, não por Robolectric (declarado em "Não confirmado"):**
`ReminderScheduler.schedule` devolve `SchedulerOutcome(scheduled = true)` **incondicionalmente**
depois de chamar `setAlarmClock`/`setExactAndAllowWhileIdle` — nenhum dos dois tem retorno, e o
`scheduled` só é `false` no ramo `nextReminderAt == null`. Quem lê o campo: **ninguém** (`grep
'\.scheduled'` em `app/src/main` só acha `scheduledAt`, que é outro campo). O `SaveResult` carrega
`usedInexactAlarm` e nunca `scheduled`. Ou seja: um `setAlarmClock` que o sistema descarta (o caso
do `setAlarmClock` engolido em OEM) não é percebido nem contado.

**Por que importa pra ela:** se o alarme não é armado, o lembrete não dispara — e o aplicativo não
tem onde dizer isso.

## Medido e OK (não refazer)

- **Elo 4 (permissão/canal desligado):** nos 64 estados `perm=false`, o app **não** diz OK e **não**
  pede a voz (invariante A e B passam). A guarda de permissão e de canal desligado funciona.
- **Elo 5 (canal rebaixado puro):** `IMPORTANCE_LOW` ⇒ `QUIET`; `IMPORTANCE_NONE` ⇒ `OFF`. Confirmado.
- **Elo 6 (volume zero com canal saudável):** canal com som e volume 0 ⇒ `APARELHO_MUDO` (não OK).
  O #81 está fechado.
- **A porta da voz:** `deveFalarOlembrete` só devolve `true` para `POSTED` (medido em
  `CadeiaDeAudioOracleTest`: nos estados `perm=false` a voz não é pedida).
- **O aceite síncrono do motor:** o `VozDoAparelho.falar` devolve `false` quando o `speak` devolve
  `ERROR`/`catch` (coberto por `LembreteFaladoServiceTest` do #80; não re-medido aqui).

## Não confirmado

- **Elo 1 e elo 2** (armação e boot) não foram exercitados no Robolectric: `AlarmManager` e
  `BroadcastReceiver` de manifesto pediriam `FalaAgendaApplication`, Room e infraestrutura
  inventada. O P2 do elo 1 é leitura de fonte (o `grep` está acima), não medição de comportamento.
- **A falha assíncrona do motor `TextToSpeech` real:** o `ShadowTextToSpeech` sempre devolve SUCCESS
  e não tem como disparar o `onError` do motor; o `CadeiaDeAudioAsyncTest` usa a costura
  `SintetizadorDeVoz` e um motor de mentira. O comportamento do `onError` **real** de um motor de
  fabricante não foi medido em aparelho.
- **O Não Perturbe com o alarme bloqueado de propósito** e um aparelho cujo volume de alarme o
  sistema trate de outro jeito: só se medem no aparelho dela (limite já declarado no #81).

## PENDENTE (decisão de produto)

- **A voz tem que ter guarda, ou tem que calar?** Se o aparelho não tem voz em pt-BR, o correto é
  (a) não subir o serviço, (b) subir e dizer na notificação que não há voz, ou (c) deixar só o som
  do canal? A decisão é de produto, e o elo 7 hoje não faz nenhuma das três.
- **`channelSilenced` deve contar canal vibrando-sem-som como mudo?** Hoje não conta; a vibração é
  lida como "ainda avisa". Para uma idosa com o celular na sala, vibração não é aviso — mas marcar
  como mudo acende o cartão para quem pode estar sentindo a vibração. É o mesmo trade-off que o #81
  registrou para o volume.
- **Onde o veredito da voz aparece?** A home não tem superfície nenhuma para a voz (`grep 'voz'`
  em `HomeScreen.kt` = zero). O cartão de avisos cobre canal e volume; a voz não tem cartão.

## Nota de escopo — a voz não está em `main`

`git ls-tree main` **não tem** `VozDoAparelho.kt`, `LembreteFaladoService.kt` nem `AvisoFalado.kt`;
o `<service>` não está no `AndroidManifest` de `main`; `grep -r VozDoAparelho main` = zero. O #80
(`feat/fala-alarme-que-fala`) está **aberto** e sua base (`83bf24b`) é **anterior** ao #81 — a
árvore do #80 nem tem o `APARELHO_MUDO`. Ou seja: o produto **de hoje** (o que ela tem no celular)
só tem os elos 1-6, e o elo 7 chega se/quando o #80 mergear. A árvore medida aqui é a do produto
**depois** do #80 mergeado sobre `main` (`git merge` limpo, sem conflito), que é onde o produto
cartesiano dos sete elos passa a existir.

**Consequência de upgrade (a hipótese do "canal que não muda"):** o #80 cria um canal **novo**
(`fala_agenda_voz`) com `IMPORTANCE_LOW` e som nulo, de propósito. Como um id novo nasce sem
histórico, um upgrade não deixa o canal da voz no estado antigo — mas se uma versão futura mudar o
**id** ou a **importância** do canal da voz, o sistema ignora a criação repetida (o
`ShadowNotificationManager` do Robolectric replica isso: só sobe importância, nunca desce; `sound`
nunca é sobrescrito) e o aparelho fica com o canal velho. Hoje não há mudança de id do canal da voz
em nenhum PR — o risco é de manutenção, não atual.
