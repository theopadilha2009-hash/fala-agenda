# Fala Agenda

Agenda por voz para Android. Você fala o recado, confere o que foi entendido e o aviso toca no horário. As tarefas ficam **só no aparelho**.

Versão **0.6.0** · pacote `com.theopadilha.falaagenda` · copyright 2026 Theo Lorentz Padilha. Todos os direitos reservados (veja `LICENSE`).

## O que o aplicativo faz

- Tela principal com **Hoje**, **Próximas**, **Concluídas** e **Não realizadas**.
- Ícone e splash com microfone. O botão **pulsa** parado e faz ondas enquanto ouve.
- Microfone **sempre embaixo**, visível ao abrir. Toque uma vez — **não precisa segurar**. O aplicativo espera ficar pronto e só então mostra **Pode falar agora**. Se o microfone interno falhar, abre o reconhecimento do próprio celular. Se não ouvir, tenta de novo sozinho.
- Primeira visita em **uma** tela.
- Atalho de lançador **Falar** (segurar o ícone do aplicativo).
- Estados **Ouvindo** e **Entendendo**, resultados parciais, para após silêncio.
- Confirmação editável obrigatória antes de salvar. Data e hora se escolhem no calendário e no relógio — **não são inventadas**.
- Tarefas únicas ou recorrentes (todo dia, dias úteis, semanal com vários dias, mensal, anual).
- Cada ocorrência se conclui sozinha. Se a próxima nascer e a anterior ainda estiver pendente, a anterior vira **não realizada** e o aviso dela é cancelado.
- Lembretes com `AlarmManager`: no horário, depois +15 min, +30 min, depois de hora em hora. Das 22h às 8h as **repetições** pausam; o primeiro aviso no horário combinado ainda toca. A repetição volta às 8h.
- Na notificação: **Concluir** e **Adiar 30 min**, sem abrir o aplicativo.
- Confirmação com atalhos de data, horário e repetição (incluindo dias da semana).
- Tarefa única **não realizada** pode voltar com **Fazer hoje** (hoje se o horário ainda não passou; senão amanhã, no mesmo relógio).
- No cartão da tarefa pendente: adiar 10 min, 30 min ou 1 hora.
- Horário de silêncio se escolhe no relógio, sem digitar.
- Tela inicial com saudação e o próximo aviso. Cartões dizem “daqui X min”.
- Depois de salvar, o recado diz **quando** vai avisar. Concluir tem desfazer.
- “daqui 10 minutos” (com ou sem “a”) vira horário.
- Se o recado já veio completo, um diálogo **Pode salvar?** confirma em um toque. **Mudar** abre a tela cheia.
- No cartão pendente: **10 min**. No concluído único: **Amanhã**.
- Menu lateral: resumo do mês, atualizar, enviar o aplicativo, não matar alarmes, widget, aparência claro/escuro.
- Resumo do mês com o que mais você fez (ex.: cabelo 3 vezes) e soma opcional de valores.
- Na confirmação, campo **Valor (opcional)** para coisa paga.
- Atualização pelo próprio aplicativo: na home aparece **Tem versão nova** quando sai um APK no GitHub. A primeira instalação ainda é por arquivo; as seguintes a mamãe só toca em atualizar.
- Widget 2×2 com a próxima tarefa e o botão **Falar**.
- Ícone mais claro (creme + microfone verde).
- Letra maior, botões de 56 dp. Toque na tarefa abre tela cheia para editar e escrever. Escrever / daqui 5 min também são tela cheia, sem diálogo apertado.
- Campo **Observação** na tarefa. Menu **Enviar o dia** manda a lista de hoje por WhatsApp.

## Arquitetura

Camadas simples, sem Hilt:

| Camada | Onde | Papel |
|---|---|---|
| Domínio | módulo `:domain` (JVM) | Parser pt-BR, recorrência, política de lembretes, `Clock`/`ZoneId` injetáveis |
| Dados | `:app` Room | Única fonte das tarefas (`task_series` + `task_occurrences`) |
| Plataforma | `:app` | `SpeechRecognizer`, `AlarmManager`, notificações, DataStore, EncryptedSharedPreferences |
| UI | Jetpack Compose Material 3 | pt-BR, mobile-first, Figtree empacotada |

O parser local é determinístico. Só se o resultado ficar **ambíguo**, a IA estiver ativada e houver rede, o aplicativo envia **somente** `transcript`, `now`, `timezone` e `locale` para a Edge Function `parse-reminder`. Sem internet, ativação, cota ou sucesso, o rascunho local permanece para correção manual.

## Privacidade

- Tarefas e histórico **não** sobem para servidor nosso, e o aplicativo **nunca envia áudio** a servidor nenhum. Antes do modelo de fala offline estar instalado — e **sempre que ele falhar**, que é quando o aplicativo volta ao motor do sistema — quem ouve é o motor do celular, que pode usar a rede (ver abaixo).
- Backup automático do Android está desligado para o banco e preferências.
- Códigos de ativação são armazenados no servidor só como hash. O token do aparelho fica no Keystore (`EncryptedSharedPreferences`). **Se o Keystore não puder ser montado**, ele cai para `SharedPreferences` comum, **sem cifra**, para o aplicativo não ficar sem falar com o servidor — não é o caminho normal, e não há aviso na tela.
- **O texto do recado chega à OpenAI, e só por aí.** Quando o parse local fica **ambíguo**, a ajuda por IA está ativada e há rede, o aplicativo envia `transcript`, `now`, `timezone` e `locale` para a Edge Function `parse-reminder`, que repassa o mesmo texto a `api.openai.com` (`OPENAI_MODEL`, padrão `gpt-5-nano`) para virar rascunho. A função não grava transcript nem título, e o transcript não é guardado como dado da tarefa: ele existe no rascunho, enquanto a confirmação está aberta. O **título** é outra coisa — ele *é* a tarefa, e fica no aparelho, na agenda dela. Mas **a OpenAI recebe o texto**, e a política de retenção dela é a que vale. Sem ativação, sem rede, ou com o parse local resolvido sozinho, nada **da fala** sai do aparelho.
- O aplicativo baixa o modelo de fala offline no primeiro toque no microfone: 31 MB de `alphacephei.com` (`vosk-model-small-pt-0.3`). O endereço de partida é fixo e **o conteúdo tem que bater com o SHA-256** gravado no código, venha de onde vier. Uma vez baixado, é ele que transcreve no próprio aparelho, sem rede; se o download falhar, ele tenta de novo no próximo toque.
- Sem URL/chave Supabase o aplicativo funciona normalmente e a ajuda extra aparece como **não ativada**.
- **No APK de release, configurado é para estar certo — ou não estar lá.** O workflow lê os secrets `SUPABASE_URL` e `SUPABASE_ANON_KEY` e **aborta** se a URL não for exatamente o host do projeto, se a chave não for anon/public, ou se só uma das duas estiver preenchida (meia configuração é quase sempre secret que sumiu ou nome trocado). **Os dois ausentes não travam a release**: o APK sai sem a ajuda extra, como saíram todas as releases publicadas até a v0.5.2 — o gate trata ausência como recurso desligado, e erro como erro. Depois de compilar, quando os dois existem, o gate confere que os valores estão **dentro do dex**.

## Secrets do repositório (release)

Em Settings → Secrets and variables → Actions → Secrets:

| Secret | Valor |
|---|---|
| `SUPABASE_URL` | `https://SEU-PROJETO.supabase.co` — **só o host**: sem caminho, porta, `?` ou `#`. Barra no fim é aceita e removida antes de entrar no APK |
| `SUPABASE_ANON_KEY` | JWT **anon/public** (o payload precisa ter `role: anon`). A `service_role` é reprovada: ela ignora RLS e quem extrair a chave do APK ficaria com o banco inteiro |
| `RELEASE_KEYSTORE_BASE64` | keystore de release em base64 |
| `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | dados do keystore |

O release confere, antes de publicar: formato da URL (regex ancorada, só `https://<projeto>.supabase.co`)
e da chave (`role: anon`, decodificando o JWT — o valor nunca vai para o log), os dois valores dentro
do dex **quando configurados**, `versionName` igual à tag e impressão digital do certificado igual à
da release anterior (senão o app instalado não aceita a atualização por cima).

**Pré-release**: tag com sufixo (`v0.6.0-rc1`) é comparada com a versão base (`0.6.0`, que é o
`versionName`) e publicada marcada como pré-release. O app lê `/releases/latest`, que nunca devolve
pré-release — então o rc não é oferecido como atualização.

## Requisitos de build

- JDK 17
- Android Gradle Plugin 9.3.0
- Android SDK 36, build-tools 36.0.0, platform-tools
- Gradle Wrapper 9.5.0 (já versionado)
- Deno 2.x para os testes das Edge Functions

## Setup local

```bash
cp local.properties.example local.properties
# Ajuste sdk.dir para o SDK desta máquina.

# Opcional: URL e chave anon do Supabase (só functions; tabelas sem acesso do cliente)
# SUPABASE_URL=...
# SUPABASE_ANON_KEY=...
```

Não copie segredos reais para o Git. Use `.env.example` como modelo das variáveis de servidor.

## Build e testes

Rode os alvos **separados** (evita OOM em máquinas justas):

```bash
export JAVA_HOME="$( /usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home )"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :domain:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
```

APK debug (instalável):

`app/build/outputs/apk/debug/app-debug.apk`

Testes das functions (OpenAI mockado):

```bash
deno test --allow-env supabase/functions
```

## Instalar o APK no aparelho

1. No telefone: Ajustes → Segurança → permitir fontes desconhecidas / instalar apps deste computador.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Abra **Fala Agenda**. O onboarding pede microfone, notificações e alarmes exatos, explicando o porquê de cada um.

O APK debug usa o sufixo `.debug` no applicationId (`com.theopadilha.falaagenda.debug`).

## Permissões

| Permissão | Por quê |
|---|---|
| Microfone | Só enquanto o aplicativo está ouvindo, depois do toque |
| Notificações | Aviso na hora, com Concluir / Adiar |
| Alarmes exatos (`SCHEDULE_EXACT_ALARM`) | Tocar no horário combinado. Sem `USE_EXACT_ALARM`. |
| Ignorar otimização de bateria | Pedido opcional para o Android não matar os alarmes. |
| Instalar pacotes | Só para atualizar o APK publicado, sem loja. |

Se o alarme exato for recusado, a tarefa **é salva**, o alarme cai no modo inexato e aparece um aviso com atalho para os ajustes.

Após `BOOT_COMPLETED`, mudança de fuso/hora ou concessão da permissão de alarme, os avisos são reagendados.

## Recorrência (regras)

- Mensal nos dias 29, 30 ou 31: último dia válido daquele mês.
- Anual em 29 de fevereiro: 28 de fevereiro em ano não bissexto.
- Tarefa recorrente criada com o horário de hoje já passado começa na próxima data válida — a mesma conta que a fala já usava.
- O horário local fica no `ZoneId` da série (fuso do aparelho na criação). Mudança de fuso do sistema não reescreve esse horário local.

## Ativação da ajuda extra (opcional)

1. Coordenador sobe o projeto Supabase (fora deste repositório local).
2. Gera um código de uso único:

```bash
ADMIN_SECRET=... SUPABASE_URL=... SUPABASE_SERVICE_ROLE_KEY=... \
  deno run --allow-env --allow-net supabase/scripts/generate-activation-code.ts
```

O valor em claro aparece **uma vez**. Só o hash vai ao banco.

3. No aplicativo: Configurações → colar o código. O token fica no Keystore.
4. Limite: 30 usos de `parse-reminder` por instalação por dia.

## CI e release

- `.github/workflows/ci.yml` — lint, testes, `assembleDebug`, testes Deno e um `assembleRelease`
  com valores-sentinela de Supabase, conferindo por `grep` no dex que a config chega ao APK. Sem
  isso, a injeção da config só era exercitada na tag — se quebrasse, o release quebrava na hora de
  publicar. Nenhum secret entra nesse passo.
- `.github/workflows/release.yml` — tag `v*`: confere formato da URL e da chave anon, reconstrói o
  keystore a partir de secrets, assina o APK, calcula SHA-256, roda o gate do artefato e publica com
  `gh` (marcando pré-release quando a tag tem sufixo). **Não gera keystore neste repositório.**

Secrets de release (o coordenador configura no GitHub): `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

## Limites conhecidos

- Reconhecimento de fala depende do motor do aparelho (pode precisar de rede do Google, mas o áudio não é enviado ao nosso servidor).
- Sem emulador/aparelho nesta máquina de build: a verificação local é testes JVM (`:domain:test`, `:app:testDebugUnitTest`), `lintDebug`, `assembleDebug`, `assembleRelease` e `compileDebugAndroidTestKotlin`. O teste instrumentado de confirmação existe, mas não roda sem aparelho/emulador.
- `android.disallowKotlinSourceSets=false` é necessário no AGP 9.3 enquanto o KSP registra fontes geradas do Room via `kotlin.sourceSets`. Não é um desligamento genérico de checagem.
- A Edge Function precisa ser publicada pelo coordenador na organização pessoal; o app local não faz deploy.
- Horário de silêncio pausa repetições, não o primeiro aviso.
- Expressões vagas (“à noite”, “depois do almoço”) abrem a confirmação sem inventar horário.
- Este repositório é público na forma, mas o código é proprietário.

## Estrutura

```
domain/     parser, recorrência, lembretes (testes unitários com relógio fixo)
app/        Android, Room, UI, alarmes, voz
supabase/   migrations, functions, script administrativo
.github/    CI e release por tag
```
