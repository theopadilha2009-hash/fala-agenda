# Caçada — ERROS DE TRANSCRIÇÃO DO VOSK: o pipeline com a fala derrubada, abreviada ou errada (2026-10-19)

Vigésima quinta leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

A pergunta dela: **"o áudio grava mesmo com erros ou abreviações de fala?"** Ninguém nunca mediu
o elo Vosk→pipeline. As caçadas anteriores mediram o parser com fala *bem transcrita*; esta mediu
o que acontece quando a TRANSCRIÇÃO vem errada — palavra derrubada, abreviada, com fonema
trocado, caps e pontuação erráticos, travada de ASR, número híbrido.

**Resposta curta: SIM, ela grava — e, no que importa, grava SEGURO.** Em 41 formas distorcidas,
**nenhuma cravou valor inventado**. O pior que o pipeline fez com um erro de transcrição foi
perder informação (nota "Falta o horário", ambiguidade) ou poluir o título. A doutrina "nunca
cravar valor que ela não disse; dúvida escala" segurou no elo ASR: o "triz" que ela nunca disse
não virou hora; o "8 da noite" que perdeu o "às" não virou 08:00 — virou `amb=true`. Os dois
defeitos reais estão na PORTA de comando do classificador, não no parser.

**Base:** `origin/main` @ `012941b`. Worktree descartável (`~/WORKSPACES/fala-caca-vosk`).
**Produção intocada** — só sonda de teste (`ZZProbeVoskErrosTest`, apagada depois de medir).
41 formas (30 capturas + 11 comandos), `--rerun-tasks --max-workers=2`, contagem pelos XMLs.
Clock fixo: 19/10/2026 10:00, America/Sao_Paulo. Pipeline medido: `SpeechIntentClassifier.classify`
+ `LocalTaskParser.parse` — exatamente os dois elos que `HomeViewModel.understandSpeech` chama.

---

## P1 — A conjugação que o ASR corta desliga o comando de "já tomei"

```
"já tomei o remédio"      → intent=Complete  ✓ (controle)
"ja tomei remedio"        → intent=Complete  ✓ (sem artigo, continua)
"ja tomei o remedio hoj"  → intent=Complete  ✓ (typo no fim, continua)
"ja tome o remedio hoj"   → intent=CAPTURE   ✗ ("tomei"→"tome" cortou o -i)
```

A única variável é a conjugação. O gatilho de Complete é a lista de passado
`ja\s+(tomei|fiz|comi|bebi|…)` (`SpeechIntent.kt:440`) — "tome", a forma que o Vosk devolve quando
corta o **-i** final, não casa. Desfecho: em vez de registrar a tomada, o app cria o **rascunho
de tarefa "Ja tome remedio hoj"** (date/time null, `qc=false`) — a ação OPOSTA da pedida, sem
dado cravado. A dose não é marcada como tomada e nasce um recado que ninguém pediu.

## P1 — O infinitivo desliga o cancelar

```
"cancela o médico"          → intent=Cancel  ✓ (controle)
"cancela o medico dr joao"  → intent=Cancel  ✓ (qualificador no alvo, continua)
"cancelar o medico"         → intent=CAPTURE ✗ (imperativo→infinitivo)
```

O gatilho de Cancel é a lista de imperativo `\b(cancela|cancele|desmarca|desmarque)\b`
(`SpeechIntent.kt:472`) — "cancel**ar**" (a forma infinitiva que ASRs devolvem com frequência)
fica fora. Mesmo desfecho do anterior: pediu APAGAR, recebe o rascunho "Cancelar médico".

**Causa comum dos dois P1:** as portas de comando são listas fechadas de formas verbais exatas.
O classificador é conservador de propósito — ambiguidade cai em Capture — e essa escolha era
segura para o PARSE, mas aqui o custo da conservadoridade é executar o comando errado como
recado: a intenção visível (criar tarefa) contradiz a intenção falada (completar/apagar). A
leva da 11ª ("intencao-e-comandos") mediu o classificador com fala limpa; a forma conjugada
errada era o buraco do corpus.

**Forma de fix sugerida (não implementada):** aceitar as formas vizinhas de cada lista
(imperativo ∪ infinitivo: `cancela|cancele|cancelar|desmarca|desmarque|desmarcar`; e para o
"já tomei", a forma sem o -i final `tome|fiz|come|…` — ou casar o radical `\btomei?\b`), que é
exatamente a classe de erro que um ASR pt-BR produz. O risco é falso positivo (ela falando
"tome o remédio amanhã" querendo CRIAR) — o corpus precisa do par que discrimina antes de
abrir a lista, doutrina das caçadas anteriores.

## P1 — O "às" derrubado derruba a hora junto: o número dito vira título

```
"marcar dentista amanhã às 8"   → 08:00 · 20/10 · qc=true  ✓ (controle)
"marcar dentista amanhã 8"      → time=null · título "Dentista 8" · nota "Falta o horário"
"tomar remédio hoje 8"          → time=null · título "Tomar remédio 8" · nota "Falta o horário"
```

Desfecho **seguro** (nada cravado, a nota pede o horário), mas o 8 que ELA DISSE como hora não
é reconhecido sem o "às": a informação some do campo e o número vai poluir o título. Ela terá
de digitar a hora de novo — e o título "Dentista 8" sobrevive para o banco e o widget. Não é
invenção; é perda silenciosa de campo dito (a nota diz "falta", não diz "o 8 era a sua hora").

---

## A tabela medida (41 formas)

| Fala (transcrição hipotética) | Desfecho | amb | Veredito |
|---|---|---|---|
| `marcar dentista amanhã às 8 da manhã` | 08:00 · 20/10 · qc=true | false | controle ✓ |
| `tomar remédio hoje às 8 da noite` | 20:00 · 19/10 · qc=true | false | controle ✓ |
| `tomar remédio às tres da tarde` | 15:00 · sem data · nota | false | controle ✓ |
| `marcar dentista amanhã 8` (sem "às") | time=null · título "Dentista 8" | false | **P1** — hora dita vira título |
| `tomar remédio hoje 8` (sem "às") | time=null · título "Tomar remédio 8" | false | **P1** (idem) |
| `tomar remédio tres` (sem "às", extenso) | nada cravado · "…tres" no título | false | OK — seguro, degradação |
| `tomar remédio amanha 8 da manha` (sem "às") | time=null · nota de manhã | **true** | OK — período órfão escala ✓ |
| `tomar remédio amanha` (hora derrubada) | 20/10 · time=null · nota | false | OK |
| `tomar remédio hoje 8 da noite` (sem "às") | time=null · nota "à noite" | **true** | OK — NÃO vira 08:00 ✓ |
| `tomar remédio hoje 8 da manha` (sem "às") | time=null · nota | **true** | OK ✓ |
| `marcar dentista amanha tres da tarde` | time=null · título "Dentista tres" | **true** | OK seguro, título sujo |
| `tomar remédio am as 8` ("am" por amanhã) | 08:00 · **data perdida** · título "Tomar remédio am" | false | **P2** — data dita some |
| `marcar dentista hoj as 10` ("hoj") | 10:00 · **data perdida** · título "Dentista hoj" | false | **P2** (idem) |
| `tomar remédio amanhã às 9h` | 09:00 · 20/10 · qc=true | false | OK — "9h" colado ✓ |
| `tomar remédio amanhã às 9 h` | 09:00 · 20/10 · qc=true | false | OK — com espaço ✓ |
| `consulta com o dr joao amanhã às 8` | 08:00 · 20/10 · título "Consulta com dr joao" | false | OK — "dr" segue no título |
| `tomar remédio amanha as 8 da manha` (sem til) | 08:00 · 20/10 · qc=true | false | OK — fold normaliza ✓ |
| `tomar remédio às triz` (fonema) | nada cravado · título "Tomar remédio triz" | false | OK — não inventa ✓ |
| `tomar remédio às oito da noite` | 20:00 · sem data · nota | false | OK ✓ |
| `consulta com a dotora ana amanhã às 8` | 08:00 · 20/10 · qc=true | false | OK |
| `TOMAR REMEDIO AMANHA AS 8 DA MANHA` | 08:00 · 20/10 · título "TOMAR REMEDIO" | false | **P2** — título em caps |
| `tomar remédio, amanhã às 8.` | 08:00 · 20/10 · qc=true | false | OK — pontuação limpa ✓ |
| `tomar. remédio amanhã às 8` (ponto no meio) | 08:00 · 20/10 · qc=true | false | OK ✓ |
| `marcar   dentista   amanhã    às 8` | 08:00 · 20/10 · qc=true | false | OK ✓ |
| `tomar tomar remédio amanhã às 8` (travada) | 08:00 · 20/10 · título "Tomar tomar remédio" | false | **P2** — repetição no título |
| `marcar dentista as as 8 amanhã` | 08:00 · 20/10 · qc=true | false | OK — "as as" engolido ✓ |
| `tomar remédio amanhã 8h30` | 08:30 · 20/10 · qc=true | false | OK ✓ |
| `tomar remédio amanhã às 8 horas da manha` | 08:00 · 20/10 · qc=true | false | OK ✓ |
| `tomar remédio oito e meia da manha` | 08:30 · sem data · nota | false | OK ✓ |
| `tomar remédio amanhã 8h` | 08:00 · 20/10 · qc=true | false | OK ✓ |
| `cancela o médico` | intent=Cancel | — | controle ✓ |
| `cancela o medico dr joao` | intent=Cancel | — | OK — alvo qualificado ✓ |
| `já tomei o remédio` | intent=Complete | — | controle ✓ |
| `conclui o remédio` | intent=**Capture** | — | pré-existente FORA do eixo ASR — "conclui" nunca foi gatilho (ver "Não confirmado") |
| `ja tomei remedio` (sem artigo) | intent=Complete | — | OK ✓ |
| `ja tome o remedio hoj` ("tomei"→"tome") | intent=**Capture** → rascunho "Ja tome remedio hoj" | — | **P1** — comando vira recado |
| `ja tomei o remedio hoj` (typo só no fim) | intent=Complete | — | OK — isola a conjugação ✓ |
| `conclui remedio` / `conclui o remedio hoj` | intent=Capture | — | idem "conclui" |
| `cancela o medico amanha as 8` | intent=Cancel (hora ignorada pelo fluxo) | — | fronteira de produto, ver "Não confirmado" |
| `cancelar o medico` (infinitivo) | intent=**Capture** → rascunho "Cancelar médico" | — | **P1** — comando vira recado |

**Leitura da tabela:** nos 30 casos de captura, o desfecho cravado — quando houve — foi sempre
um valor que ela disse. As duas violações reais estão nos comandos: a porta fecha e a intenção
oposta é executada.

---

## Doutrina observada: o parser já tem a defesa certa para ASR; o classificador não tem

O parser perde contra erro de transcrição **por design**: "triz" não casa nenhuma regex de hora,
e um período órfão sem relógio na frente escala (`amb=true`) em vez de chutar. O que a leva
acrescenta: essa defesa sobrevive à distorção *sem ajuste nenhum* — a doutrina "dúvida escala"
foi escrita para fala ambígua e funciona para fala mal transcrita. O classificador é o elo
fraco: listas fechadas de formas verbais exatas transformam a inflexão que o ASR erra
("tomei"→"tome", "cancela"→"cancelar") em troca de intenção — e aí a conservadoridade não
escala, EXECUTA O OPOSTO (cria o recado em vez de completar/apagar).

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

Produção intocada; a sonda (5 testes) foi apagada após a medição e o baseline re-rodado fresco.

| Run | O que | domain | Falhas | Veredito |
|---|---|---|---|---|
| Sonda (com produção atual) | 41 formas + doutrina | **630** | **2** | as ÚNICAS falhas são `comandoConjugadoErradoNaoViraRecado` e `infinitivoNaoDerrubaOCancelar` — os oráculos dos dois P1 |
| Baseline (sonda apagada) | produção intacta | **625** | **0** | limpo |

## Não confirmado

- **A voz REAL dela não foi medida.** Todas as distorções aqui são *tipicas* de ASR pt-BR
  coloquial (derrubada de artigo/preposição, corte de -i final, fonema trocado, travada), não os
  erros do Vosk dela com o vocabulário e o microfone dela. Um corpus com o log real das
  transcrições do app é a próxima leva natural.
- **O caminho remoto (HybridParser/IA) não foi medido** — só o parser local, que é o caminho
  offline e o que o merge usa como base. Como a IA corrige (ou piora) as transcrições erradas é
  outro elo.
- **`conclui o remédio` → Capture é pré-existente FORA do eixo ASR** (a fala limpa também cai
  em Capture — o verbo "conclui" nunca entrou nas listas do classificador). Está ligado às
  decisões de produto abertas sobre o comando `conclui`; não era o escopo desta leva.
- **`cancela o medico amanha as 8` mantém intent=Cancel** e a hora dita some do fluxo (o Cancel
  com alvo não usa data/hora). Se ela cancela "a consulta de amanhã às 8" falando o horário,
  o alvo casa pela série inteira — fronteira de produto, não defeito de ASR; merece caçada
  própria sobre alvo temporal no cancelamento.
- **O gatilho de fix das listas verbais** (aceitar infinitivo / forma sem -i) precisa do par
  discriminador antes de abrir — "tome o remédio" dito com intenção de CRIAR não pode virar
  Complete. O corpus dessa fronteira não foi construído aqui.
- **Pontuação no meio que quebra título não apareceu** no corpus ("tomar. remédio" saiu limpo);
  formas mais agressivas (vírgula trocando palavra, hífen de ASR "marcar-dentista") não foram
  medidas.
