# Fala Agenda

Agenda por voz para Android. Você fala o recado, confere o que foi entendido e o aviso toca no horário. As tarefas ficam **só no aparelho**.

Versão **0.2.4** · pacote `com.theopadilha.falaagenda` · copyright 2026 Theo Lorentz Padilha. Todos os direitos reservados (veja `LICENSE`).

## O que o aplicativo faz

- Tela principal com **Hoje**, **Próximas**, **Concluídas** e **Não realizadas**.
- Botão grande de microfone (só começa a ouvir quando você toca). Entrada por texto se preferir.
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

- Tarefas, áudio e histórico **não** sobem para a nuvem.
- Backup automático do Android está desligado para o banco e preferências.
- Códigos de ativação são armazenados no servidor só como hash. O token do aparelho fica no Keystore (`EncryptedSharedPreferences`).
- `parse-reminder` não grava transcript nem título. Logs mínimos, sem PII.
- Sem URL/chave Supabase o aplicativo funciona normalmente e a ajuda extra aparece como **não ativada**.

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
| Microfone | Só enquanto você segura o botão de falar |
| Notificações | Aviso na hora, com Concluir / Adiar |
| Alarmes exatos (`SCHEDULE_EXACT_ALARM`) | Tocar no horário combinado. Sem `USE_EXACT_ALARM`. |

Se o alarme exato for recusado, a tarefa **é salva**, o alarme cai no modo inexato e aparece um aviso com atalho para os ajustes.

Após `BOOT_COMPLETED`, mudança de fuso/hora ou concessão da permissão de alarme, os avisos são reagendados.

## Recorrência (regras)

- Mensal nos dias 29, 30 ou 31: último dia válido daquele mês.
- Anual em 29 de fevereiro: 28 de fevereiro em ano não bissexto.
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

- `.github/workflows/ci.yml` — lint, testes, `assembleDebug` e testes Deno.
- `.github/workflows/release.yml` — tag `v*`: reconstrói o keystore a partir de secrets, assina o APK, calcula SHA-256 e publica com `gh`. **Não gera keystore neste repositório.**

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
