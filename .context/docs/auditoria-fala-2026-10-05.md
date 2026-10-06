# Auditoria — fala, conversa e alarme (05/10/2026)

Duas auditorias por leitura de código, feitas em paralelo, sobre o caminho real da
mãe: ela fala, o app entende, o alarme toca. Nenhuma delas foi feita "no escuro":
cada defeito abaixo tem arquivo, linha e o cenário concreto de falha.

O que este documento é: o registro dos defeitos **verificados** e do que já está
correto. Vários viraram PR no mesmo dia (ver o fim). O resto fica aqui para não
voltar a ser descoberto do zero.

---

## Parte 1 — fala e conversa

### 1. [ALTA] Duas tarefas numa frase viram uma só, com confiança alta

`domain/.../parser/LocalTaskParser.kt`

Frase: **"marcar médico terça e tomar remédio às oito"**.

`extractRecurrence` (`:226-262`) não acha recorrência; `extractTime` acha **só um**
relógio — `CLOCK_WORD` casa "às oito" (`:315-319`, `:593-595`) → 08:00;
`extractDate` acha **um** dia de semana ("terça") → próxima terça (`:454-469`).
Com um só dia, `ambiguous = false`. O título sai do texto restante inteiro
(`:532-552`): **"Marcar médico tomar remédio"**, terça 08:00, `isComplete = true`.

Como `canQuickConfirm` só barra por `ambiguous`/incompleto (`Models.kt:73-83`), a
caixa rápida abre prometendo **"Terça às 08:00"** para as duas coisas coladas.

Pior: "marcar médico terça e tomar remédio **todo dia** às oito" também cola — a
recorrência diária vence e o "terça" sobra no título.

**Deveria:** detectar duas orações (dois verbos/tarefas, ou uma com dia e outra com
hora) e marcar ambíguo. Nunca colar as duas num título com `ambiguous = false`.

### 2. [ALTA] Não existe camada de comando — falar "cancela" cria tarefa

Nenhum `Intent`/`Command` de domínio em `app/src/main` nem `domain/src/main` (o
único `Intent` é `android.content.Intent` de notificação/microfone). Todo texto
reconhecido vai direto para `viewModel.speech.understand(text)`
(`ui/home/HomeScreen.kt:311-318`) → `HybridParser.parse`
(`ui/home/HomeViewModel.kt:705-706`) → tratado como **tarefa nova**.

| ela fala | o app faz hoje |
|---|---|
| "cancela o médico" | cria a tarefa **"Cancela o médico"** — a consulta continua marcada |
| "muda pra quinta" | tarefa "Muda pra", quinta |
| "já tomei" | tarefa "Já tomei" |
| "apaga isso" | tarefa "Apaga isso" |
| "o que tenho hoje?" | data = hoje, sem hora, título vazio → tela pedindo "o que precisa ser feito" |

O dano não é a tarefa boba criada: é ela **acreditar** que cancelou, mudou ou
registrou algo. Ação destrutiva que mente é o pior formato de defeito neste app.

### 3. [ALTA] Falha de reconhecimento não escala para a IA — só `ambiguous` escala

`domain/.../parser/HybridParser.kt:28-52`

O único gatilho do remoto era `if (!localDraft.ambiguous) return localDraft`. Mas o
`LocalTaskParser` **não** marca `ambiguous` quando simplesmente **não conhece** a
expressão: devolve data/hora nulas, confiança ~0.55 e `ambiguous = false`
(`LocalTaskParser.kt:104-124`).

Ou seja: "não entendi" e "entendi ambíguo" são coisas diferentes, e só a segunda
chamava a IA. Para "quinze horas", "semana que vem", "no dia 25", "dois de maio",
"à noitinha" o local devolvia `ambiguous = false`, a IA nunca era consultada, e a
tela mostrava só "Falta a data. Não inventamos um dia." — ela digitava na mão, sem
a ajuda que o app diz ter.

**É a raiz de 4, 5 e 7 abaixo.** Quando o local simplesmente não conhece a
expressão, ele devolve silêncio com `ambiguous = false` e a IA fica de fora. Para a
mãe é o pior formato de erro: a tela não promete errado (por sorte), mas também não
ajuda — e ela não tem como saber que era só o app não conhecer a frase.

### 4. [MÉDIA-ALTA] Horas por extenso de 13 a 23 não existem

`CLOCK_WORD` (`:593-595`) só aceita
`uma|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze`, e `WORD_HOURS`
(`:655-669`) só mapeia 1–12. "quinze", "dezesseis", "vinte e três" não estão no
mapa; `CLOCK_NUMERIC` (`:590-592`) exige dígitos; `CLOCK_BARE` (`:596-598`) exige
"às" + dígito. Então "às quinze horas" → nenhum horário, e "oito e meia" sem "às" →
nenhum horário.

Isto importa mais do que parece: o **Vosk pt-BR costuma devolver palavras**
("quinze horas") e o motor do sistema pode devolver **dígitos** ("às 15 horas") —
o mesmo recado funciona ou não conforme o motor que atendeu.

### 5. [MÉDIA-ALTA] Datas relativas comuns não são tratadas

- "semana que vem": `WEEKDAY_NEXT_WEEK` exige um dia de semana antes de "que vem"
  (`:640-643`) → sozinho, sem data.
- "daqui a pouco": `extractRelative` só cobre "daqui (a) X minutos/horas" e "daqui a
  meia hora" (`:365-403`) → sem data nem hora.
- "no dia 25": as regex de data exigem mês por extenso, barra/hífen, ou recorrência
  ("todo dia N"); `monthly` (`:202-211`) exige "todo(s)" → sem data.
- "no começo do mês": nenhuma regex.

Todos ficam com `ambiguous = false` → **não escalam** (defeito 3), e o número "25"
ou "começo do mês" some ou polui o título.

### 6. [MÉDIA] Hora "pelada" vira madrugada com cara de certeza

"amanhã às três" (querendo 15h) → `CLOCK_WORD` casa "às três" → **03:00**,
`ambiguous = false`, completo → a caixa rápida promete "amanhã às 03:00". Não há
nenhum ajuste de manhã/tarde quando o período não foi dito.

Cuidado ao consertar: "às 3 da tarde" → 15h **já funciona** (`applyPeriodHour`,
`:505-513`) e não pode virar ambíguo. Só o caso sem período.

### 7. [MÉDIA] "à noitinha" e dia por extenso não existem

"à noitinha" é palavra única; `\bnoite\b` não casa dentro de "noitinha"
(`:485-488`) → sem período, sem hora. "dois de maio": a regex de data por extenso
exige o dia em **dígito** (`:442-444`) → sem data, e "dois de maio" sobra no título.

### 8. [MÉDIA-BAIXA] Duas formas erradas específicas

- **"às 12 e meia da noite" → 12:30 (meio-dia).** `CLOCK_BARE` captura "às 12" e o
  grupo de período só olha imediatamente depois do número (`:596-598`), então "da
  noite" (que vem depois de "e meia") não entra; `applyPeriodHour(12, "")` devolve 12
  (`:505-513`) e `extractPeriodHint` já não aplica porque a hora não está em 1..11
  (`:80-88`). Há ramo correto para "meia-noite e meia" (`:301-305`), mas não para
  esta forma.
- **"todo dia 5" → mensal dia 5.** Casa `monthly` (`:202-211`), que exige "todo(s)".
  Se ela omitir o "às", vira uma recorrência mensal silenciosa. Precisa diferenciar
  "todo dia N" de "todo dia às N".

### PRs abertos no mesmo dia

| PR | frente | defeito |
|---|---|---|
| #42 | manchete da home | a manchete contava os dois motivos de `missed` como um só e culpava ela pela falha do app |
| #43 | `HybridParser` | frases que o parser local não conhece passam a consultar a IA (defeito 3) |
| #44 | recap da home + resumo do mês | os dois cartões fundiam `NOT_WARNED` e `NOT_DONE`; agora separam pela mesma decisão canônica (`missedReason`) |
| #45 | `LocalTaskParser` | os 6 defeitos de fala (duas tarefas numa frase, horas 13–23 por extenso, datas relativas, hora pelada, noitinha, dia por extenso) |
| #46 | textos | onboarding sem culpa, "encerrar série" com confirmação, "Cancelar" fora do slot primário |
| #47 | jargão do alarme | as três frases de alarme inexato que **de fato** aparecem para ela (estavam hardcoded em `HomeScreen.kt`) |
| #48 | comandos de fala | "cancela o médico" deixa de criar uma tarefa com esse nome (defeito 2) — mergeado `2d4cb74` |
| #49 | alarme no passado | editar/desfazer tarefa recorrente para hoje já passado deixa de disparar na hora (áudio 1 e 2) — mergeado `5ede588` |
| #51 | adiamento no silêncio | o "Adiar 30 min" das 23:45 toca às 00:15 em vez de ser empurrado para as 08:00 — mergeado `554675a` |
| #53 | porta de texto da escrita | "cancela o médico" escrito na tela "Escrever tarefa" deixa de criar essa tarefa — mergeado `27dd4c0` |

Todos entraram em `main` em 06/10/2026, com CI verde e prova por mutação nos pontos corrigidos.

### O que o review adversarial pegou, e o que o CI não pegou

Nove PRs entraram em `main` em 06/10. **Quatro deles foram reprovados pela revisão independente depois de passar CI verde, suíte verde e prova por mutação** — e três dos quatro carregavam a mesma classe de dano (data/hora errada, completa e `ambiguous = false`, confirmada em silêncio pela caixa rápida):

| PR | o que passou verde e estava errado |
|---|---|
| #49 | a tela prometia uma data que o repositório descartava por tombstone — **ficou no `main`**, o fix está em voo |
| #50 | o PR introduziu o próprio bug: remover o cartão transitório deixou `_inexactWarning` órfão |
| #52 | o executor fez prova por mutação **real e inútil** — não existia teste para o caso errado |
| #55 | consertou os cinco alvos e criou **seis regressões novas** da mesma classe |

E dois testes foram pegos **existindo, passando, e não prendendo nada**: o `comandoEscritoSaiDaTelaENaoCriaTarefa` do #53 (o `NavHostController` sem grafo tornava o `popBackStack()` um no-op silencioso) e o teste de call-site do cartão de saúde do #50 (prendia a bateria, não a exatidão — a revisora provou mutando `canScheduleExact` para constante e vendo a suíte inteira seguir verde). O `todaSemanaEhSerieSemanal` do #55 nunca afirmava o dia da semana, que é por isso que o F1 passou com 753 testes verdes.

A lição, para o próximo leitor: **CI verde + suíte verde + prova por mutação não são prova.** A pergunta que decide é "este teste falharia se a linha que ele promete proteger sumisse?" — e quem a faz é o review adversarial, antes do merge.

### Ainda aberto depois desta leva

**O APK que ela tem é anterior a tudo isto.** O `FalaAgenda-v0.6.0-rc1-release.apk` (Desktop,
05/10 19:19, sha `14d925bb…`) é bit a bit o asset da release `v0.6.0-rc1`, cuja tag `33e0f1a` é
**anterior** a #42–#49. Nenhuma correção de fala/alarme chegou ao aparelho dela. Enquanto não sair
uma release nova, ela continua com o app quebrado — o código corrigido não muda nada no celular.

**A ajuda extra de IA não existe na release.** `SUPABASE_URL` e `SUPABASE_ANON_KEY` não estão nos
secrets do repo (só as 4 do keystore), então toda release sai com `SupabaseConfig.isConfigured =
false` e a fala cai só no parser local. O #43 (escalar para a IA quando o local não entende) está
no código, mas sem as chaves nunca dispara no aparelho dela.

**Buracos de fala ainda sem PR** (auditoria de 06/10, confirmada por execução contra o parser real
de `main`; harness em `/tmp`, nenhum arquivo do repo tocado). Os piores saem com rascunho completo
e `ambiguous = false`, então a caixa rápida confirma sem consultar a IA e a data errada é agendada
em silêncio:

| # | frase | hoje | dano |
|---|---|---|---|
| 1 | "sexta-feira santa missa às 15h" | a sexta desta semana | data errada em silêncio; a real é 26/03/2027 |
| 2 | "amanhã depois do jantar às oito" | 08:00 | devia ser 20:00 |
| 3 | "amanhã no fim do mês às 10h" | amanhã | o "amanhã" ganha em silêncio |
| 4 | "amanhã dia 25 do mês que vem às 9h" | amanhã + série MONTHLY | data única virou recorrência |
| 5 | "amanhã pela manhã às oito" | título "Pela" | a tarefa se chama "Pela" |
| 10 | "amanhã meio da tarde às quatro" | título "Meio" | idem |

E os que deixam campo nulo (escalam para a IA **se** ela estiver ligada): "duas da tarde" sem o "às"
(o reconhecedor derruba o "às"), "três horas em ponto", Natal/Páscoa/Finados sem data, "toda
semana"/"daqui a duas semanas".

## Terceira passada de áudio (06/10, confirmada no código atual `5ede588`)

**"Adiar 30 min" que cai no silêncio é empurrado para as 08:00, em silêncio.** `TaskRepository.fire`
(`:519`) trata qualquer `reminderStep > 0` como repetição da escada e aplica o horário de silêncio;
mas o `snooze` grava `STEP_HOURLY` (`ReminderPolicy.kt:96-107`) e o próprio comentário lá diz que
"é ação explícita da usuária e não é repetição: vale no horário pedido, mesmo depois da meia-noite".
**Cenário**: 23:45, ela toca "Adiar 30 min"; o alarme é armado para 00:15; às 00:15 o `fire` vê
`step = 3 > 0` dentro de 22:00–08:00 e reagenda para 08:00. O adiamento que ela pediu não toca e a
tela não conta. As duas peças discordam sobre quem vence o silêncio. O sinal para distinguir existe
(`snoozedUntil`, gravado só pelo `snooze`) — cuidado: ele não é limpo ao entregar, então usá-lo cru
isenta a escada daquela ocorrência para sempre.

**Aviso bloqueado não rearma, e a janela de recuperação é de 6h** (`Delivery.BLOCKED -> return` em
`:554`, `JANELA_ENTREGA_PENDENTE` em `:789`): com o canal desligado, o remédio das 08:00 não toca;
se ela abrir o app até as 14:00 ele toca atrasado, depois disso nada mais é armado e a ocorrência
fica pendente o resto do dia sem a home dizer que o aviso não saiu — o desfecho "Não consegui
avisar" só aparece na virada do dia seguinte.

**Hipótese não confirmada (estado do aparelho)**: `NotificationHelper.reminderAlerts` (`:80-86`) só
olha `areNotificationsEnabled` e `channel.importance`. O canal é HIGH e sem `setSound` (herda o som
padrão do aparelho), então um celular com o som padrão de notificação em "Nenhum" deixa o canal
HIGH e mudo — o app devolve `OK` e acha que está tudo bem. Confirmar exige mexer no som padrão de
um aparelho e observar; **não** é defeito de código comprovado.

## Achados da revisão independente do #48 (06/10, depois do merge)

A revisão do PR #48 (já mergeado como `2d4cb74`) confirmou os dois fixes P1 e as âncoras, e achou
mais três coisas — as duas primeiras viraram frente nova:

1. **A tela "Escrever tarefa" não passa pelo classificador.** `FalaAgendaRoot.kt:489` chama
   `homeVm.speech.understand(text)` direto, enquanto a home chama `understandSpeech` (que classifica
   antes). Escrever `cancela o médico` naquela tela cria a tarefa "Cancela o médico" — o mesmo
   defeito do #48, pela outra porta. É também a rota do ditado pelo teclado do sistema.
2. **O alvo casa por QUALQUER palavra.** `SpeechTargetMatcher.kt:67-69` casa se qualquer palavra do
   alvo bater com qualquer palavra do título. Com **uma única** tarefa na agenda, "cancela o remédio
   do cachorro" apaga "Passear com o cachorro" (casou pelo "cachorro") — e a proteção do `Ambiguous`
   some justamente quando só há uma tarefa. Os 19 testes da matcher só cobrem alvos de uma palavra.
3. **`cancela o.` deixa o alvo como `"o"`** (`SpeechIntent.kt:283`, o `\s+` do regex exige algo
   depois do artigo). Não causa dano: o piso de 3 letras da matcher devolve `None` e a resposta é
   "Não achei nenhuma tarefa com esse nome." Registrado, sem PR.
4. **A lista `jaFiz` captura afirmações de rotina legítimas** ("já comprei o presente", "já li o
   livro", "já usei o cupom") → `Complete` com alvo que não casa → "Não achei". O desfecho é seguro
   (não cria tarefa e não apaga nada), mas é uma captura legítima que deixa de ser registrada.
   Severidade baixa; decisão de produto.

## Achado da revisão independente do #49 (06/10, depois do merge)

A revisão do #49 (mergeado como `5ede588`) **reprovou** o PR com um defeito real que ficou no
`main`, e o executor da correção está com ele agora:

**A tela promete a próxima data da regra mesmo quando ela tem tombstone.**
`occurrencesForChoice` (`TaskRepository.kt:668`) devolve `armed = null` quando `plan.date` está em
`skippedDates` — nenhum alarme é armado e a próxima ocorrência viva é a data seguinte. Mas
`ChoiceSchedule.plan` (`:819-848`) não recebe as datas excluídas, então os dois consumidores de tela
(`AgendaFormat.promiseOfChoice` com `editing = true`, `:80-86`, e `HomeViewModel.announceOfEdit`,
`:555-565`) prometem `plan.date` assim mesmo.

**Cenário**: série "Remédio" todo dia às 08:00; hoje 20/08, agora 20:00. Ela exclui a ocorrência de
21/08 (grava o tombstone) e edita o cartão de hoje para as 18:00. A tela diz "Vai avisar sexta, 21
de agosto às 18:00"; salvar não arma alarme nenhum (tombstone); a próxima viva é 22/08. É o defeito
de origem do app — a tela prometendo um aviso que não acontece — pelo caminho que o #49 criou.

**O comentário do invariante é falso e perigoso.** `TaskRepository.kt:724-726` diz "nenhum caminho
arma alarme no passado", mas `rescheduleAll` (`:609-616`) arma instante passado **de propósito**
para entrega pendente (coberto por `avisoBloqueadoTocaAtrasadoDentroDaJanela`). O invariante real é
mais estreito: "nenhum caminho **materializa/cria** ocorrência nova já vencida com alarme". Sem a
correção, o próximo leitor "conserta" o sweep e mata o aviso atrasado, que é funcionalidade real.

### O fix (PR #57, `da81de1`)

A raiz era a peça compartilhada **não conhecer os tombstones**: `ChoiceSchedule.plan` devolvia a data
excluída enquanto o repositório a descartava. O conserto é estrutural, não um guard a mais —
`ChoiceSchedule.plan` passa a receber `skippedDates` e avança pela **mesma regra**
(`RecurrenceEngine.nextAfter`) até a próxima data **viva**, vários tombstones seguidos. O repositório
deixa de ter a guarda duplicada e passa a consumir a peça; a tela (`promiseOfChoice`), o
`ConfirmDraftScreen`, o `announceOfEdit` e os dois call sites do `FalaAgendaRoot` levam as datas
excluídas. Sem tombstone, o laço não dá uma volta e o comportamento é idêntico ao de antes. O
comentário do invariante foi corrigido junto.

Vermelho-antes comportamental (não de compilação): a promessa dizia 30/09 enquanto o repositório
armava 01/10. Duas mutações provadas (remover o laço de avanço → 3 testes caem; remover a passagem na
tela → 1 cai), sha256 restaurados idênticos. O executor também **testou o merge com o `main`**
(`a56012e`): merge automático limpo, suíte merged com **768 testes, 0 falhas**.

**Elo mais fraco, apontado pelo próprio executor:** nenhum teste unitário exercita o call site do
`FalaAgendaRoot` (exigiria o `NavHost` inteiro). A mutação prova que a passagem na tela é
load-bearing; a linha equivalente do Root não tem cobertura direta.

### Reconferido e correto — não mexer

Colisão de `PendingIntent` não existe (`AlarmIds.requestCode` usa lane distinto por ação sobre o
hash da ocorrência; varredura do dia com id próprio). `FLAG_IMMUTABLE` + `FLAG_UPDATE_CURRENT` em
todos. A armadilha do canal imutável **não** foi acionada: o canal nasceu HIGH com vibração desde a
primeira versão, nunca houve rebaixamento no código, e a leitura usa a importância gravada em vez
da constante (`:56-57`). `setSilent`/`setOnlyAlertOnce`/som próprio não existem — deliberado. O
widget não participa do caminho do som.

## O #52 e a lição das três reprovações (06/10)

O PR #52 (períodos do dia: `"depois do jantar às oito"`, `"pela manhã"`, hora sem o `"às"`, `"em ponto"`) foi **reprovado três vezes seguidas**, sempre pelo mesmo motivo: **o conserto de um lote de regressões cria regressões novas da mesma classe.**

A classe, nas três rodadas: data ou hora **errada, preenchida e `ambiguous = false`** — a caixa rápida confirma sem consultar a IA e o alarme toca no momento errado, sem aviso.

| rodada | o que a revisão achou |
|---|---|
| 1ª | 6 regressões (o PR consertava 5 alvos) |
| 2ª | 9 regressões F1–F9 |
| 3ª | as 9 fecharam, mas **6 novas** R1–R6, mais R7–R12 |

### A causa raiz, na terceira rodada

**É uma só: o D3** — a decisão de reconhecer hora sem o `"às"` (ex. `"duas da tarde"`). Todas as seis regressões novas vêm dele ou do guard que ele exige:

- **R1/R2** — `BARE_HOUR_PERIOD` casa dentro de `dd/mm` e do dia da recorrência; o guard `DIA_DO_MES_BEFORE` só cobre o prefixo literal `"dia "`, então `"consulta dia 25/12 da tarde"` sai **25/08 às 12:00** com `miss=[]` e confiança 0.85.
- **R3** — separar hora de dose pelo artigo não funciona: `"tomar duas **da** manhã"` (dose) tem o mesmo artigo que `"duas **da** tarde"` (hora).
- **R5** — o marco do jantar sobrescreve um período **explícito**: `"antes do jantar às seis da manhã"` → 18:00.
- **R6** — a segunda dose some sem sinal.

### E a premissa do D3 nunca foi medida

O D3 existe porque "o reconhecedor derruba o `às`". A medição real do parser (67 frases, relógio congelado) disse explicitamente: *"medi texto, não áudio. As frases 'sem o `às`' assumem que o reconhecedor derruba o `às` — é hipótese sobre o motor, não medida."*

Ou seja: **o D3 compensa uma hipótese não verificada sobre o Vosk, e é a fonte de todas as regressões que reprovaram o PR três vezes.** A regra de ouro do app é "na dúvida, `ambiguous` — nunca agendar errado em silêncio"; uma hipótese não medida não justifica cravar hora. O executor foi despachado com instrução de **reverter ou estreitar o D3**, e com o alerta explícito de não repetir o padrão: *"não conserte cada finding com um guard novo em cima — um guard a mais é o que produziu esta terceira reprovação."*

### A prova de mutação também tinha buracos (P2)

A revisão rodou 15 mutações na cópia em `/tmp`; **duas sobreviveram** (R8: desligar o ramo `1..6` do `ANTES_DO_JANTAR` não derruba teste nenhum; R9: remover `jantar` do `PERIOD_PHRASE` também não). E **três testes novos não prendiam o que o nome promete** (R10): o `f7DuasDosesNaMesmaFraseNaoColamNuma` só afirma `ambiguous == true` e passa com a segunda dose já perdida; o `f2DoseContadaNaoViraHora` nunca testa `da manhã`/`da tarde` como dose — é por isso que o R3 passa despercebido.

É a quarta aparição do mesmo padrão nesta sessão: **teste que existe, passa, e não prende nada.**

### O desfecho do #52 (06/10, `a84538f4f9`)

O executor **reverteu o D3 inteiro** — removeu o bloco `if (found.isEmpty()) { BARE_HOUR_PERIOD… }` e as constantes órfãs (`BARE_HOUR_PERIOD`, `DIA_DO_MES_BEFORE`, `DOSE_PERIOD`, o ramo `ANTES_DO_JANTAR`). Não achou estreitamento que preservasse o valor do D3 sem os R1–R6: o artigo ("da"/"de") não distingue hora de dose, então toda variante cai nos mesmos casos. R1/R2/R3/R6 sumiram na raiz; R4/R5/R7/R8/R9/R10 ganharam fix próprio.

Resultado medido: `"consulta dia 25/12 da tarde"` → 2026-12-25, hora nula, `ambiguous=true`; `"todo dia 5 da tarde"` → hora nula, ambíguo; `"tomar duas da manhã"` → hora nula, ambíguo, título `"Tomar duas"`; `"antes do jantar às seis da manhã"` → 06:00 (o período explícito vence o marco do jantar). Suíte: **domain 266 / app 528, 0 falhas**.

Dois sobreviventes de mutação (o ramo `hadPeriod` da faixa e o `DOSE_PERIOD`) foram tratados como **código morto e removidos**, não como "teste a escrever": ambos só existiam por causa da premissa do D3.

R6 é escolha conservadora declarada: `looksLikeTwoTasks` zera a hora do rascunho **inteiro** quando detecta duas tarefas — o rascunho de duas tarefas perde a hora mesmo da primeira. "Na dúvida, ambíguo" é a regra do app; nenhum teste do `main` dependia disso.

## Achados da revisão independente do #55 (06/10)

O #55 (datas nomeadas e relativos) consertou os cinco alvos e o algoritmo de Páscoa está **correto**
(validado contra `dateutil.easter` para 1583–4100, zero divergências), mas a revisão **reprovou**: o
conserto criou regressões novas da mesma classe — data/hora errada, preenchida e `ambiguous = false`,
que a caixa rápida confirma sem consultar a IA — em frases que o `main` tratava certo.

| # | frase | o #55 fazia | dano |
|---|---|---|---|
| F1 | "toda semana na terça limpar a casa às 8h" | ancorava em **hoje** (quinta) | a série inteira no dia errado |
| F1 | "toda semana na terça natação às 18h" | hoje às 18h | alarme no mesmo dia |
| F2 | "daqui a duas semanas dentista às 9h" | 03/09 às **17:43** | a hora em que ela falou, não a pedida |
| F3 | Dia das Mães/Pais | corte de ano por dia-do-mês | ano errado na virada; um caso no **passado** |
| F4 | "voltar para minha terra natal às 10h" | 25/12 | substantivo comum lido como festa |
| F4 | "limpar as cinzas da churrasqueira às 10h" | 10/02/2027 | idem |
| F5 | "no fim do mês que vem" | mês **atual** | o "que vem" sumia |
| F6 | "todo dia 25 do mês que vem" | virava DIÁRIO e perdia o 25 | recorrência trocada |
| F7 | "sexta santa" (sem "feira") | a próxima sexta | **o defeito D1 que o PR existia para matar** |

F8–F12 (P2/P3): "toda semana que vem" engolida; "em duas semanas" pela metade com hora fabricada;
nota do intervalo contradiz a hora declarada; `DateHit.note` é código morto; e o teste
`todaSemanaEhSerieSemanal` **nunca afirmava o dia da semana** — é exatamente por isso que o F1 passou
com 753 testes verdes.

A lição que se repete nesta sessão, agora com nome: **a suíte verde não cobre o caso que o teste não
afirma.** O teste existia, passava, e não prendia nada. Executor de correção despachado com os doze.

## Achado da revisão independente do #51 (06/10)

O #51 (o adiamento toca no horário pedido, mesmo depois da meia-noite) foi **aprovado com ressalvas**.
O fix faz o que promete e a escada continua pausando no silêncio.

**F1 — adiamento entregue com horas de atraso agora toca de madrugada.** Cenário fechado: ela toca
"Adiar 30 min" às 23:45 (alvo 00:15), desliga o celular, liga às 03:00. O `valeRearmar` aceita
(03:00 < 00:15 + 6h, `JANELA_ENTREGA_PENDENTE`), o alarme vai ao `AlarmManager` com instante no
passado e dispara na hora; no `fire` o portão de silêncio é pulado porque `snoozedUntil != null`, e o
aviso sai às **03:00**. Antes do PR esse disparo era empurrado para as 08:00. É a regra pretendida
aplicada a um atraso de 3h, e o pior caso é acordá-la de madrugada — o oposto do que o horário de
silêncio existe para fazer. O autor descartou de propósito a alternativa que fecharia isso (comparar
o relógio com `snoozedUntil`), e a razão é boa: a entrega atrasada cairia fora da tolerância.
**Trade-off, não regressão acidental** — decisão do Ruan.

**F2 — corrigido na hora.** O `rescheduleAsync` (`Receivers.kt:303`) guardava só o `as?` do
aplicativo, enquanto os dois receivers da notificação já checam `appScope.isActive`. Com o escopo
morto, o `launch` devolve um job que nunca começa e o `finally` que encerra o `pending` não roda — o
receiver fica vivo até o sistema matar o processo. Inalcançável hoje (nada cancela o `appScope`),
mas era a mesma guarda faltando no terceiro de três lugares que fazem a mesma coisa. Aplicado,
suíte do app 508/0.

## Caça a lacunas de fala (06/10, varredura read-only no parser)

Duas varreduras independentes (fala e conversa) trouxeram itens novos. Os confirmados por leitura de
código, ainda **sem PR**:

### Fala — o parser erra ou trava

| frase | hoje | dano | prova |
|---|---|---|---|
| "cancela o remédio do cachorro" (só existe "Passear com o cachorro") | casa por **qualquer** palavra e apaga a tarefa errada | **destrutivo e silencioso** — a proteção de ambíguo some justamente quando há uma tarefa só | `SpeechTargetMatcher.kt:67` |
| "quarta-feira de cinzas" / "domingo de páscoa" | próxima quarta/domingo comuns, `ambiguous = false` | agenda no dia errado em silêncio | `LocalTaskParser.kt:838` |
| "no outro sábado" | o sábado **desta** semana, e "outro" sobra no título | data errada (intenção de "outro" é interpretativa — confiança média) | `:591-610`, `WEEKDAY_NEXT_WEEK:827` |
| "remédio às 5", "missa às 6", "uma e vinte" | hora 1–6 sem período → `ambiguous = true` | com a IA desligada, **toda** hora nessa faixa vira beco sem saída com palpite 12h errado — o caso mais frequente do dia dela | `:429` |
| "duas da tarde", "cinco da noite" (sem "às") | sem hora nenhuma | beco sem saída | `CLOCK_WORD:773` exige "às" |
| "mês que vem", "daqui a duas semanas", "toda semana" | sem data / sem recorrência | beco sem saída | `extractRelative:455`, `WEEK_PHRASE:882` |
| "à tardinha", "de manhãzinha" | não reconhecido (assimetria: "noitinha" **é** tratado) | beco sem saída | `:627`, `:631` |
| "café da manhã na padaria", "consulta da tarde" | o período é roubado como hora → título mutilado **e** ambíguo | beco sem saída com o nome da tarefa cortado | `:623-634` |
| "amanhã pela manhã às oito" / "amanhã meio da tarde às quatro" | título vira **"Pela"** / **"Meio"** | a tarefa se chama "Pela" | `:631` (exige `a\|da\|de\|na`, "pela" fica de fora) |

**Os três de maior valor** (do caçador): o matcher por palavra solta (única falha destrutiva), as datas
de festa por dia da semana (missa/culto no dia errado, sem aviso), e as horas 1–6 (o caso mais
frequente virando beco sem saída).

### Conversa — o app responde mal

| situação | resposta de hoje | por que trava | prova |
|---|---|---|---|
| duas tarefas numa frase | `"Parece haver mais de uma tarefa na mesma frase. Vamos separar?"` | **faz uma pergunta que não aceita resposta** — não há botão "separar" nem caminho para falar as duas frases. É a conversa que mais acontece | `LocalTaskParser.kt:96` → `ConfirmDraftScreen.kt:168` |
| ela quer corrigir **falando** ("não, é amanhã") | `"Ainda não sei mudar uma tarefa falando. Toque na tarefa na lista para editar."` | a caixa rápida não tem microfone; falar a correção não corrige e ainda pode criar a tarefa "Muda pra" | `HomeViewModel.kt:825`, `QuickConfirmDialog.kt:102-106` |
| remédio **de rotina** cujo aviso não tocou | seção "Não consegui avisar", e só "Concluir"/"Excluir"/"Encerrar série" | o botão "Fazer hoje" só existe para tarefa que **não** repete — a dose do dia se perde sem saída | `ConfirmDraftScreen.kt:404` (`!isRecurring`) |
| "o que tenho hoje?" | tudo numa linha, em snackbar que some em ~4s e não volta | não há como rever a resposta; com 3+ tarefas a linha fica longa demais | `HomeViewModel.kt:773-776` |
| microfone não pegou | três frases para o mesmo evento, e uma é jargão ("reconhecimento do aparelho") | ruído; a primeira tem aspas aninhadas | `VoiceCaptureController.kt:190,195`, `HomeScreen.kt:971,1003` |
| fala ambígua | `"Alguma parte ficou em dúvida…"` **e** `"O horário ficou ambíguo."` | "ambíguo"/"recorrência" são palavras que ela não usa, e as duas camadas dizem a mesma coisa | `ConfirmDraftScreen.kt:162-168` |

**Jargão que sobrou** (o #47 limpou o do *alarme*; o do *parser* e do *config* ficaram): "ambíguo",
"recorrência", "campos ausentes", "reconhecimento do aparelho", "matar alarmes", número de versão.

**Inconsistência de vocabulário confirmada:** a home chama a fala de **"recado"**
(`HomeScreen.kt:559,687,971,1003`), mas os botões dizem **"tarefa"** ("Falar uma tarefa", "Escrever
tarefa"). Dois nomes para a mesma coisa.

**Achado estrutural (confiança média):** o parser local **nunca devolve "não entendi"** para uma frase
não vazia — `extractTitle` (`:712-732`) sempre reconstrói um título com o que sobrou. Com a IA
desligada, o único "não entendi" literal (`SpeechSession.kt:15`) nunca roda. Toda frase vira um
rascunho com cara de certeza.

## Medição real do parser (06/10, 67 frases contra o `main` `a56012e`)

As duas varreduras acima foram feitas **por leitura de código** e declararam isso. Um harness que
executa `LocalTaskParser.parse(...)` de verdade (relógio congelado numa quinta 20/08/2026 15:00,
America/Sao_Paulo) mediu 67 frases. O resultado **corrige** as varreduras em pontos importantes:

**Contagem:** 8 ERRADO-SILENCIOSO · 50 BECO · 7 DISCUTÍVEL (título mutilado, mas data/hora certas e
`ambiguous = false` — passa pela caixa rápida com o nome errado) · 2 OK.

**O `main` é mais seguro que o #55 nos pontos que o #55 consertou.** O #55 lia "voltar para minha
terra natal às 10h" como 25/12 e "limpar as cinzas da churrasqueira" como 10/02/2027; no `main` as
duas dão data nula (beco) com a hora certa. O #55 trocou um beco por uma data errada silenciosa.

**Só o feriado que carrega o dia da semana é silencioso.** "quarta-feira de cinzas" → próxima quarta,
"sexta-feira santa" → próxima sexta, "domingo de páscoa" → próximo domingo, "domingo de ramos",
"quarta-feira santa": **data cravada, `ambiguous = false`, rascunho completo** — a caixa rápida
confirma e o alarme toca no dia errado. Já "no natal", "dia das mães", "finados", "corpus christi"
dão data **nula** (beco). A varredura tratou os dois grupos como um só; a execução os separa.

**"sexta-feira santa" e "sexta santa" dão o MESMO resultado no `main`** (ambos → próxima sexta,
título "Santa missa"). O F7 do #55 previa que o `main` acertava "sexta santa" e o branch quebrava —
a medição mostra que o `main` **já tem** o defeito: "Santa" é lido como nome próprio.

### Achados novos que nenhuma varredura tinha catalogado

| frase | resultado real | dano |
|---|---|---|
| "ir na feira sábado às 8h" | título **"Ir"** | `stripWeekDays` remove `\bfeiras?\b` para limpar o "-feira" de "sexta-feira" e **come o substantivo**: o nome da tarefa some |
| "comprar na feira sexta às 8h" | título **"Comprar"** | idem — data certa, nome mutilado, `ambiguous = false` |
| "meio-dia e meia" (sozinho) | título **vazio**, `missing = {TITLE, DATE}` | a frase inteira é consumida; a hora (12:30) está certa, o rascunho sai sem nome |
| "três em ponto" | título **"Três ponto"**, hora nula | "em ponto" não é tratado e vaza para o título |
| "oito e meia em ponto" | título **"Ponto"** | idem |

O achado do "feira" é o de maior valor do lote: é uma palavra que ela fala o tempo todo ("ir na
feira", "comprar na feira") e o app apaga o nome da tarefa em silêncio.

### O que a medição confirma das varreduras

As horas 1–6 sem período ("remédio às 5", "missa às 6", "às 3", "uma e vinte") viram `ambiguous = true`
com o palpite 05:00/06:00/03:00 exibido — com a IA desligada, é beco sem saída, e é o caso mais
frequente do dia dela. E o período sem o "às" ("duas da tarde", "cinco da noite") zera a hora e
mutila o título ("Duas", "Cinco").

**Não medido:** a camada de comando (`SpeechIntent`), a escalada para a IA (nunca dispara sem as
chaves), o que o Vosk de fato devolve (mediu-se texto, não áudio), e a caixa rápida (o veredito
"passa pela caixa rápida" vem do predicado `isComplete && !ambiguous`, não de um toque real).

## Decisão de produto aberta

**`conclui` nu saiu dos gatilhos de comando** (PR #48). No presente, "conclui" é idêntico ao
imperativo ("conclui a faculdade em dezembro") e não dá para distinguir pela forma — então,
aplicando a regra "na dúvida, `CAPTURA`", ele virou captura. Consequência: **"conclui a
consulta médica" agora cria uma tarefa com esse nome** em vez de concluir a consulta.

O trade-off, para o Ruan decidir:

- **Manter como está** (captura): ela cria uma tarefa a mais e conclui pelo toque. Nada é
  apagado nem marcado errado.
- **Devolver `conclui` ao comando**: "conclui a consulta médica" funciona, ao custo de engolir
  "Conclui a faculdade em dezembro" — que vira conclusão de uma tarefa em vez de uma tarefa
  nova. Engolir é o dano que o PR existe para evitar.

Recomendação: manter como está. "Já <verbo>" ("já tomei", "já paguei") e "marca como feito"
cobrem o caso de uso sem o risco.

**O adiamento entregue com horas de atraso toca de madrugada** (revisão do #51, F1). O PR #51 fez o
"Adiar 30 min" das 23:45 tocar às 00:15 mesmo dentro do silêncio, que é o que ela pediu. O efeito
colateral: se o celular ficou desligado e só voltou às 03:00, o alarme é rearmado com instante no
passado e toca **na hora** — antes do PR era empurrado para as 08:00.

- **Manter como está**: o adiamento dela é honrado ao pé da letra, inclusive de madrugada.
- **Comparar o relógio com `snoozedUntil` na entrega atrasada**: o atraso de 3h cairia fora da
  tolerância e o aviso das 08:00 voltaria — ao custo de um adiamento legítimo (minutos de atraso)
  também ser empurrado.

Recomendação: manter. O cenário exige o celular desligado por horas entre o adiamento e a entrega;
empurrar de volta reintroduz o defeito que o PR existe para consertar, que é mais provável.

## Falsos positivos que qualquer conserto precisa respeitar

- "**Tomar** remédio às 8" é captura, não comando — o verbo no início não faz dela
  um comando.
- "amanhã de manhã" e "depois do almoço" já marcam `ambiguous = true` **de
  propósito** e escalam (README:190).
- "de duas em duas horas" / "a cada duas horas" são **recusados** de propósito
  (`INTERVAL`, `:276-284`): é intervalo, não horário do dia.

### O que já está correto — não mexer

"amanhã de manhã" / "depois do almoço" (escala); "de duas em duas horas" (recusa
certa); "meio-dia e meia" → 12:30 (`:296-300`); "toda terça e quinta" → WEEKLY com
os dois dias (`:226-241`); "às 3 da tarde" → 15h e "às 8 de noite" → 20h
(`CLOCK_BARE` + `applyPeriodHour`); "de 8 em 8 horas"; "todo dia 15 do mês";
"quinta que vem"; "daqui a duas horas e meia".

---

## Parte 2 — áudio e alarme

### 1. [GRAVE] Editar tarefa que repete para horário de hoje já passado arma alarme no passado

`app/.../data/repo/TaskRepository.kt:413-443` (`editOccurrence`)

A ocorrência é rematerializada na data escolhida literalmente
(`OccurrenceLifecycle.materialize(updatedSeries, date, now)`, linha 413); o guard
`DraftSchedule.bornWithoutReminder(...)` (linha 420) só age quando `kind == NONE` —
**não cobre regra recorrente** — e `scheduler.schedule(refreshed, updatedSeries,
first = true)` (linha 443) recebe `nextReminderAt` no passado. O próprio código
reconhece o buraco como "EM ABERTO" (linhas 436-442).

**Cenário:** são 20:00, ela abre o remédio "todo dia às 08:00" e corrige o horário
para 18:00 de hoje → alarme no instante vencido → **dispara imediatamente** e segue
a escada. `setAlarmClock` com instante passado dispara na hora.

### 2. [GRAVE] O mesmo furo no "desfazer Excluir" de tarefa recorrente

`TaskRepository.kt:308-326` (`restore`)

O guard de instante vencido só marca `MISSED` quando `!series.recurrence.isRecurring`
(linha 310); sendo recorrente, cai direto em `scheduler.schedule(fresh, series,
first = true)` (linha 326) com `scheduledAt` possivelmente no passado → disparo
imediato.

**O conserto do #36 cobre criação (`saveDraft`, linhas 138-147) e o parser, mas não
cobre `editOccurrence` nem `restore`.**

### 3. [MÉDIO] A dose das 22:00 com o celular desligado a noite toda se perde

`TaskRepository.kt:715` (`JANELA_ENTREGA_PENDENTE = Duration.ofHours(6)`), aplicada
em `OccurrenceLifecycle.entregaPendente` (`domain/.../recurrence/OccurrenceLifecycle.kt:236-246`)
e em `valeRearmar` (`:215-222`).

**Cenário:** ocorrência às 22:00, `lastReminderAt == null`; celular liga às 09:00 →
`marcado + 6h = 04:00`; `now (09:00)` não é anterior a 04:00 → `entregaPendente =
false` → `rescheduleAll` **não** rearma (`TaskRepository.kt:628`) e `applyLifecycle`
marca `MISSED` (`:640-657`). A dose não toca; o app a mostra em "Não consegui
avisar" (`ui/home/MissedSections.kt:25-26`, porque `lastReminderAt == null`).

É o terminador declarado, mas para um remédio a dose do dia se perde. Decisão de
produto pendente: qual é a janela honesta para um remédio.

### 4. [MÉDIO] Bateria/otimização é detectada, mas não há cartão na home — **RESOLVIDO no #50**

A detecção existe (`platform/DeviceIntents.kt:91-93`,
`isIgnoringBatteryOptimizations`), é lida em `ui/home/HomeScreen.kt:155,164`, e vira
o rótulo "Não matar alarmes" na gaveta (`ui/home/HomeDrawer.kt:76`). Não existe
equivalente ao `reminderAlertCard` para bateria: se ela nunca abrir a gaveta, o app
nunca avisa que a economia de bateria está matando os alarmes. O guia do fabricante
(`HomeScreen.kt:723-801`, `ManufacturerHint.kt`, `ManufacturerGuide.kt`) é bom, mas
depende de ela chegar até lá.

**Fechado em `a56012e` (#50).** O `alarmHealthCard` (`ui/home/AlarmHealthCard.kt`) é o
cartão que faltava: aparece na home com bateria restrita **ou** sem o acesso a alarmes
exatos, com precedência para a bateria (é o que pode matar o alarme de vez, não só
atrasar). O texto e o botão vivem fora do Composable para o teste poder lê-los, e o
botão só oferece a tela de bateria quando há o que pedir ali
(`DeviceIntents.batterySettingsIntentOrNull`). O cartão lê o aparelho a cada resume, e
o teste de call-site prende as **duas** fontes — a revisora tinha provado que trocar
`canScheduleExact` por constante deixava a suíte inteira verde.

Fica em aberto: a gaveta agora diz "Fazer os avisos tocarem sempre" (#56) e o
`AlertDialog` que ela abre ainda diz "Não matar alarmes" (`HomeScreen.kt:796`) — a
mesma superfície com dois vocabulários, a fechar num PR seguinte.

### 5. [BAIXO] "Alarme inexato" é transitório; não há detecção persistente — **RESOLVIDO no #50**

O cartão/snackbar (`ui/home/HomeScreen.kt:612-629`) só é alimentado no **salvar**
(`FalaAgendaRoot.kt:314,515` → `usedInexactAlarm`) e some no toque
(`HomeScreen.kt:626`). Se a permissão for revogada depois, ou ela dispensar o
cartão, não há releitura persistente como há para notificações. A permissão em si é
detectada em `ReminderScheduler.canScheduleExact()` (`reminders/ReminderScheduler.kt:22-28`),
que cai em `setInexactCompat` (`setAndAllowWhileIdle`, `:143-149`) — o alarme
continua tocando, só sem exatidão.

**Fechado em `a56012e` (#50).** O `refreshAlarmHealth` (`HomeViewModel`) relê a
permissão no `ON_RESUME` da home, e o cartão persistente substitui o transitório. O
`_inexactWarning` também é baixado quando a exatidão volta — sem isso o aviso
reaparecia a cada retorno à home, inclusive depois de ela conceder a permissão, que
foi o bug que o próprio PR introduziu e a revisão pegou.

Sobra (não é regressão, é o mesmo teto de antes): se a permissão for revogada
**enquanto o app está aberto**, nada reagenda os alarmes já armados — o cartão passa
a avisar no resume, mas o rearme só acontece no próximo start/boot/virada do dia.

### 6. [BAIXO] Divergência widget × home é real e já documentada

O widget usa a **hora** (`scheduledAt.isBefore(now)`) e a home usa o **dia**
(`widget/AgendaWidgetProvider.kt:116-123`). **Cenário** (do próprio comentário):
15:00, remédio das 08:00 pendente e nada à frente → widget diz "Atrasada — Hoje ·
08:00", home diz "há 7 h". O widget mostra só o **próximo** pendente (`:94-104`), a
home mostra as seções completas — não é estado errado, é recorte diferente.

### O que já está correto — não mexer

1. **Não tocar som próprio é decisão deliberada e correta.**
   `NotificationHelper.kt:40-51` (canal `IMPORTANCE_HIGH` + `enableVibration`);
   `:53-68` explica por que não fura Não Perturbe. **Não é o defeito** — não
   "consertar".
2. **Rearme pós-reboot, troca de hora e virada do dia estão completos.**
   `BootCompletedReceiver` (BOOT_COMPLETED + MY_PACKAGE_REPLACED) e
   `TimeChangeReceiver` (TIMEZONE_CHANGED + TIME_SET +
   SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) em `reminders/Receivers.kt:250-307`,
   registrados no `AndroidManifest.xml:74-98`. A virada do dia é o
   `DailySweepReceiver` (00:05, `OccurrenceLifecycle.kt:256-262`) porque
   `DATE_CHANGED` não chega em receiver de manifesto. A escada está correta: passo 0
   = horário, depois +15/+30/+60 min, teto de 32 passos, sem atravessar a meia-noite
   exceto o adiamento do silêncio (`ReminderPolicy.kt:39-88`, teste em
   `ReminderLadderTest.kt`); silêncio pausa só passo ≥ 1 (`TaskRepository.kt:538`).
3. **Detecção e aviso de notificação bloqueada está correta.**
   `NotificationHelper.reminderAlerts` (`:80-96`, separa OFF de QUIET e de canal
   desligado) → cartão na home com ação (`HomeScreen.kt:568-592`,
   `ReminderAlertCard.kt`), mais o pedido de permissão no onboarding
   (`OnboardingScreen.kt:140-149`) e o cartão de alarme inexato
   (`HomeScreen.kt:612-629`).

---

## Rodada de 06/10 — o que já está em `main`

Mergeados por squash nesta sessão, cada um com revisão independente antes do merge:

| PR | merge | o que fecha |
|---|---|---|
| #48 | `2d4cb74` | a fala distingue comando de tarefa |
| #49 | `5ede588` | editar/desfazer recorrente para hoje já passado não arma no passado |
| #51 | `554675a` | o adiamento dela toca no horário pedido, mesmo depois da meia-noite |
| #53 | `27dd4c0` | escrever tarefa também classifica comando |
| #50 | `a56012e` | a home avisa quando o aparelho pode não tocar o alarme |
| #54 | `51f7c67` | alvo de várias palavras exige maioria, não uma palavra solta |
| #56 | `8461823` | a conversa para de falar em jargão e para de dar ordem |

Suíte em `main` depois de tudo: **domain 236 / app 521, 0 falhas**.

### O fio que a revisão do #56 pegou

O item da gaveta dizia "Fazer os avisos tocarem sempre" e o diálogo que ele **abre** ainda dizia "Não matar alarmes" — a mesma superfície com dois vocabulários, o de dentro pedindo que ela "matasse". O fix é de texto, mas o teste que devia prendê-lo **passava verde com o jargão de volta**: a gaveta é animada, o relógio da composição não anda sozinho no Robolectric, e o `performClick` caía numa gaveta fora da tela (x negativo) — o diálogo nem abria, e o `assertDoesNotExist("matar")` passava por vacuidade.

É a **quinta** aparição do padrão nesta sessão. A correção que funciona: `mainClock.autoAdvance = false` + `advanceTimeBy` para a gaveta abrir, `performSemanticsAction(OnClick)` no lugar do `performClick` (o hit-testing erra o nó recém-animado), e **asserção positiva** — o botão "Entendi" existe e o título aparece duas vezes (gaveta + diálogo). Prova por mutação: com o título antigo, o teste fica vermelho.

### Ainda aberto

- **#55** (datas nomeadas) — reprovado com regressão P0 nova: `"toda semana que vem … às 18h"` ancora **hoje** (o `nextWeek` não é propagado no early-return de `days.isEmpty()`); `"na sexta feira"` sem hífen corrompe o título; `"cinzas"` nu vira Quarta de Cinzas; `"Natal"` isolado zera o título; `"no Natal de 2027"` ignora o ano; `"próximo mês"` não coberto apesar de o PR declarar que cobre.
- **#57** (promessa × tombstone) — aprovado com ressalvas: `announceOfEdit` sem teste que o prenda; asserção exata onde devia ser `substring`.
- **#52** — revert do D3 pushed, em revisão independente.

## O que só o aparelho prova

1. **Entrega real sob Doze/OEM:** que o `setAlarmClock` (`ReminderScheduler.kt:46`)
   acorde a tela e toque com o aparelho bloqueado e o app em segundo plano; e que
   Xiaomi/Samsung/Huawei não matem o `ReminderAlarmReceiver`. O caminho de código
   está correto, mas o comportamento depende do fabricante — o ponto cego histórico
   do "o áudio nunca funciona".
2. **Som do canal e permissões no estado real:** confirmar que o canal
   `fala_agenda_reminders` está com importância HIGH e som (o app **não** toca som
   próprio de propósito — `NotificationHelper.kt:53-68` — então o som depende 100%
   do canal do sistema); que `POST_NOTIFICATIONS` está concedida; que
   `SCHEDULE_EXACT_ALARM` está ligada; e que a tela de autostart do fabricante
   (`ManufacturerHint.shortcutIntent`, `:31-60`) abre no modelo dela.

---

