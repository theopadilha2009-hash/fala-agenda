# Caçada — as âncoras de rotina (2026-10-07)

Sexta leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06-novos.md`, `cacada-fala-2026-10-07-correcao.md`,
`cacada-fala-2026-10-07-dose-e-duracao.md` e `cacada-fala-2026-10-07-pergunta-e-resposta.md`.

Esta mede como ela ancora o horário na **própria rotina** em vez de no relógio: "depois do
almoço", "antes de dormir", "quando eu acordar", "na novela", "de tardinha".

Medições com o **parser real** (`LocalTaskParser`) e `FixedAppClock` em
`2026-08-20 10:00 America/Sao_Paulo` (sexta-feira). Espaço: 13 âncoras × 2 posições ×
(sem hora | hora que bate | hora que contradiz).

> **Nota de sobreposição, honesta:** o eixo **não é totalmente novo**.
> `cacada-fala-2026-10-06-novos.md` já traz `ORACLE_ANCORA total=17 vazouOuCalado=17` (N2),
> com o mesmo achado-base. O que esta medição acrescenta é a **quantificação do espaço
> cartesiano** (posição × presença de hora × coerência), o número da **contradição âncora ×
> hora** (16/16 silencioso com `qc=true`) e o controle que funciona (`depois do jantar` tem
> guarda; as demais não).

## Manchete

**A âncora nunca vira hora, e em 16 de 26 frases ela fica presa no título.** O parser
**não reconhece** nenhuma das 13 âncoras como horário: 0 de 20 geram instante. Pior: em
**14 de 26** o resultado é `hora=null && ambiguous=false` — a âncora some em silêncio, sem
nota, e o app escala para a IA **com a âncora já dentro do título** da tarefa.

A terceira via é a pior: nem reconhece (não vira hora), nem marca ambíguo (não pede
confirmação), nem limpa (fica no título).

## P1 — 16 de 26 âncoras sozinhas ficam no título, com `ambiguous=false`

```
ORACLE_ANCORA_SOZINHA total=26 ancoraVazouNoTitulo=16 horaNulaE_SemAviso=14 ambiguo=12 canQuickConfirm=0
```

Saída crua (o `title` é o que sobra no nome da tarefa):

```
A|depois do almoço        | time=null amb=true  | leak=false | title=Tomar remedio          <- reconhecida
A|antes do almoço         | time=null amb=false | leak=true  | title=Tomar remedio antes almoço
A|depois do café da manhã | time=null amb=true  | leak=true  | title=Tomar remedio café
A|antes de dormir         | time=null amb=false | leak=true  | title=Tomar remedio antes dormir
A|na hora de deitar       | time=null amb=false | leak=true  | title=Tomar remedio deitar
A|quando eu acordar       | time=null amb=false | leak=true  | title=Tomar remedio quando eu acordar
A|depois do banho         | time=null amb=false | leak=true  | title=Tomar remedio banho
A|na novela               | time=null amb=false | leak=true  | title=Tomar remedio novela
A|antes da missa          | time=null amb=false | leak=true  | title=Tomar remedio antes missa
A|quando voltar da feira  | time=null amb=false | leak=true  | title=Tomar remedio quando voltar feira
A|de tardinha             | time=null amb=false | leak=true  | title=Tomar remedio tardinha
A|de manhãzinha           | time=null amb=false | leak=true  | title=Tomar remedio manhãzinha
A|lá pelas tantas         | time=null amb=false | leak=true  | title=Tomar remedio lá pelas tantas
A|mais tarde              | time=null amb=false | leak=false | title=Tomar remedio mais
```

Só **5** âncoras (`depois do almoço`, `depois/antes do jantar`, `à noitinha`, `no meio da
tarde`) são removidas do título **e** marcam ambíguo. As outras **8** são ignoradas por
completo.

Notas e escalação (`escala = amb || data==null || hora==null`, réplica de
`HybridParser.deveEscalar`):

```
tomar remédio depois da novela   | time=null amb=false escala=true | title=Tomar remédio novela   | notes=[Falta a data…, Falta o horário…]
tomar remédio quando eu acordar  | time=null amb=false escala=true | title=… quando eu acordar    | notes=[Falta a data…, Falta o horário…]
tomar remédio depois do banho    | time=null amb=false escala=true | title=Tomar remédio banho    | notes=[Falta a data…, Falta o horário…]
tomar remédio depois do almoço   | time=null amb=true  escala=true | title=Tomar remedio          | notes=[“depois do almoço” não é um horário exato…, Falta a data…, Falta o horário…]
```

## P1 — âncora + hora contraditória: o app crava a hora e joga a âncora fora, calado

```
ORACLE_CONTRADICAO total=26 horaForaDaJanela_semAviso=16 dessasComQc=16 vazouAncoraNoTitulo=16
```

```
VIOLA|depois do almoço      | hora=08:00 fora da janela 12-15, amb=false qc=true | title=Tomar remédio
VIOLA|antes de dormir       | hora=09:00 fora da janela 21-23, amb=false qc=true | title=Tomar remédio antes dormir
VIOLA|na hora de deitar     | hora=09:00 fora da janela 21-23, amb=false qc=true | title=Tomar remédio deitar
VIOLA|quando eu acordar     | hora=15:00 fora da janela 5-8,  amb=false qc=true | title=Tomar remédio quando eu acordar
VIOLA|depois do café da manhã| hora=20:00 fora da janela 8-11, amb=false qc=true | title=Tomar remédio café
VIOLA|na novela             | hora=09:00 fora da janela 20-22,amb=false qc=true | title=Tomar remédio novela
VIOLA|antes da missa        | hora=15:00 fora da janela 7-10, amb=false qc=true | title=Tomar remédio antes missa
```

**16 de 16** com `amb=false` **e** `canQuickConfirm=true`: a caixa "Pode salvar?" abre
pronta, um toque, com a hora contraditória cravada. `"depois do almoço às 8 da manhã"` →
`08:00, qc=true, título limpo, sem nota` — a âncora (12–13h) foi descartada sem aviso.

A guarda de coerência **existe**, mas só para o `jantar`:

```
E|depois do jantar às 6 | time=06:00 amb=true qc=false | notes=[“depois do jantar” e a hora dita não batem…]
```

## Controles (o que funciona)

```
CTL|amanhã tomar remédio às 8h                   | time=08:00 amb=false qc=true | title=Tomar remédio
CTL|amanhã tomar remédio depois do almoço às 13h | time=13:00 amb=false qc=true | title=Tomar remédio
CTL|amanhã tomar remédio à noitinha às 8         | time=20:00 amb=false qc=true | title=Tomar remédio
E  |tomar remédio de tarde                       | time=null  amb=true  qc=false| title=Tomar remédio
E  |depois do jantar às 6                        | time=06:00 amb=true  qc=false| notes=[“depois do jantar” e a hora dita não batem…]
```

`"de tarde"` / `"de manhã"` **sozinhos** funcionam certo: `time=null, amb=true, qc=false`,
título limpo, nota "não é um horário exato". Mas `"de tardinha"` / `"de manhãzinha"`
**não casam** — a `PERIOD_PHRASE` exige `\bmanha\b`/`\btarde\b`, não a forma diminutiva — e
vazam para o título com `amb=false`.

## Conclusão medida, hipótese por hipótese

- **A âncora vira hora?** **Não.** `reconhecida=0` de 20; nenhuma gera instante.
- **A âncora entra no título?** **Sim** — 16/26, com `ambiguous=false` e só a nota genérica
  "Falta o horário". Escala para a IA **com a âncora já dentro do título**.
- **Engolida em silêncio?** A parte semântica sim: **14/26** ficam `hora=null &&
  ambiguous=false`; nenhuma nota aponta a âncora.
- **`PERIOD_PHRASE` com período sem hora:** correto para "de tarde"/"de manhã"; quebrado
  para as formas diminutivas.
- **Âncora + hora contraditória:** o app guarda a hora explícita e descarta a âncora em
  silêncio — **16/16** violações com `amb=false` e `qc=true`. A guarda existe só para
  `jantar`.

## Não confirmado

- **A execução não foi `--rerun-tasks` fresca.** Os números vieram dos `.class` compilados
  (`build/classes/.../main`, mtime 03:03) contra fonte limpa no HEAD; batem com os probes
  já medidos na caçada anterior para os casos compartilhados, mas quem quiser o selo do
  método do projeto precisa recompilar.
- **A IA real** não foi chamada; o desfecho do caminho escalado (o que a IA faz com a
  âncora no título) não foi medido.
- **A renderização da caixa rápida** com o título contendo a âncora não foi medida.

## PENDENTE (decisão de produto)

1. **Dar hora social implícita a `depois do almoço`** (≈12–13h) ou mantê-la "vaga" e
   ambígua. Hoje ela é removida do título e marca ambíguo — o meio-termo.
2. **Tratar os marcadores de rotina** (`depois da novela`, `depois do banho`, `quando eu
   acordar`, `antes da missa`) como âncora real **ou** como "não é hora exata" ambíguo.
   Hoje são a terceira via, a pior: calados no título.
3. **Âncora + hora contraditória**: escalar/ambíguo (como o `jantar` faz) em vez de cravar
   a hora em silêncio. É a mesma doutrina do `HybridParser` ("duas expressões de tempo
   discordam ⇒ escala"), aplicada só para o `jantar`.
4. **As formas diminutivas** (`de tardinha`, `de manhãzinha`) deveriam entrar no
   `PERIOD_PHRASE` — hoje vazam para o título com `amb=false`.
