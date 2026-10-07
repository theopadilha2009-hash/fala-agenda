# Caçada — a pergunta dela e a resposta do app (2026-10-07)

Quinta leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06-novos.md`, `cacada-fala-2026-10-07-correcao.md` e
`cacada-fala-2026-10-07-dose-e-duracao.md`. Esta mede um eixo que nenhuma das outras
tocou, e é o espelho das nove primeiras: **todas mediram o app recebendo fala; esta mede o
app respondendo.**

Medições em cópia descartável (`/tmp/caca-pergunta-resposta`), com o **classificador real**
(`SpeechIntentClassifier`) e a **tela real** (`HomeScreen` + Room, Robolectric sdk 34), e
um caso de controle.

## Manchete

**Das 11 perguntas dela, 4 são respondidas (só em texto), 6 viram tarefa e 1 vira comando
que mexe na agenda. Nenhuma é respondida em voz.**

O invariante — *"toda pergunta produz alguma resposta; nenhuma vira tarefa"* — é **violado
em 7 de 11**. E o defeito não está no handler: o handler do `Ask` (`HomeViewModel.kt:791`)
está **certo**, responde 4 de 4 em texto. O que falha é **antes** dele: o classificador só
reconhece 4 das 11 formas como `Ask` (`SpeechIntent.kt:166-188`).

## P0 — "já tomei o remédio?" é uma pergunta, e o app MARCA a dose

```
PERGUNTA|já tomei o remédio?|CLASSE|Complete(target=remedio)|DESFECHO|Complete/Cancel sem alvo: "Não achei nenhuma tarefa com esse nome."
PERGUNTA_MUTANTE|texto="Feito."|status=COMPLETED
```

Ela perguntou se já tomou e o app **conclui a dose** — "Feito." e a ocorrência vai para
`COMPLETED`. Se houver uma tarefa de remédio com nome compatível, a pergunta dela
**registra uma dose que ela não tomou**. É a mesma classe de risco do alvo descartado do
`Complete` (ver `cacada-fala-2026-10-07-correcao.md`), por outro caminho: aqui não há
correção nenhuma, só uma pergunta com a forma de um comando.

## P0 — as três formas de perguntar a hora do remédio criam tarefa com título-lixo

```
PERGUNTA|que horas é o meu remédio?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Meu")
PERGUNTA|quando é o remédio?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Quando")
PERGUNTA|que horas eu tomo o remédio?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Eu tomo")
```

A pergunta mais natural que ela vai fazer sobre o remédio — "que horas é o meu remédio?" —
vira uma **tarefa chamada "Meu"**. Não é só uma resposta que falta: é uma tarefa que ela
não pediu, com um nome que não quer dizer nada, ocupando a agenda.

## P0 — "que horas são?" e "que dia é hoje?" viram tarefa

```
PERGUNTA|que dia é hoje?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Dia")
PERGUNTA|que horas são?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "")
VOZ|pergunta_por_voz_que_vira_tarefa|texto="que horas são?"|classe=Capture|captura=true|rascunho=""
```

São as duas perguntas que **não dependem de agenda nenhuma** — o relógio do aparelho basta
— e são as que ela mais vai repetir. As duas viram tarefa; a segunda com **título vazio**.

## P1 — a resposta existe, mas só em texto: não há voz de resposta

```
TTS|instancia_construida_pelo_app=null
TELA|resposta_em_texto_visivel=true
VOZ|pergunta_por_voz_tem_resposta_em_texto=true
```

O app **não constrói nenhum `TextToSpeech` para responder** — a instância é `null`. A
resposta certa existe ("Não tem nada marcado para hoje.") e chega na tela, mas **só em
texto**, numa snackbar. Para uma idosa que fez a pergunta **por voz**, provavelmente sem o
celular na mão, a resposta em texto é a mesma experiência de silêncio que a queixa
*"o áudio nunca funciona"* descreve.

O que **funciona**: a pergunta por voz chega no mesmo lugar que a de texto
(`VOZ|pergunta_por_voz_tem_resposta_em_texto=true`) — o caminho de entrada não é o
problema.

## Oráculo (output colado)

```
PERGUNTA|que horas é o meu remédio?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Meu")
PERGUNTA|quando é o remédio?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Quando")
PERGUNTA|que horas eu tomo o remédio?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Eu tomo")
PERGUNTA|o que eu tenho hoje?|CLASSE|Ask(whenDay=TODAY)|DESFECHO|Ask respondido em texto: "Não tem nada marcado para hoje."
PERGUNTA|o que tem pra hoje?|CLASSE|Ask(whenDay=TODAY)|DESFECHO|Ask respondido em texto: "Não tem nada marcado para hoje."
PERGUNTA|tem alguma coisa amanhã?|CLASSE|Ask(whenDay=TOMORROW)|DESFECHO|Ask respondido em texto: "Não tem nada marcado para amanhã."
PERGUNTA|qual é a minha agenda?|CLASSE|Ask(whenDay=TODAY)|DESFECHO|Ask respondido em texto: "Não tem nada marcado para hoje."
PERGUNTA|já tomei o remédio?|CLASSE|Complete(target=remedio)|DESFECHO|Complete/Cancel sem alvo: "Não achei nenhuma tarefa com esse nome."
PERGUNTA|o que eu já fiz hoje?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Eu já fiz")
PERGUNTA|que dia é hoje?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Dia")
PERGUNTA|que horas são?|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "")
PERGUNTA|me lembra de tomar remédio amanhã às oito|CLASSE|Capture|DESFECHO|Capture (rascunho de tarefa: "Tomar remédio")
--- CONTAGEM ---
Ask respondido em texto = 4
Ask respondido em voz   = 0
Ask sem resposta        = 0
Capture (criou tarefa)  = 7
Unknown                 = 0
outro (Complete/Cancel) = 1
TOTAL = 12 (perguntas=11, controle=1)
```

Sondas complementares:

```
PERGUNTA_MUTANTE|texto="Feito."|status=COMPLETED          # "já tomei o remédio?" MARCA a dose
TTS|instancia_construida_pelo_app=null                    # não existe TTS na resposta
TELA|resposta_em_texto_visivel=true                       # resposta certa, só em snackbar
VOZ|pergunta_por_voz_tem_resposta_em_texto=true           # voz chega no mesmo lugar que texto
VOZ|pergunta_por_voz_que_vira_tarefa|texto="que horas são?"|classe=Capture|captura=true|rascunho=""
CONTROLE|draft=Tomar remédio|podeSalvar=true              # piso do oráculo
```

O teste do oráculo falha **de propósito** quando acha violação (`7 tests completed, 1
failed`).

## Medido e OK (não refazer)

- **O handler do `Ask` está correto.** As 4 perguntas classificadas como `Ask` são
  respondidas, com o texto certo, lendo a agenda de verdade (`HomeViewModel.kt:791`).
- **O caminho de entrada por voz não é o problema**: a pergunta falada chega ao mesmo
  ponto que a digitada e produz a mesma resposta em texto.
- **O caso de controle funciona**: "me lembra de tomar remédio amanhã às oito" cria o
  rascunho "Tomar remédio" com `podeSalvar=true` — o piso do oráculo está firme.

## Não confirmado

- **A contagem de toques até um alarme duplicado** a partir dos títulos-lixo (`"Meu"`,
  `"Quando"`, `"Eu tomo"`) — se caem em `canQuickConfirm=true`, ela teria um toque até
  criar a tarefa errada. **Não medido.**
- **O comportamento com a agenda cheia**: todas as medições rodaram com a agenda vazia, e
  por isso a resposta é sempre "Não tem nada marcado". A resposta do `Ask` com tarefas
  reais não foi exercitada.
- **`"já tomei o remédio?"` com uma tarefa de remédio existente** — o caso em que a dose é
  de fato marcada. Medido só o desfecho "sem alvo"; o dano real exige a agenda com o
  remédio cadastrado.
- **A IA real** não foi chamada; o eixo é do classificador local.

## PENDENTE (decisão de produto)

1. **Fazer o app responder em voz** — é a metade que falta do "áudio" e o que a queixa do
   dono pede. O PR #80 faz o **lembrete** falar; a **resposta à pergunta** não fala.
2. **`"já tomei o remédio?"` precisa de guarda de interrogação** antes de virar `Complete`.
   É risco clínico (dose registrada a partir de uma pergunta), e o fix é do classificador.
3. **`"que horas são?"` / `"que dia é hoje?"`** são respondíveis sem agenda — decidir se
   entram no `Ask` (parece óbvio, e hoje viram tarefa).
4. **Ampliar as formas de `Ask`** para as três variações de "que horas é o meu remédio" —
   hoje viram tarefa com título-lixo.
