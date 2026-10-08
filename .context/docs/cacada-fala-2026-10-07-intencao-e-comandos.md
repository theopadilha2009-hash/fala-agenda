# Caçada — a INTENÇÃO e os comandos (`SpeechIntentClassifier`) (2026-10-07)

Décima primeira leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06.md`, `cacada-fala-2026-10-06-novos.md`,
`cacada-fala-2026-10-07-correcao.md`, `cacada-fala-2026-10-07-dose-e-duracao.md`,
`cacada-fala-2026-10-07-pergunta-e-resposta.md`,
`cacada-fala-2026-10-07-ancoras-de-rotina.md`,
`cacada-fala-2026-10-07-numeros-e-valores.md`,
`cacada-fala-2026-10-07-cadeia-de-audio.md`,
`cacada-fala-2026-10-07-instalacao-e-atualizacao.md`,
`cacada-fala-2026-10-07-multiplas-tarefas.md`,
`cacada-fala-2026-10-07-titulo.md`,
`cacada-fala-2026-10-07-caminho-de-volta.md`,
`cacada-fala-2026-10-07-recorrencia.md` e
`cacada-fala-2026-10-07-data-e-hora.md`.

**As levas seguintes** (o índice parou na décima primeira e várias delas apontam para cá
dizendo "as anteriores estão listadas lá" — a lista abaixo é a que fecha a contagem):

| Leva | Doc |
|---|---|
| 12ª | `cacada-fala-2026-10-07-recado.md` |
| 13ª | `cacada-fala-2026-10-07-fonte-grande.md` |
| 14ª | `cacada-fala-2026-10-07-funcoes-de-limpeza.md` |
| 15ª | `cacada-fala-2026-10-07-acumulo-de-dados.md` |
| 16ª | `cacada-fala-2026-10-07-widget.md` |
| 17ª | `cacada-fala-2026-10-08-segunda-porta.md` |

Esta mede a **primeira porta do pipeline**: o classificador de intenção. Duas caçadas
anteriores declararam explicitamente que **não o mediram** ("ele roda antes do parser e
poderia desviar alguma fala"). Se ele classifica errado, o parser nunca vê a fala.

**Base:** `origin/main` @ `417201c` (o #83 já mergeado). Cópia descartável
(`/tmp/fala-caca-intent`), classificador real via `jshell` sobre o bytecode compilado,
mais a cadeia ponta-a-ponta (`classifier → parser → matcher`) num teste Robolectric real.
**~180 falas** distintas no classificador, **4 estados** ponta-a-ponta, **5 mutações**.
Oráculo escrito à mão a partir da doutrina do projeto.

Onde ele mora: `:domain`, JVM puro —
`domain/src/main/kotlin/com/theopadilha/falaagenda/domain/parser/SpeechIntent.kt`.
O executor em `:app` (`HomeViewModel.understandSpeech:801`).

## O pior caso do briefing NÃO existe

O medo era `"não tomei"` virar "concluí". **Não vira** — é `Capture`:

```
"não tomei" / "não tomei o remédio" / "ainda não tomei"  → Capture
"não quero cancelar" / "não cancela o médico" / "não apaga o remédio" → Capture
```

A negação **antes** do gatilho derruba a âncora `opensWith` e a fala cai em captura.
**O eixo da negação está sólido** — zero falsos positivos de comando.

## P0 — F1: conector de correção fora de `CONTENT_CUE` apaga o alvo DESCARTADO

O #83 fechou o eixo de correção, mas o comentário declara que ficaram de fora
"espera", "depois", "agora" e "então". **"espera" volta atrás** — e é fala coloquial dela:

```
"cancela o médico, espera, o dentista"        → recado="Tarefa excluída."  restam=[Dentista]
"cancela o médico, calma, o dentista"         → idem — o MÉDICO foi apagado
"cancela o médico, ops, o dentista"           → idem
"cancela o médico, pera, o dentista"          → idem
"cancela o médico, deixa eu ver, o dentista"  → idem
"já tomei o remédio, espera, o de pressão"    → Complete(target="remedio")  ← DOSE ERRADA
"apaga o remédio, espera, o de pressão"       → EraseNamed(target="remedio")
```

`restam=[Dentista]` prova: **o Médico foi apagado**, o Dentista (o alvo corrigido)
sobreviveu. O controle do #83 (`"não, o dentista"`) bloqueia; o `espera` não.

**Devia sair:** `Unknown(CORRECTION)` — bloquear e não agir, como o `não,` e o `peraí,`.

**Causa:** `CONTENT_CUE` em `SpeechIntent.kt:289-293` — lista fechada.

**Cobertura:** zero. `grep` nos testes: só `"peraí"` aparece (e está na lista);
`espera`/`calma`/`ops`/`deixa` → zero hits. Mutação: remover o gate de `cancel()`
derruba 19 testes → o **mecanismo** é coberto, a **forma** `espera` não é.

## P1 — F2: o alvo engole a conjunção ("cancela e cria outro")

O módulo declara que só material tolerado **antes** do gatilho muda o sentido, e
"na dúvida, Captura". Mas **depois** do gatilho o `targetAfter` (`SpeechIntent.kt:593`)
aceita qualquer coisa, inclusive uma segunda oração:

```
"cancela e cria outro"           → Cancel  target="e cria outro"
"cancela o médico e cria outro"  → Cancel  target="medico e cria outro"
"cancela o médico e a consulta"  → Cancel  target="medico e a consulta"
"cancela aí" / "cancela logo"    → Cancel  target="ai" / "logo"
"cancela o médico agora"         → Cancel  target="medico agora"
```

Com agenda que case, apaga **um** item (a fala pedia dois) ou o errado. Com agenda
vazia: "Não achei nenhuma tarefa com esse nome." — a segunda intenção é engolida.
**Teste:** não coberto.

## P1 — F3: `Cancel("")` é decisão declarada **sem teste** (assimetria de cobertura)

```
"cancela" / "desmarca"  → Cancel   target=""
"já tomei"              → Complete target=""   ← preso por teste (jaTomeiViraComandoDeConcluir)
```

`target=""` → matcher `None` → "Não achei nenhuma tarefa com esse nome." **Nunca age**
(não escolhe no chute) — desfecho seguro, decisão de produto já registrada em
`auditoria-fala-2026-10-05.md:496-505`. Mas a cobertura é assimétrica:

| Mutação | Resultado |
|---|---|
| MUT4 — rejeitar `Cancel` vazio | **domain 489 / app 756, 0 falhas → invisível** |
| MUT5 — rejeitar `Complete` vazio | 1 falha (pega) |

Metade da decisão está presa, a outra não. `eraseNamed()` (`:556`) **já rejeita** vazio.

## P2 — F4: qualificador de data no alvo é descartado → age na data errada

`SpeechTargetMatcher` poda os temporais do alvo (`:61-95`), então `"de amanhã"`/`"de sexta"`
some antes do casamento:

```
alvo="remedio de amanha" na agenda {Tomar remédio(HOJE)}         → One(occ-hoje)
alvo="consulta de sexta" na agenda {Consulta(quinta), Consulta de sexta} → Ambiguous
```

`"cancela o remédio de amanhã"` com uma dose de **hoje** pendente apaga a **de hoje**,
calado ("Tarefa excluída."). A decisão de podar é documentada como intencional ("a data
é o único elo que ela tem"), mas o efeito é agir na data errada. O matcher é coberto para
a poda (47 testes); o desfecho "apaga a data errada" não tem teste de invariante.

## P2 — F5: a lista `jaFiz` deixa passar conclusões legítimas

`jaFiz` (`SpeechIntent.kt:407-412`) exige `já + verbo`. Formas naturais caem em captura:

```
"tomei" / "terminei" / "fiz" / "acabei"        → Capture
"já coloquei o remédio"                        → Capture
"já desliguei" / "já organizei" / "já estudei" / "já conferi" → Capture
"registra que tomei"                           → Capture (título "Registra que tomei")
"conclui o de pressão"                         → Capture (título "Conclui pressão")
"ela tomou" / "já tomamos"                     → Capture
```

`"já lavei a louça"` funciona (verbo na lista); `"já coloquei"` não. `"conclui"` nu é
decisão aberta conhecida; `"tomei"` sem "já" e `"registra que tomei"` **não** estão nessa
decisão — e a dose segue pendente enquanto ela acha que registrou.

## P2 — F6: "esquece" (cancelar) não é reconhecido → vira tarefa-lixo

```
"esquece o médico" / "esquece a consulta" / "esquece tudo"  → Capture
"não preciso mais" / "não vou mais"                          → Capture
```

Nenhuma tarefa é apagada; nasce um rascunho com título-lixo (`"Esquece médico"`) — a mesma
classe do `"Cancela o médico"` que a camada existe para consertar. Nota: `"esquece"` **como
conector de correção** (`"cancela o médico, esquece, o dentista"`) funciona; como verbo de
cancelar, não.

## Medido e OK (não refazer)

- **Negação:** `não tomei`, `não tomei o remédio`, `não quero cancelar`, `não é pra cancelar`,
  `não cancela`, `não apaga` → todos `Capture`. **Zero falsos positivos de comando.**
- **Correção núcleo (#83):** `"cancela o médico, não, o dentista"` → `Unknown(CORRECTION)`;
  `"já tomei …, não, o de pressão"` → `CORRECTION`; terminais na cópula (`não é isso`) →
  `CORRECTION`. Remover os 3 gates derruba **19 / 10** testes → **fortemente coberto**.
- **Comando nu nunca age:** `target=""` → matcher `None`.
- **Ambiguidade:** dois "remédio" → `Ambiguous` → "mais de uma tarefa com esse nome"; não age.
- **Gatilho ancorado no início:** `"perguntar se a médica cancela a consulta"` → `Capture`.

## Contagem de testes (XMLs, nunca "BUILD SUCCESSFUL")

| suíte | testes | falhas |
|---|---|---|
| domain (pristine `origin/main`) | 489 | 0 |
| app (pristine `origin/main`) | 756 | 0 |
| MUT1 (remove `cancelaIsso`) | 489 | **3** (pega) |
| MUT2 (remove gate correção cancel/complete) | 489 | **19** (pega) |
| MUT3 (remove gate correção eraseNamed) | 489 | **10** (pega) |
| **MUT4** (rejeita `Cancel` vazio) | 489 / 756 | **0 / 0 — defeito invisível** |
| MUT5 (rejeita `Complete` vazio) | 489 | **1** (pega) |

## Não confirmado

- A tela real não foi executada; "recado" e "restam" vêm do `HomeViewModel` + Room
  (Robolectric), não de um emulador.
- A IA remota não foi chamada (o eixo é do classificador local).
- Não se varreu sistematicamente todas as posições de conector; mediram-se as formas do
  briefing mais as que a lista `CONTENT_CUE` exclui de propósito.
