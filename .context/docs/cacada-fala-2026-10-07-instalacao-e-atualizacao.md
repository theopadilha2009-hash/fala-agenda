# Caçada de fala — instalação e atualização (07/10/2026)

Base: `main` @ `7c83992` (clone descartável `/tmp/fala-cacada-clone`); tag `v0.5.2` lida via `git show`.
Escopo: o que muda no aparelho dela quando o app passa de `v0.5.2` (21/08) para o `main` de hoje.

## Manchete

O upgrade **não perde estado dela** e **não a tranca em nenhuma recusa**: banco (Room), preferências,
canal, permissões e alarmes sobrevivem ou se re-armam. O que quebra — e **só** no upgrade, não na
instalação limpa — é a **chegada do modelo de voz**: 31 MB que ela **nunca teve** (o `v0.5.2` não tem
voz offline; o modelo entrou em 28/09), baixados sem resume, sem progresso e sem botão de nova
tentativa, no processo do app, disparados pelo toque no microfone.

Zero P0. Dois P1. Três P2.

---

## P1 — O download de 31 MB não tem resume, não tem progresso e não tem botão de tentar de novo

**Estado exato:** `OfflineModelInstaller.installIfNeeded` (`app/src/main/java/com/theopadilha/falaagenda/speech/OfflineModelInstaller.kt:156-189`)
baixa para um zip em `cacheDir` e, no `finally` (`:185-188`), apaga zip **e** staging em **qualquer**
desfecho que não seja sucesso. `fetch()` (`:191-219`) usa `http.newCall(request).execute()` sem header
`Range` e sem arquivo parcial. `request()` (`:108-145`) é "uma tentativa por pedido de voz" — sem laço,
sem `WorkManager`, sem foreground service.

**Ausência é a prova:**

```
$ grep -rn "WorkManager\|ForegroundService\|startForeground\|DownloadManager\|Range\|setNotificationVisibility" \
    app/src/main/java app/build.gradle.kts
(sem resultados)

$ grep -n "resume\|partial\|Range\|contentLength" .../OfflineModelInstaller.kt
201:            val declared = body.contentLength()   ← único uso; só valida teto
```

**Obtido × esperado:** obtido = download atômico que reinicia do zero a cada interrupção, rodando só
enquanto o processo do app vive. Esperado (para uma idosa em rede instável) = resume por `Range`, ou um
`WorkManager`/foreground service que sobrevive ao app sair de cena, ou ao menos um botão "Tentar de novo".

**Por que importa pra ela:** o modelo é **novo** para ela. O `v0.5.2` (tag de `2026-08-21 17:40`) não tem
`speech/Vosk*` nem `OfflineModelInstaller`; os commits que os criam são `#18`/`#19` de `2026-09-28`. Ou
seja, **no aparelho dela o modelo não existe e vai ser baixado do zero** — 31 MB nos dados dela, disparados
no primeiro toque no microfone. Se ela fechar o app, se o Android matar o processo, se cair o wifi, o
download **reinicia do começo**.

O que ela vê enquanto baixa é uma frase fixa na tela de Ajustes ("Estamos preparando a voz do celular.
Pode usar o microfone normalmente." — `SettingsScreen.kt:259-260`), **sem porcentagem e sem MB**; se
falhar, outra frase fixa (`:265-266`), **sem botão** — a "nova tentativa" é o próximo toque no microfone.

---

## P1 (condicional) — No upgrade ela NÃO refaz o onboarding, então a permissão de avisos não é pedida de novo

**Estado exato:** o destino inicial é `if (onboardingDone) "home" else "onboarding"` (`FalaAgendaRoot.kt:220`),
e `onboardingDone` vem de `onboarding_done` no DataStore — **preservado no upgrade** (mesma chave). Logo,
quem **atualiza** não passa pelo `OnboardingScreen`, que é o único lugar que pede `POST_NOTIFICATIONS` de
forma proativa (`OnboardingScreen.kt:148`). Na home o pedido só acontece pelo toque no cartão (`HomeScreen.kt:611`).

**A assimetria exata (é o eixo):** instalação limpa → onboarding pede mic → avisos → alarme exato.
Upgrade → **nenhum pedido proativo**; o estado da permissão é o que o `v0.5.2` deixou.

E o `v0.5.2` tinha uma saída que **pulava o pedido de avisos inteiro**: `SecondaryButton("Agora não") { finish() }`
— o `v0.5.2` só pedia avisos **dentro** do ramo de microfone concedido (o callback do `mic` chama
`notif.launch` só quando `granted`).

**Obtido × esperado:** obtido = se ela tocou "Agora não" (ou negou o mic) no `v0.5.2`, ela chega no `main`
com avisos desligados e **nenhum diálogo volta a aparecer**; o lembrete fica mudo até ela reparar no cartão
"Os avisos estão desligados" e tocar "Ligar avisos" (`ReminderAlertCard.kt:21-25`). Esperado = o upgrade
cobrir a mesma lacuna que a instalação limpa cobre.

**Por que importa pra ela:** é um app de lembrete de remédio para idosa; lembrete mudo é o defeito que o
app inteiro existe para não ter. Não é beco (o cartão existe, 1 toque), mas é **silêncio por padrão no upgrade**.

**Não confirmado:** o estado real da permissão no aparelho dela (não há device aqui). A afirmação é
condicional ao caminho do `v0.5.2`.

---

## P2 — O widget muda de tamanho no upgrade

`res/xml/agenda_widget_info.xml`, `v0.5.2` → `main`:

```
android:minWidth="110dp"      → android:minWidth="150dp"
android:minHeight="110dp"     → android:minHeight="180dp"
android:targetCellWidth="2"   → android:targetCellWidth="3"
```

No Android 12+ os `targetCell*` valem **no lugar** de `minWidth`/`minHeight`; o widget dela (que já estava
na home) passa a ser pedido como 3×2 em vez de 2×. O widget **não quebra** (mesma classe
`AgendaWidgetProvider`, repintado), mas o launcher re-layouta o que ela já tinha posicionado. Mudança
visível, não perda. (O KDoc no arquivo documenta que o `v0.5.2` entregava 130dp e truncava o título.)

---

## P2 — O download roda no processo do app (não em foreground service)

`request(background)` é chamado de `VoiceCaptureController.start` (`:137`) com o `appScope` do
`FalaAgendaApplication` (`:27-31`). Não há `startForeground`. Consequência: com o app em segundo
plano/processo morto, o download morre — e, sem resume (P1), recomeça do zero.

---

## P2 — A "recusa" da fala offline não existe como escolha; o app baixa 31 MB sem consentimento

O download é disparado pelo toque no microfone sem aviso prévio de tamanho. Não trava (a escuta segue no
motor do sistema), mas é uma obrigação de dados imposta a uma idosa em plano possivelmente limitado. Não
há caminho de recusa — também não há necessidade de haver, mas combinado com "sem progresso" ela não
consegue decidir.

---

## Oráculo — o que foi medido e a contagem

**Invariante 1 — "todo estado que ela já tinha sobrevive ao upgrade":**
espaço medido = linhas do Room (séries/ocorrências), chaves do DataStore, permissões do manifesto,
alarmes armados.

- Banco: `AppDatabase` `v0.5.2` = `version = 3`; `main` = `version = 4`, com `MIGRATION_1_2`,
  `MIGRATION_2_3`, `MIGRATION_3_4` registradas (`AppDatabase.kt:48`). `MIGRATION_3_4` =
  `ALTER TABLE task_series ADD COLUMN skippedDates TEXT NOT NULL DEFAULT ''` — aditivo, sem perda.
- Preferências: nome do DataStore idêntico (`fala_agenda_settings`), chaves idênticas (`quiet_start_min`,
  `quiet_end_min`, `onboarding_done`, `theme_mode`). A única removida, `exact_alarm_warned`, era
  **escrita e nunca lida** no `v0.5.2` (grep: só a declaração) — não é estado que ela veja.
- Permissões: `diff` dos `<uses-permission>` = **"PERMISSÕES IDÊNTICAS"** (nenhuma nova obrigatória no upgrade).
- Alarmes: `BootCompletedReceiver` trata `ACTION_BOOT_COMPLETED` **e** `ACTION_MY_PACKAGE_REPLACED`
  (`Receivers.kt:262-264`; ambos no manifesto `:82-83`) → `rescheduleAll()`, que rearma as pendentes.
  Coberto por `TaskRepositoryTest.rebootReschedulesPending` (passa).

**Invariante 2 — "toda obrigação tem caminho de recusa que não trava":**
espaço medido = mic, avisos, alarme exato, bateria.

- Mic negado → mensagem + "Escrever tarefa" (`OnboardingScreen.kt:212-226`, `HomeScreen.kt:280-282`).
- Avisos negados → mensagem + "Continuar" (`OnboardingScreen.kt:227-238`); home → cartão `OFF` + "Ligar avisos".
- Alarme exato negado → mensagem + cartão (`OnboardingScreen.kt:239-251`).
- Bateria restrita → cartão + `ManufacturerHint` (`AlarmHealthCard.kt:38-43`).

**Contagem de violações: 0** (invariante 1) **+ 0** (invariante 2). Uma **promessa quebrada não-beco**:
o download de 31 MB (P1 acima). O estado antes×depois é a referência — a migração foi exercitada com um
banco `v1` de verdade (`MigrationTest.migracoesDoV1AoV4PreservamDados`).

**Contagem pelos XMLs, não pelo "BUILD SUCCESSFUL"** (`:app:testDebugUnitTest --rerun-tasks --max-workers=2`
na cópia):

```
FILES=82  TOTAL=671  skipped=0  failures=0  errors=0
MigrationTest                 4 tests  fail 0  err 0
NotificationHelperTest       22 tests  fail 0  err 0
LembreteQueFazBarulhoTest    11 tests  fail 0  err 0
OfflineModelInstallerTest    13 tests  fail 0  err 0
```

**Caso de controle (instalação limpa) × upgrade:** para **preferências, banco, permissões e alarmes** os
dois casos convergem (nenhuma diferença atribuível). A diferença atribuível é a **ordem de chegada**:
instalação limpa → onboarding pede as permissões; upgrade → não. E o **modelo de voz é igual nos dois**
(net-new em ambos), então o P1 do download não é específico do upgrade — é específico de "o modelo nunca
esteve lá".

---

## Medido e OK (não refazer)

- **Canal de notificação:** id mudou `fala_agenda_reminders` (`v0.5.2`) → `fala_agenda_alarmes` (`main`), e
  `ensureChannel` apaga o legado (`NotificationHelper.kt:26,33,91`). O defeito "canal velho mudo sobrevive"
  está **fechado**. O KDoc no `:18-25` explica por que o id novo é a única saída: um canal apagado com o
  mesmo id renasce com as configurações antigas.
- **Migração do Room v3→v4** (e a cadeia v1→v4): `MigrationTest` 4/4, dados preservados.
- **Preferências do DataStore:** nome e chaves estáveis; sobrevivem.
- **Permissões do manifesto:** idênticas ao `v0.5.2` — nenhuma permissão nova obrigatória no upgrade.
- **Alarmes órfãos:** refutado. `MY_PACKAGE_REPLACED` → `rescheduleAll` rearma; coberto por teste.
- **Primeiro boot não trava:** `FalaAgendaApplication.onCreate` envolve `rescheduleAll` em `runCatching` e o
  `appScope` tem `CoroutineExceptionHandler` (`FalaAgendaApplication.kt:27-53`).
- **`AppContainer` não abre o keystore no arranque** (`tokenStore by lazy`, `AppContainer.kt:43`) — não é
  risco de boot.

---

## Não confirmado

- O estado real da permissão de avisos no aparelho dela (P1 condicional).
- Não foi escrito um teste novo que suba o `FalaAgendaApplication` real com um banco `v0.5.2` semeado e leia
  o estado depois — a sobrevivência foi medida pela `MigrationTest` (que usa o SQL de migração real) + leitura.
  O caminho "boot do Application → estado" é **não confirmado por teste novo**.
- Robolectric não sobrescreve a importância de um canal já criado (fato conhecido); não re-medido.

---

## PENDENTE (decisão de produto)

- Resume/`WorkManager` no download de 31 MB (P1) — é decisão de produto, não bug.
- Pedir a permissão de avisos no primeiro boot pós-upgrade, ou aceitar que o cartão da home cobre (P1).
