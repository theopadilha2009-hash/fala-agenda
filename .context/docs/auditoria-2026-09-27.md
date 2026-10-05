# Auditoria completa — Fala Agenda v0.5.2

Data: 2026-09-27 · Escopo: todo o repositório · 8 auditores read-only em paralelo
Motivo: relato "o app simplesmente não funciona"

> Registro do que a auditoria **encontrou**, não fotografia do que existe hoje: os nove P0
> abaixo foram corrigidos no #14 (`0a46352`), e o que a segunda onda achou e mudou está nos
> adendos do fim. As recomendações ("→") são o plano de então.

## Baseline verificada nesta máquina (não é opinião, é output)

SDK 36 + build-tools 36.0.0 instalados nesta sessão; os 6 alvos passaram **depois** das auditorias:

| Alvo | Resultado |
|---|---|
| `:domain:test` | BUILD SUCCESSFUL (11s) |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL (2m5s) |
| `:app:lintDebug` | BUILD SUCCESSFUL (1m4s) |
| `:app:assembleDebug` | BUILD SUCCESSFUL (1m25s) — APK 19,8 MB |
| `:app:assembleRelease` | BUILD SUCCESSFUL (2m49s) — APK 13,3 MB (unsigned) |
| `:app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL (20s) |

**O build não é o problema.** O "não funciona" está em comportamento de execução.

Prova independente do achado nº 8 (APK publicado sem Supabase): baixei `app-release.apk` da
v0.5.2, conferi o SHA-256 publicado (`4a4993eb…bb4c`, idêntico) e varri os 3 dex:
`grep -i supabase` só acha **nomes de campo**; `grep -c eyJ` = **0**. Nenhuma URL, nenhum JWT.

## O que explica diretamente o "não funciona" (P0)

1. **Voz sem watchdog** — `speech/VoiceCaptureController.kt:29,87,202`. `startedAt` só é lido
   dentro de `onError`. Se o serviço de reconhecimento travar, o mic estiver ocupado ou o app
   voltar do background, o idoso fica em "Espera um instante…" ou "Pode falar agora" **para
   sempre**, e falar não faz nada. Único escape é tocar de novo — que ele não descobre.
   → watchdog por estado (10 s em PREPARING/UNDERSTANDING; silêncio+2 s em LISTENING), limpo no
   primeiro callback.

2. **Permissão de microfone negada é beco sem saída** — `ui/onboarding/OnboardingScreen.kt:63-69,96`
   (o `granted` do callback nunca é lido) + `ui/home/HomeScreen.kt:155-161`. No Android 11+ o
   sistema suprime o diálogo depois da 2ª recusa: todo toque no microfone vira um snackbar de
   4 s e nada mais, **para sempre**. Não existe `ACTION_APPLICATION_DETAILS_SETTINGS` em nenhum
   lugar do app.
   → cartão persistente com "Abrir Ajustes" (o padrão já existe para alarme exato em
   `HomeScreen.kt:385-394`).

3. **Aviso descartado em silêncio** — `reminders/NotificationHelper.kt:52`. Se
   `areNotificationsEnabled()` for false (permissão negada no Android 13+, ou o usuário desligou
   as notificações do app), o lembrete **não é emitido**: sem som, sem toast, sem log, sem nada.
   O alarme já foi consumido (`onAlarmFired` reagenda a repetição antes de notificar). O
   onboarding ignora a resposta de POST_NOTIFICATIONS e o app nunca mais checa.
   → fallback visível (card na home na próxima abertura + aviso no onboarding) e nunca falhar
   calado.

4. **Botão "Escrever tarefa" desaparece no erro** — `ui/home/HomeScreen.kt:552`. Ele só é
   renderizado quando `state == IDLE`. No estado ERROR a tela diz literalmente "Toque de novo, ou
   escreva o recado" **sem nenhum botão de escrever**. Se o microfone não funciona, não há
   nenhuma forma de criar tarefa.

5. **Resultado vazio é falha silenciosa** — `VoiceCaptureController.kt:142-150` +
   `HomeScreen.kt:216-222,519-525`. `finishWith("")` publica `state = IDLE` **junto** com
   `error = "Não entendi o que foi dito."`, mas o `MicDock` só mostra `error` quando
   `state == ERROR`. A mensagem é descartada e a tela volta como se nada tivesse acontecido.

6. **Salvar falhando não avisa nada** — `HomeScreen.kt:487`. `saveDraft` é chamado sem `onError` e
   o default em `HomeViewModel.kt:54` é `{}`. O botão volta de "Salvando…" para "Salvar" e mais
   nada: o recado nunca é salvo e o usuário não sabe por quê. Pior: `HomeViewModel.kt:68-96` não
   tem `try/catch` nem `CoroutineExceptionHandler` em lugar nenhum → exceção de IO **derruba o
   processo**.

7. **Parser erra com confiança** (`domain/.../parser/LocalTaskParser.kt`) — todos com
   `ambiguous=false`, ou seja, vão direto para o "Pode salvar?" de um toque:
   - `"amanhã às 9 e meia"` → **09:00** (o "e meia" é descartado; linhas 501-503,269,499)
   - `"amanhã às 3 de tarde"` → **03:00** (só aceita `a/da/na`, não `de`; linhas 401-412)
   - `"todo dia útil"` → recorrência **diária** (toca sábado e domingo; linha 201-211)
   - `"daqui cinco minutos"` e `"daqui 5 min"` → **não entende** (WORD_AMOUNTS incompleto; 306,531-540)
   - recorrente com hora já passada nasce no passado e o alarme dispara na hora do save (103-108)

8. **A ajuda extra (IA) nunca foi embarcada em nenhuma release** — `app/build.gradle.kts:35-36` +
   `.github/workflows/release.yml:35-42`. Nem o release nem o CI passam `SUPABASE_URL`/
   `SUPABASE_ANON_KEY`, e `localOrEnv()` devolve `""`. Em aparelho, `SupabaseConfig.isConfigured`
   é false → Configurações mostra "Sem conexão com o serviço" e "Ativar" devolve "Serviço não
   configurado". Foram 13 releases assim.
   → passar as duas como *vars* do repo (a anon key é pública por design) + assert no CI de que o
   dex contém a URL.

9. **Excluir tarefa recorrente não exclui** — `domain/.../recurrence/OccurrenceLifecycle.kt:110-116`
   + `TaskRepository.kt:182-190`. `advance` rematerializa a data sempre que não existe ocorrência,
   sem memória de exclusão. Toca Excluir na tarefa de hoje, reabre o app → ela volta, com alarme
   agendado no passado disparando. (O mesmo para a prévia de amanhã.)

## Outros achados reais

**Alarmes/notificações** (auditado na thread principal; 3 subagentes falharam por erro do provedor)
- P2 `AlarmIds.kt:37-40` — colisão de requestCode só seria possível com 28 bits de hash em ~268 M;
  na prática ok. Nota: `spawnUpcomingPreview` (`TaskRepository.kt:409`) agenda as 3 prévias com
  `first = true` → `setAlarmClock` para datas futuras, deixando o "próximo alarme" do sistema
  apontando dias à frente.
- P2 `Receivers.kt:52-72` — `exported=true` em Boot/TimeChange é inofensivo (actions protegidas do
  sistema).
- P2 o canal é criado com IMPORTANCE_HIGH, mas nada detecta o usuário **reduzindo** a importância
  depois.
- P2 `Receivers.kt:13-29` — `goAsync()` + `appScope.launch`: em OEM agressivo o processo pode morrer
  antes do `finish()` e o aviso não aparece.

**Dados** (auditado na thread principal)
- P1 `editOccurrence:254-260` — editar uma ocorrência **apaga todas as PENDING** da série e
  recria só a editada + 3 prévias: o histórico de pendentes some.
- P1 corrida: `complete()` (129-147) e `onAlarmFired()` (326-361) leem-calcula-gravam com
  `@Insert(REPLACE)`; se o usuário conclui pela notificação enquanto a repetição dispara, o
  último write vence e a tarefa pode voltar a PENDING depois de concluída.
- P1 `MigrationTest.kt` é tautológico: asserta `startVersion`/`endVersion` e o literal do SQL, mas
  **nunca executa a migração** (`exportSchema=false`, `MigrationTestHelper` não usado). Um SQL
  errado na v4 = crash no launch para quem atualiza, com CI verde.
- P2 `Daos.kt:41-42` — `observeAll()` carrega a tabela inteira a cada mudança, sem filtro de data,
  para sempre.

**Recorrência/insights**
- P1 `time/AppClock.kt:23-27` — `Clock.systemDefaultZone()` congela o fuso na criação do processo;
  mudar o fuso com o app vivo faz `today()`/`zoneId()` mentirem (tarefa some de "Hoje").
- P1 `insight/MonthInsights.kt:46-50` — `filter { status != CANCELLED }` conta MISSED (não
  realizado) e PENDING futuro como "o que mais você fez".
- P2 catch-up: o horizonte é de 3 prévias; celular desligado 5 dias → 2 dias **nunca existiram**
  (não viram "não realizadas" nem entram no resumo).
- P2 `OccurrenceLifecycle.kt:89-100` — aviso que dispara depois da meia-noite (Doze/alarme inexato)
  é marcado MISSED antes do `notify` e nunca toca.
- P2 `Models.kt:126-139` + `MonthSummaryScreen.kt:161-169` — `amountCents` mora na série: editar
  o valor hoje reescreve a soma de meses fechados.
- P2 `TaskRepository.kt:74-85` — PENDING com data anterior a hoje não cai em nenhuma seção: fica
  invisível.

**UI/UX (público 60+)**
- P1 `HomeViewModel.kt:68-80` + `HomeScreen.kt:128-141` — snackbar de "Feito." dura 10 s e o undo
  lê um slot único `lastCompleted`: concluir dois itens rápido e tocar "Desfazer" desfaz **o item
  errado**.
- P1 `components/MicMark.kt:77` — valor de animação lido em composição: `PulsingMic` recompõe a
  60 fps e mantém dois `rememberInfiniteTransition` vivos mesmo parado, na tela inicial.
- P1 `HomeScreen.kt:121` — `isBatteryUnrestricted()` (binder síncrono) roda a cada recomposição, e
  a home recompõe a cada resultado parcial da fala.
- P1 `HomeScreen.kt:562,567,652` — `Modifier.height(48/56.dp)` sobre texto em sp, sem teto de
  `fontScale`: com a fonte do sistema no máximo (comum em 60+) o texto é cortado.
- P2 `HomeScreen.kt:236-243` — id de ocorrência desconhecido não consome nem avisa; pode abrir a
  tela de edição sozinha depois.
- P2 `HomeScreen.kt:603` — `Instant.now()` em composição sem tique: "Bom dia"/"daqui X min"
  congelam até outra mudança de estado.
- P2 botões de menu/atalhos em 48dp, abaixo dos 56dp prometidos.

**Segurança/privacidade**
- P1 `speech/VoiceCaptureController.kt:44-47,114-123` — o caminho padrão é o reconhecedor de
  **rede** (sem `EXTRA_PREFER_OFFLINE`), o on-device só entra como fallback; contradiz "o áudio não
  sobe para a nuvem" (README e strings.xml). PLAUSÍVEL: depende do serviço do OEM.
- P1 `.github/workflows/release.yml` + repo — `main` e as tags não têm proteção (API confirmou
  404/environments 0) e a chave de assinatura está em secret do mesmo repo: um commit em `main`
  que imprima o secret + `git tag` exfiltra a chave e permite APK malicioso que passa na
  verificação de assinatura. A certidão ainda é a mesma desde a v0.1.0 (`BB:AB:67:79:…`).
- P2 `activate-device/index.ts:23-30` + `activation_rpc.sql:17-19` — INSERT antes de validar o
  código, sem rate limit: loop de POST gera escrita no Postgres.
- P2 `parse-reminder/index.ts:27-29` — `transcript` sem limite de tamanho entra no prompt.
- P2 `SettingsStore.kt:60-73` — fallback silencioso do Keystore para SharedPreferences em claro.
- P2 `AppUpdater.kt:27,61-72,118-124` — sem `.sha256` o APK é instalado **sem verificação**;
  `followRedirects(true)` com allowlist só na URL inicial.
- P2 ativação envia texto de saúde para Supabase+OpenAI sem consentimento específico (LGPD).
- Verificado e **sem achado**: RLS habilitada nas 3 tabelas sem policy e privilégios revogados de
  anon/authenticated; nenhum segredo jamais commitado; cota atômica; host da OpenAI fixo; nenhuma
  dep com CVE conhecida.

**Build/CI/testes**
- P0 `.github/workflows/release.yml:49` — imprime as certidões mas não compara com a release
  anterior: keystore regenerada publica normalmente e o APK não instala por cima (recuperação =
  desinstalar e perder as tarefas).
- P1 `app/build.gradle.kts:31-32` + `release.yml:5-6` — nada confere `versionName` com a tag; um
  desencontro faz o app anunciar "Tem versão nova" para sempre, baixando 13 MB a cada abertura.
- P1 `ci.yml:34-35` — o teste instrumentado só é **compilado**, nunca roda (sem emulador).
- P1 caminho do auto-update (o que mais quebrou em produção) sem teste: `okhttp-mockwebserver` está
  declarado e nunca importado.
- P2 deps bem atrás: Compose BOM `2025.01.01` (ui 1.7.7 / material3 1.3.1; hoje 2026.09.00 →
  1.12.1/1.4.0), core-ktx 1.15.0, lifecycle 2.8.7, navigation 2.8.5, activity 1.10.0, okhttp 4.12.0,
  Kotlin 2.2.10. `lint` desliga justamente `GradleDependency`.
- P2 `libs.versions.toml:13` — `security-crypto 1.1.0-alpha06` (lib descontinuada; existe 1.1.0
  estável).
- P2 `ci.yml` — todo push dispara a matriz inteira e o PR dispara de novo, sem cache, sem
  `concurrency`, 6 JVMs separadas (~13 min por PR).
- P2 `release.yml:51-53` — nota estática, sem `--generate-notes`, sem `--clobber`, e o app olha
  `/releases/latest`: publicar hotfix em tag antiga faz o "latest" apontar para trás.
- P2 `verify.sh:23` vs `ci.yml:61` — flags do Deno divergem e não há `deno.lock`.
- P2 `gradle.properties:7-11` — `android.disallowKotlinSourceSets=false` sem `// atalho:` com
  gatilho.
- Sem achado: 16 KB page size OK (`.so` alinhadas a 16384); `versionCode` 1..14 coerente com as
  14 tags; APK das tags corresponde ao commit da tag (verificado via
  `version-control-info.textproto`).

## Verificado vs não verificado

- **Verificado nesta sessão**: build/testes/lint/APKs (output acima); SHA-256 da release; ausência
  de Supabase no dex; assinatura inalterada entre v0.1.0/v0.4.0/v0.5.2; RLS e migrations por
  leitura; proteção de branch via API do GitHub.
- **Não verificado**: nada foi executado em aparelho/emulador (não há device nesta máquina). Os
  achados de UI (fonte grande, alvos de toque) são de leitura, não de inspeção visual. O finding do
  áudio na nuvem é PLAUSÍVEL (depende do serviço de voz do OEM). O comportamento de edge-to-edge
  com `targetSdk 36` fica como **verificar**.

## Limitação do processo

4 dos 8 subagentes morreram com `HTTP 400` do provedor no meio da leitura (alarmes 3×, dados 2×,
UI 1×). Alarmes e dados foram auditados na thread principal; UI foi relançada com escopo menor e
completou. Áreas com 1 passada só — não houve juiz adversarial por finding.

## Segunda opinião (onde dois sêniores discordariam)

- **Corrigir o parser agora ou trocar de abordagem**: consertar os regex (`e meia`, `de tarde`,
  `dia útil`) é barato e resolve o caso real; a alternativa é mandar mais coisa para a IA — que
  hoje não está embarcada (achado 8) e depende de rede. Pragmático: consertar os regex.
- **Auto-update vs F-Droid/loja**: o auto-update é o vetor de entrega de um APK malicioso
  (finding P1 de segurança) e o que mais quebrou em produção. Perfeccionista: assinar com chave em
  hardware e validar a assinatura do APK baixado. Pragmático: comparar a certidão do APK baixado
  com a do app instalado antes de entregar ao instalador — 20 linhas, resolve o ataque real.

---

## Adendo — P0 encontrado depois, ao escrever o teste da migração

**`@Insert(onConflict = REPLACE)` em `task_series` apagava as ocorrências da série.**
`data/local/Daos.kt:20` combinado com `Entities.kt:41-45` (`ON DELETE CASCADE` de
`task_occurrences.seriesId`). No SQLite, `INSERT OR REPLACE` = `DELETE` da linha conflitante +
`INSERT`; com FK ligada (o Room liga), o `DELETE` cascateia. Toda reescrita da série — concluir e
desfazer, excluir uma ocorrência, restaurar, encerrar a série, editar, remarcar — zerava as
ocorrências daquela série. `spawnUpcomingPreview` rematerializa até 3 futuras, então o sintoma
visível era "sumiu tudo" e não "o app quebrou".

Não apareceu antes porque `TaskRepositoryTest` usa um DAO falso em memória: nenhum teste tocava o
Room de verdade. Provado com `app/src/test/.../SeriesDaoTest.kt` (Robolectric, banco real):
`reescreverASerieNaoApagaAsOcorrencias` falhava antes do fix e passa depois.
→ `@Upsert` (INSERT ... ON CONFLICT DO UPDATE), que não apaga a linha.

**Tombstone de data excluída não funcionava em nenhum dos dois lados.** Dois defeitos
independentes, os dois corrigidos:

1. `Entities.kt:84` (`TaskSeries.toEntity()`) não escrevia `skippedDates`, então o `skipDate` do
   repositório era descartado na saída.
2. `TaskRepository.spawnUpcomingPreview` não consultava `isSkipped`. Mesmo com o tombstone
   gravado, `RecurrenceEngine.upcoming` começa em `today` — a data que o usuário acabou de
   excluir era a **primeira** da lista, então o preview a recriava em todo cold start. Como o
   mundo de datas do tombstone (90 dias) cobre exatamente as 3 do preview, o tombstone nunca
   teria efeito sozinho.

Coberto por `skippedDatesSobreviveAoBanco`, pela migração v1→v4 e por
`excluirOcorrenciaDeSerieDiariaNaoVoltaAoReagendar` (caminho completo
`deleteOccurrence` → `rescheduleAll`), que é o teste que faltava.

Junto: `deleteOccurrence` apagava a série quando a ocorrência excluída era a última, mesmo em
série recorrente — "Excluir" virava "Encerrar série". Agora só apaga a série quando a tarefa não
é recorrente, e `restore` limpa o tombstone via `unskipDate`.

Lição para o próximo review: teste com DAO falso não vê semântica de Room (FK, REPLACE, índices,
NOT NULL). Toda mudança de schema/DAO precisa de um teste com banco real.

---

## Adendo 2 — segunda onda: o que a primeira passada não viu

Mesma data. Depois do #14 a auditoria voltou às áreas que a primeira passada não cobriu
(widget, tela de mês, ajustes, compartilhar, tela de atualização, `domain/insight/**`) e às
regras que o primeiro fix encostou. Tudo abaixo está na árvore.

**Parser pt-BR** (`domain/.../parser/LocalTaskParser.kt`)
- `"quinta que vem"` caía na ocorrência da **semana corrente** (podia ser hoje, e a tarefa
  nascia no passado) e o "Vem" sobrava no título. `WEEKDAY_NEXT_WEEK` reconhece a expressão
  inteira e `stripWeekDays` a remove antes de remover o dia da semana.
- `"de 8 em 8 horas"` / `"a cada N horas"` não era intervalo: virava um horário único, errado.
  Agora `INTERVAL` reconhece o intervalo e o rascunho sai **ambíguo de propósito** —
  `localTime` nulo, `MissingDraftField.TIME`, a expressão citada na nota. O `HybridParser`
  escala para a IA quando ela está ligada; sem ela, a nota diz que o rascunho local ficou para
  corrigir na mão.
- minutos compostos por extenso perdiam a última unidade: `"nove e quarenta e cinco"` dava
  09:40 e "Cinco" ia para o título (`MINUTE_TAIL` aceita a unidade, mas só quando o primeiro
  termo é uma dezena).
- `"daqui a duas horas e meia"` ignorava o "e meia"; `"às 12 da noite"` virava meio-dia.

**Ciclo de vida** (`domain/.../recurrence/OccurrenceLifecycle.kt`)
- `skipDate` cortava as tombstones **mais próximas de hoje** — justo as que o preview e o
  `advance` materializam. Com o teto de datas cheio, a data excluída renascia. O corte agora
  descarta as mais distantes.
- Ocorrência de ontem com `nextReminderAt`/`snoozedUntil` vivo deixou de virar "não
  realizada": o adiamento do horário de silêncio só toca às 08:00 do dia seguinte, e a virada
  do dia matava o aviso antes de ele sair.
- `editOccurrence` ancora o preview em **hoje** (antes gerava três datas vencidas a partir da
  data editada, e o avanço seguinte marcava todas como não realizadas, deixando a agenda sem
  as futuras até o app reabrir) e limpa o tombstone da data editada — remarcar para uma data
  excluída não pode deixá-la bloqueada em toda materialização futura.

**Lembretes**
- `schedule()` não cancela mais a notificação já publicada, só o alarme: ela era removida da
  barra em todo start, boot e virada do dia, antes de a usuária ver.
- Disparo que estoura os 8 s do `goAsync` reagenda uma recuperação em 60 s
  (`RECOVERY_DELAY_SECONDS`) em vez de morrer calado; `rescheduleAsync` (boot, troca de hora)
  ganhou `catch`, senão a exceção subia pelo `appScope` e derrubava o processo.

**Abertura do app**
- `collectWidgetUpdates` trata a falha de leitura da agenda e **reassina** depois de 5 s:
  banco corrompido, migração ruim ou disco cheio fechavam o app no `onCreate`, sempre, sem
  mensagem.
- DataStore: leitura com `catch` (o collect morria e a tela ficava em spinner eterno), handler
  de corrupção no arquivo (sem ele a gravação ficava impossível para sempre) e o tema do
  `MainActivity` dentro de `runCatching` (o `runBlocking` podia impedir o app de abrir).

**Auto-update**
- Antes de entregar o APK ao instalador, `ApkSignature` compara o SHA-256 do certificado do
  arquivo com o do app instalado; sem como ler as certidões, **não instala**.
- O APK já baixado é reaproveitado quando a origem é a mesma versão, e download que não fecha
  com o `Content-Length` não fica no cache passando por bom.

**Intents de sistema**
- Os 9 pontos de `ui/**` que abriam tela de sistema com `startActivity` direto passam por
  `DeviceIntents` (`HomeScreen` 6, `UpdateScreen` 2, `OnboardingScreen` 1): sem quem responda
  ao intent o toque vira recado na tela — menos no onboarding, que ignora o retorno de
  propósito, porque a tela troca no mesmo toque.

**Release/CI**
- O gate aceitava `https://evil.example#.supabase.co` (em glob o `*` do `case` casa `/`, `?` e
  `#`) e não olhava o papel da chave. Agora a URL é regex ancorada ao host e o `role` do JWT é
  decodificado: só `anon` entra — uma `service_role` num APK distribuído entrega o banco a quem
  extrair a chave.
- `ci.yml` compila o release com valores-sentinela a cada PR e confere os dois no dex: a
  injeção da config quebra no PR, não na tag. `versionName 0.6.0` / `versionCode 15`.

**Superfícies que a primeira passada não auditou**
- Tela de mês (`MonthSummaryViewModel`): o `Flow` era criado no corpo do composable, então
  cada recomposição cancelava e reassinava as duas queries do banco no thread principal. O
  `stateIn` no ViewModel resolve — mesmo padrão do `HomeViewModel`.
- Widget: a opção "Aparência" não valia para ele, que seguia o modo noturno do sistema. Agora
  o tema gravado é lido junto da agenda e o widget usa as cores de `values/` (nunca as de
  `values-night`), senão "Claro" com o celular no escuro não pega.
- Ajustes: a mensagem de sucesso passou a esperar a gravação — é o item 1 da lição abaixo.
- Tela de atualização: o download morria calado se ela saísse da tela (o escopo agora é do
  processo, não da composição) e "Instalar agora" com o APK já limpo do cache pelo Android
  falhava para sempre (o arquivo é conferido antes, e a tela volta para "Baixar e instalar"
  com a explicação em vez de mandar ela a um instalador que só sabe dizer "não foi possível
  analisar o pacote").
- `domain/insight/MonthInsights`: "o que mais você fez" somava MISSED e PENDING; agora só o
  que foi concluído — corrigido já no #14 (`0a46352`), não nesta onda.

### A lição: três jeitos de um fix correto não fazer efeito

O adendo anterior tirou uma lição de ferramenta ("teste com DAO falso não vê semântica do
Room"). Esta segunda onda mostrou que o problema é maior que o banco: três fixes corretos,
cada um com teste verde, não fizeram efeito no app. É isto que o próximo a mexer no repo
precisa ler antes de abrir PR.

1. **Dois fixes corretos, em branches separadas, se anulam quando juntos.** Uma branch fez o
   `SettingsStore` engolir a exceção de gravação (`private suspend fun save`) para ela não
   subir do escopo de composição; a outra fez a tela de ajustes só anunciar "atualizado"
   depois que a gravação voltasse **sem** exceção. Isoladas, cada uma estava certa, verde e
   coerente; juntas, a tela voltou a dizer "atualizado" sem ter salvo. Resolvido tirando o
   `save` do `SettingsStore` e tratando em cada chamador (`SettingsScreen.save`,
   `FalaAgendaRoot.onThemeMode`), com o cancelamento re-lançado — quem só girou o aparelho não
   pode ver "não consegui salvar". **Branch verde não prova integração; o merge é que tem que
   exercitar os dois lados da fronteira.**

2. **Um fix correto remove o terminador implícito de outra regra.** A escada de lembretes
   acabava porque a virada do dia encerrava a ocorrência; ao salvar o adiamento que cruza a
   meia-noite, a ocorrência deixou de ser encerrada e a escada passou a tocar de hora em hora,
   **para sempre** (`nextStep` saturava no passo horário e o intervalo horário nunca terminava).
   Ela ganhou terminador próprio em `ReminderPolicy.nextRepetition` (plano sem `fireAt` quando
   o passo cairia fora do dia local da ocorrência) e `MAX_STEP = 32` como rede. **Ao abrir uma
   exceção numa regra, procure quem estava encerrando o laço por tabela.**

3. **Contrato de escopo deixa o fix inerte.** `DeviceIntents.open` foi criado e testado, mas
   os call sites estão em `ui/**` — fora do contrato daquela mudança. O corpo do commit
   registra o resultado: "os chamadores em `ui/` ainda usam `startActivity` direto — a troca é
   de uma linha em cada um e ficou fora do contrato desta mudança". O mesmo na escada: o commit
   que a encerrou anota "`app/TaskRepository.kt:368` ainda precisa passar
   `occurrenceDay = occurrence.localDate`", o argumento não tem default, e sem ele a árvore de
   `6607b18` não compilava o `:app` — o ajuste era uma linha do trabalho de integração, fechada
   em `a76bc2f`. **A mudança só termina quando o call site muda: helper sem chamador e parâmetro
   sem argumento são código morto com teste verde.**

### Regras do domínio que não se leem de um arquivo só

- A escada de lembretes tem **dois** terminadores: o fim do dia local da ocorrência e o teto
  de passos. Repetição nunca cruza a meia-noite; o adiamento do horário de silêncio é a única
  travessia e é o último degrau do dia. Já o snooze é ação explícita da usuária: vale no
  horário pedido, atravessa a meia-noite e não é podado pelo fim do dia.
- O tombstone de data excluída guarda os últimos 90 dias, no máximo 120 datas, e corta **as
  mais distantes de hoje** — as próximas são exatamente as que o preview rematerializa.

**Não verificado nesta segunda onda:** não há device nesta máquina e esta passada não rodou
build; o que sustenta os itens acima é leitura de código e os testes escritos junto de cada
fix — não output de execução. O `:app` que não compilava na árvore de `6607b18` (item 3
acima) está fechado: `a76bc2f` passa o `occurrenceDay` no call site. O
`:app:testDebugUnitTest` daquela onda eram **173 testes**, a contagem da árvore de
`eeb126d` — o último commit do grupo (`git grep -c "@Test" eeb126d -- app/src/test`);
`a76bc2f` tinha 164. O número da árvore mergeada é **230**, com 0 falhas
(`app/build/test-results/`) — ver o adendo 3.

---

## Adendo 3 — o desfecho: o #15, as quatro rodadas de review e o que ficou sem prova

Data: 2026-09-28 · `main` antes: `0a46352` (#14) · merge: `d836d8d`

Os adendos acima contam a auditoria até a metade. Depois do último parágrafo, a branch
`fix/auditoria-2` atravessou **quatro rodadas de review independente, com correção em cada
uma**, e foi mergeada. É isto que faltava ao doc — e é o que o próximo a mexer no app precisa
ler antes dos adendos 1 e 2.

### O que foi mergeado

- PR **#15**, `fix/auditoria-2` → `main`, squash em **`d836d8d`** (2026-09-28 00:19): 69
  arquivos, 7 150 inserções, 658 remoções (`git show --stat d836d8d`). A branch tinha **42
  commits** (`git rev-list --count 0a46352..cd78073`), 13 dos quais merges de frentes
  trabalhadas em worktree paralelo (`git log --oneline --merges 0a46352..cd78073`: 11
  `worktree-agent-*` + `fix/widget-crash-e-tema` + `fix/estado-sobrevive-a-recriacao`). A
  rodada 2 leu a árvore quando ela tinha 36 commits
  (`git rev-list --count 0a46352..d2c02b4`); os consertos dela aterrissaram depois.

Ordem em que os consertos chegaram (a ordem em que o review os pediu é outra — ver a tabela
abaixo): `a76bc2f` (costura das frentes) · `b654ba8` (aviso adiado pela noite) · `cf9a2c4`
(assinatura do APK / lint) · `eeb126d` (pendente atrasada em Hoje) · `f22a036` (mutações
serializadas, entrega pendente, transações) · `69dc79e` (home: `agendaLoaded`, fala, código
morto, rótulo) · `0bbdab3` (teste do teto de tombstones) · `d2c02b4` (este doc) ·
`178fcd3` + `9290832` (varredura de rotação: sete pontos em que girar perdia a ação dela ou a
deixava sem resposta) · `2637153` (entrega pendente) · `5d38e0f` (desfecho em voo no giro) ·
`df2d0b7` (confirmação: duplicação e ação sem resposta) · `cd78073` (desfecho órfão e o id do
pedido).

### Os números finais

| Alvo | Resultado |
|---|---|
| `:domain:test` | **110** testes |
| `:app:testDebugUnitTest` | **230** testes |
| `:app:lintDebug` | **0 erros**, 82 avisos (os de sempre) |
| `:app:compileDebugAndroidTestKotlin` | compila — e só (ver pendências) |

**Como os números foram obtidos** (esta passada não rodou build): contagem de anotações na
árvore — `git grep -c "@Test" -- 'app/src/test'` somando a última coluna dá 230, e
`domain/src/test` dá 110 — **conferida contra os XMLs da última execução da branch**
(`app/build/test-results/testDebugUnitTest/*.xml` e `domain/build/test-results/**`, soma de
`tests=` = 230 e 110). Fonte e XML batem; lint pelos `app/build/reports/lint-results-debug.txt`
(`0 errors, 82 warnings`).

### As quatro rodadas

| # | Sobre o quê | O que achou | Onde fechou |
|---|---|---|---|
| 1ª | o diff das 13 frentes | 6 findings: corrida na agenda, o `agendaLoaded` que mentia, transações e 3 de qualidade (código morto, teste tautológico, lint `NewApi`) | `f22a036`, `69dc79e`, `0bbdab3`, `cf9a2c4` |
| 2ª | o diff inteiro (36 commits) | **P1** girar durante o "Salvando…"; **P2** o lembrete nunca entregue virando MISSED na virada do dia | `5d38e0f`, `2637153` |
| 3ª | os dois commits que responderam a P1/P2 | a resposta ao P1 estava pela metade; as cinco confirmações de ação continuavam morrendo no giro | `df2d0b7` |
| 4ª | o commit final | o back do sistema na tela "Daqui N min" saía sem consumir o desfecho | `cd78073` |

**1ª — o diff das 13 frentes.** As frentes rodaram em worktrees paralelos e o review foi
sobre o que elas produziram juntas:
- **Corrida na agenda** (`f22a036`): todo start dispara `rescheduleAll` num escopo sem lock e
  o receiver do alarme pode chamar `onAlarmFired` em paralelo; a varredura lia o banco antes e
  gravava depois do disparo (ou do "Excluir" dela) e desfazia o que o outro caminho acabara de
  decidir. Virou um `Mutex` no repositório — **só nas mutações; a agenda que a tela lê não
  espera**.
- **Transações** (`f22a036`): as escritas que tocam série e ocorrência passam por
  `OccurrenceDao.applyBatch` — uma transação do Room (`performInTransactionSuspending`).
  Morrer no meio não deixa mais a data excluída sem tombstone nem a série sem a ocorrência.
- **O `agendaLoaded` que mentia** (`69dc79e`): o "carregado" da agenda vinha de um `stateIn`
  diferente do que a busca por id usava — dois `observeAgenda()`. O toque no aviso do remédio
  respondia "Esta tarefa não está mais na agenda" com a lista ainda na inicial vazia e
  **descartava o id**. Agora os dois saem do mesmo valor (`AgendaUi`).
- **Qualidade ×3**: `quickRemind` sem chamador e a guarda inalcançável da rota
  `quick/{minutes}` (`69dc79e`); o teste do teto de tombstones que montava 200 datas para trás,
  o filtro de 90 dias reduzia para 91 antes do `take(120)` e o `isAtMost(120)` passava com
  qualquer implementação (`0bbdab3`); o `NewApi` que **quebrava o build** do lint porque
  `PackageInfo.signingInfo` era lido sem guarda — e nas APIs 26/27 o campo nem está na classe
  do sistema, onde lê-lo não devolve `null`, derruba a chamada (`cf9a2c4`).

**2ª — o diff inteiro.**
- **P1 » `5d38e0f`.** O desfecho de uma gravação em voo era escrito no estado da composição
  que a rotação já tinha descartado: a caixa "Pode salvar?" continuava cheia e sem confirmação
  nenhuma, e o toque seguinte salvava o mesmo recado de novo — `TaskRepository.saveDraft`
  **sempre cria uma série nova** — virando **duas tarefas e dois alarmes no mesmo horário**. A
  falha tinha o mesmo destino, calada. O desfecho passa a morar no `HomeViewModel`
  (`draftSaveOutcome`), com a origem de quem pediu e um número de evento: dois desfechos iguais
  em sequência dão a mesma frase, e o `StateFlow` não emite valor igual ao atual.
- **P2 » `2637153`.** O lembrete das 22:00 que o Doze segurou chega sem `lastReminderAt` (a
  escada nem começou). O `entregaPendente` exigia um aviso **já entregue** e devolvia false, e
  a primeira varredura depois da meia-noite marcava a ocorrência como não realizada e cancelava
  o alarme: o único aviso do dia morria sem tocar. Com um aviso já entregue o mesmo restart
  mantinha tudo pendente — a assimetria contradizia o KDoc do próprio método.

**3ª — sobre os dois commits que responderam a P1/P2.**
- **A resposta ao P1 estava pela metade.** O botão **"Mudar"** da caixa "Pode salvar?" era o
  único ainda clicável durante a gravação (o "Salvar" já não era, o "Cancelar" também não
  depois): ela tocava Salvar, tocava Mudar, e a tela de confirmação abria com o mesmo recado —
  terminada a gravação, o "Salvar" de lá rearmava e criava a segunda série. **"Salvar → Mudar →
  Salvar" duplicava sem rotação nenhuma**, que é o que o P1 dizia ser preciso girar o aparelho
  para provocar. Fechar a caixa (voltar, tocar fora, "Cancelar") tinha o mesmo efeito.
- **As cinco confirmações de ação continuavam morrendo no giro.** O recado da última ação
  ("Feito.", "Tarefa excluída.", "Vai avisar amanhã às 8h.", "Série encerrada.", o do
  "remarcar") era escrito no `onDone` de quem pediu — um `MutableState` já descartado. A
  tarefa era concluída, excluída ou adiada **sem aviso nenhum**. Agora mora no `HomeViewModel`
  (`statusMessage`, com `seq` e consumo explícito), publicado pelo `write`, que roda no escopo
  do ViewModel e atravessa o giro. O `seq` é o que faz dois "Feito." seguidos serem dois
  eventos — sem ele o segundo não chegava e a tela ficava com o **desfazer armado do
  primeiro, da tarefa errada**. O desfazer passou a viajar no próprio recado (`undo`), e o
  `undoableDelete` sumiu.
- Na mesma rodada: concluir, adiar, "Fazer hoje", "Amanhã de novo", excluir, encerrar série e
  cancelar saem do alcance durante a gravação — duas escritas concorrentes sobre a mesma
  ocorrência se atropelam no banco —, e o "Daqui N min" ganhou "Salvando…" com o botão fora da
  mão dela (o segundo toque era engolido em silêncio).

**4ª — sobre o commit final.**
- **O back do sistema** na tela "Daqui N min" (`WriteTaskScreen`, o mesmo composable da tela de
  escrita) popava durante o "Salvando…" — só o botão "Cancelar" estava desabilitado. O desfecho
  chegava depois, **sem quem o anunciasse**: ela não ficava sabendo nem do aviso salvo, nem do
  erro.
- Pior: o desfecho de uma gravação **anterior**, ainda no slot, era consumido pela tela
  recriada pelo giro, que anunciava a **frase antiga** e saía — deixando a **falha da gravação
  nova sem aviso**. Confirmação de sucesso para um recado que não existia.

### A lição: cada correção fechou o caso apontado e deixou aberto um caminho adjacente

As quatro rodadas dizem a mesma coisa por quatro ângulos, e é o que interessa a quem ler
depois: **o fix certo fecha o caminho que o review apontou e deixa aberto o caminho
adjacente** — a saída por outro botão (o "Mudar" que ninguém tinha gateado), pela rotação
(o `onDone` da composição descartada), pelo gesto do sistema (o back do `WriteTaskScreen`).
Bloquear caso a caso é uma corrida que se perde.

A última rodada resolveu **por construção**, e é o desenho que ficou:

1. O desfecho carrega o **id do pedido** (`DraftSaveOutcome.requestId`), criado **no pedido** e
   não na conclusão (`HomeViewModel.newDraftSaveRequest`, `saveRequestSeq`), e a tela só
   consome o desfecho cujo `requestId` é o que ela mesma guardou — nem de outra tela, nem de
   uma gravação anterior dela (`FalaAgendaRoot` compara origem **e** id em cada rota).
2. O que está em voo é do **ViewModel** (`pendingDraftSaves`, um `Set`), não do Bundle: a morte
   do processo não deixa a tela presa em "Salvando…" para uma gravação que não existe mais, e a
   tela sabe se a gravação **dela** está em voo sem confundir com o `busy` de outra.
3. Onde o desfecho não pode ser consumido, a saída não existe: `BackHandler(enabled = saving)`
   na escrita (`WriteTaskScreen.kt:66`) e na confirmação (`ConfirmDraftScreen.kt:125`), mais
   `onDismissRequest`/`enabled` no "Pode salvar?" (`QuickConfirmDialog.kt:41,73`).

### O que ficou sem prova

**Nada foi rodado em aparelho ou emulador — não há device nesta máquina.** O comportamento de
composição (botão desabilitado, "Salvando…", o `BackHandler` bloqueando o gesto, a ordem do
snackbar) está garantido por **leitura de código e compilação**, não por execução. Os testes
cobrem o que é do ViewModel e do repositório (Robolectric nos casos que precisam de banco); o
que é composição, não.

Riscos residuais que os próprios reviews declararam **sem caminho natural de reprodução**:

- o `draftSaveOutcome` é um **slot único**: duas gravações de origens diferentes sobrepostas
  (ex.: salvar na confirmação enquanto o "Daqui N min" também grava) fazem o segundo desfecho
  sobrescrever o primeiro — quem pediu o perdedor não é avisado;
- `handleDraft` (`HomeScreen.kt:208`) **sobrescreve a caixa aberta**: um recado novo chegando
  com o "Pode salvar?" na tela troca o rascunho da caixa pelo do recado novo;
- `undo`/`restore` usam o `AgendaItem` **congelado no momento da exclusão** (ele viaja dentro
  do recado) — se a ocorrência mudar por outro caminho antes do "Desfazer", o desfazer age com
  o retrato velho.

### O que fica para decidir (produto — sem recomendação)

- Os secrets `SUPABASE_URL` / `SUPABASE_ANON_KEY`, **quando existem**, precisam ser o host do
  projeto e uma chave `role: anon` — o release recusa URL fora do formato, chave que não seja
  `anon` (uma `service_role` num APK distribuído entrega o banco) e configuração pela metade.
  **Os dois ausentes não travam a release** (corrigido em 05/10/2026): o app roda inteiro sem
  a ajuda extra, e foi assim que saíram todas as releases até a v0.5.2. O gate do #29 tratava
  ausente como errado e abortou a v0.6.0-rc1 — o PR #40 separou os dois casos.
- `JANELA_ENTREGA_PENDENTE = 6 h` (`TaskRepository.kt:589`) é **decisão de produto**: um
  lembrete mais de 6 h atrasado expira — celular desligado a noite toda não toca a dose das
  22:00 de manhã.
- O cabeçalho do compartilhamento ainda diz **"Hoje no Fala Agenda:"** mesmo quando a única
  linha é de ontem (`AgendaFormat.todayShare`). A linha ganhou a marca do dia ("ontem"), o
  cabeçalho não mudou.
- O widget **perde o canto arredondado** quando um tema explícito aplica cor:
  `setInt(widget_root, "setBackgroundColor", …)` sobrepõe o `@drawable/widget_background` (que
  é quem tem os `corners` de 20dp), e o fundo vira retângulo reto.
- `SecureTokenStore` (`data/prefs/SettingsStore.kt:94`) ainda **cai para SharedPreferences em
  texto claro** (`fala_agenda_secure_fallback`) quando o Keystore não está disponível.
- O `ci.yml` **só compila** o teste instrumentado (`:app:compileDebugAndroidTestKotlin`, linha
  51): não roda emulador, então o que depende de device não tem gate.
- `.context/memoria/` **não existe** neste repo — a memória do projeto não está publicada onde
  o time (e o Codex/Grok) a vejam.
