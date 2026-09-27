# Auditoria completa — Fala Agenda v0.5.2

Data: 2026-09-27 · Escopo: todo o repositório · 8 auditores read-only em paralelo
Motivo: relato "o app simplesmente não funciona"

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

**Tombstone de data excluída não era gravado.** `Entities.kt:84` (`TaskSeries.toEntity()`) não
escrevia `skippedDates`, então o `skipDate` do repositório era descartado na saída: a data
excluída voltava a nascer no próximo `advance()`. Corrigido em `toEntity()`. Coberto por
`skippedDatesSobreviveAoBanco` e pela migração v1→v4.

Lição para o próximo review: teste com DAO falso não vê semântica de Room (FK, REPLACE, índices,
NOT NULL). Toda mudança de schema/DAO precisa de um teste com banco real.
