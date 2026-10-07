# Caçada — O RECADO: o que o app devolve depois de entender (2026-10-07)

Décima segunda leva. As anteriores estão listadas em
`cacada-fala-2026-10-07-intencao-e-comandos.md`.

As quinze caçadas anteriores mediram a **entrada** (o parser, o classificador, a
recorrência, a data/hora, o áudio). Nenhuma mediu a **saída** — o que o app fala e
escreve de volta quando termina de processar. Isto é o laço de retroalimentação: se o app
diz a coisa errada, ela **acredita** e não confere. Um recado que confirma o que não foi
salvo é pior que um silêncio.

**Base:** `origin/main` @ `e0b721c`. Cópia descartável (`/tmp/caca-recado`), parser real
via jshell + `HomeViewModel`/Room em Robolectric. **~40 falas** do corpus de captura,
**11 estados de comando**, **8 estados de limite**, **7 rodadas de mutação**.

As cinco superfícies de recado: a promessa do diálogo, o botão de salvar, o anúncio
pós-salvar, a linha da lista, o TTS do alarme — mais a resposta falada dos comandos e a
manchete da home.

## P0 — o intervalo com a primeira dose dita não deixa rastro em NENHUMA superfície

**Fala:** `"tomar o remédio de 8 em 8 horas começando amanhã às 8h"`

```
titulo=«Tomar remédio» data=2026-10-08 hora=08:00 rec=Única valor=null amb=false faltando=[] qc=true
PROMESSA(recap)= Vai avisar Quinta-feira, 8 de outubro de 2026 às 08:00. Única.
ANUNCIO(apos salvar)= Vai avisar amanhã às 08:00.
TTS(alarme)= Está na hora. Tomar remédio.
```

Nenhuma das cinco menciona intervalo, "de 8 em 8 horas" ou "dose". `ambiguous=false`,
`notes` vazio, `qc=true` — o botão de confirmação rápida aceita sem freio.

**Causa:** `LocalTaskParser.kt:667-671` — o ramo `if (clockHit?.time != null)` devolve o
horário e **descarta o match do `INTERVAL`** sem marcar `ambiguous` nem gravar `note`. O
ramo irmão (`:672-677`), quando **não** há hora dita, faz as duas coisas. **O eixo dos
dias está preso; o das horas com hora dita não.**

**Prova de invisibilidade:** adicionando `ambiguous = true` + nota no ramo 667-671, a
suíte fica **verde** (`domain 489 / app 761, 0 failures`).

**Cobertura:** `LocalTaskParserValoresEFaixasTest.kt:300 intervaloComPrimeiraDoseNaoJogaAHoraFora`
afirma `localDate`, `localTime`, `title` e `missingFields`, e **não** afirma `ambiguous`
nem `notes`. O contraste está em `LocalTaskParserTest.kt:1571 intervaloComHoraDitaNaoPedeAHoraDeNovo`
(intervalo em **dias**), que afirma os dois.

**Cenário:** ela dita o antibiótico de 8 em 8 horas. O app mostra "Única", ela toca o
botão verde (o que sempre faz) e o recado confirma uma vez só. A segunda dose do dia não
existe em lugar nenhum — e o app nunca disse que tinha ignorado.

## P1 — "Não tem nada marcado para hoje" com o remédio de hoje na tela

**Estado:** uma ocorrência de **hoje** em `MISSED` com `lastReminderAt == null`.

```
TELA(manchete)= Boa tarde. Nada marcado agora.
TELA(secoes de atraso)= [(Não consegui avisar, [Tomar remédio Hoje])]
DIZ: «Não tem nada marcado para hoje.»
```

A mesma tela mostra "Não consegui avisar · Tomar remédio Hoje" e a voz responde que não
há nada. **Causa:** `HomeViewModel.kt:887-892` — `AskWhen.TODAY -> today to sections.today`;
`sections.missed` não entra na conta e `items.isEmpty()` dispara a negativa.
**Cobertura:** `FalaComandoTest.kt:98` e `:119` usam agenda **vazia** (sem `missed`) — o
estado "tem missed de hoje" não é exercido. Mutação M2 (`+ sections.missed`) → **verde**.

## P1 — o conteúdo da resposta falada não está preso por teste

Mutação M6: tirando o horário da linha falada (`"${title}."` em vez de `"${title} às ${time}."`)
→ **verde**. Os testes prendem o caso vazio ("Não tem nada marcado"), não o conteúdo da
linha. O horário dito — a informação que faz a resposta útil — some sem a suíte acusar.

## Medido e OK (não refazer)

- **O anúncio pós-salvar usa a data da ocorrência GRAVADA, não a do rascunho.** M3 (remover
  o `$whenLabel` de `AgendaFormat.announce`) mata **4 testes**. Eixo fechado.
- **Horário que já passou e não repete:** promessa e anúncio coerentes entre si.
- **A manchete conta os recados deixados para trás** — M4 (`+ agenda.missed` em
  `homeHeadline`) mata **5 testes**, incluindo os dois motivos que não contam duas vezes.
- **O título do TTS chega no alarme** — M5 mata `LembreteFaladoServiceTest`.
- **Alvo ambíguo/inexistente não finge que agiu:** "Tem mais de uma tarefa com esse nome…"
  e "Não achei nenhuma tarefa com esse nome."
- **Fala não entendida:** "Não consegui entender o recado. Tente de novo ou escreva a
  tarefa." — não afirma ação, oferece a porta do teclado.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| Run | Mutação | domain | app | Falhas |
|---|---|---|---|---|
| Baseline | — | 489 | 761 | 0 |
| **M1** | ramo 667-671 (`ambiguous`+`note`) | 489 | 761 | **0 — verde** |
| **M2** | `answerFor` TODAY `+ sections.missed` | 489 | 761 | **0 — verde** |
| M3 | `AgendaFormat.announce` sem `$whenLabel` (controle) | 489 | 761 | app **4** |
| M4 | `homeHeadline` `+ agenda.missed` (controle) | 489 | 761 | app **5** |
| M5 | `reminder_spoken` sem `%1$s` (controle) | 489 | 761 | app **1** |
| **M6** | `answerFor` linha falada sem o horário | 489 | 761 | **0 — verde** |

M3/M4/M5 são os controles: o mesmo harness que deu verde em M1/M2/M6 mata mutações reais
nos eixos vizinhos.

## Não confirmado

- **K5 — "Não achei nenhuma tarefa com esse nome." para um alvo já CONCLUÍDO.** Deliberado
  por contrato de `lookupTarget`, mas não se mediu se ela entende "não achei" como "não
  existe" quando a tarefa existe e está feita.
- **K7 — o anúncio omite a data** ("Vai avisar amanhã às 08:00.") enquanto o recap mostra
  "Quinta-feira, 8 de outubro de 2026". Divergência por desenho entre as duas superfícies.
- **TalkBack** como superfície separada (`contentDescription`): o texto existe, não foi
  executado por leitor de tela.
