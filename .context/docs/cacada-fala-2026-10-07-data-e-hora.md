# Caçada — DATA e HORA, a fala que diz *quando* (2026-10-07)

Décima leva de caçadas. As anteriores estão em `auditoria-fala-2026-10-05.md`,
`cacada-fala-2026-10-06.md`, `cacada-fala-2026-10-06-novos.md`,
`cacada-fala-2026-10-07-correcao.md`, `cacada-fala-2026-10-07-dose-e-duracao.md`,
`cacada-fala-2026-10-07-pergunta-e-resposta.md`,
`cacada-fala-2026-10-07-ancoras-de-rotina.md`,
`cacada-fala-2026-10-07-numeros-e-valores.md`,
`cacada-fala-2026-10-07-cadeia-de-audio.md`,
`cacada-fala-2026-10-07-instalacao-e-atualizacao.md`,
`cacada-fala-2026-10-07-multiplas-tarefas.md`,
`cacada-fala-2026-10-07-titulo.md`,
`cacada-fala-2026-10-07-caminho-de-volta.md` e
`cacada-fala-2026-10-07-recorrencia.md`.

Esta mede o eixo mais importante de uma agenda: **quando**. Medições em clone
descartável (`/tmp/fa-hunt`), parser real (`LocalTaskParser`, `:domain`, JVM puro),
**~240 falas em 9 probes com 6 relógios fixos** (`FixedAppClock`,
`America/Sao_Paulo`): 20/08/2026 10:00 (quinta), 07/08 (sexta), 28/12 (segunda),
10/02, 07/10. Baseline da suíte `:domain` = **439 testes / 0 falhas**.

O oráculo foi escrito **à mão** (a doutrina), nunca derivado da lógica do parser.

## Manchete

**5 findings, e 4 deles 100% não testados** — as mutações que os neutralizam deixam a
suíte em 0 falhas. O pior é o Finding 1, que descarta um dia inteiro **calado**,
`qc=true`, sem nota, e ainda deixa o dia perdido no **título**.

## P0 — Finding 1: dois dias relativos numa fala, um dia some CALADO

```
"hoje e amanhã às 9h"             → title="Hoje"          date=2026-08-21 time=09:00 qc=true amb=false notes=[]
"dentista hoje e amanhã às 9h"    → title="Dentista hoje" date=2026-08-21 qc=true amb=false notes=[]
"almoço hoje e amanhã às 13h"     → title="Almoço hoje"   date=2026-08-21 qc=true amb=false notes=[]
"reunião hoje e amanhã às 9h"     → title="Reunião hoje"  date=2026-08-21 qc=true amb=false notes=[]
"amanhã e depois de amanhã às 9h" → title="Amanhã"        date=2026-08-22 qc=true amb=false notes=[]
```

**Hoje sai:** **um** cartão para **amanhã**, `isComplete=true`, `qc=true`, `amb=false`,
**sem nenhuma nota**. O primeiro dia dito é descartado e **sobra no título** ("Hoje",
"Dentista hoje").

**Devia sair:** ambíguo com nota (como o app já faz com `"segunda e quarta"`), ou duas
tarefas. Um dos dois dias não pode ser descartado em silêncio.

**O contra-exemplo que prova que a maquinaria existe:** com **verbo de tarefa**, o
detector dispara e marca ambíguo:

```
"tomar remédio hoje e amanhã às 9h" → amb=true, time=null, título="Tomar remédio hoje"
```

Ou seja: **exatamente nas falas com substantivo** ("dentista", "reunião", "almoço") — o
jeito natural de falar — o defeito é silencioso e confirmável num toque.

**Causa:** `extractDate` devolve cedo no **primeiro** ramo de dia relativo que casa, sem
olhar o segundo. Ordem: `depois de amanhã` (`LocalTaskParser.kt:1078`), `amanhã` (`:1082`),
`hoje` (`:1086`). O `looksLikeTwoTasks` (`:1628`) só divide orações por `\be\b` (`:1632`) e
exige verbo de tarefa ou cruzamento data×hora em orações **distintas** — `"hoje e amanhã às
9h"` não tem verbo e o dia e a hora caem na **mesma** oração, então ele deixa passar.

**Cobertura:** **zero**. Grep por `"hoje e amanhã"` / `"amanhã e depois de amanhã"` nos
testes → vazio. **Mutação A** (dois dias relativos escalarem) → suíte **0 falhas**.

## P0 — Finding 2: `"amanhã e depois de amanhã"` também cola tudo

```
"amanhã e depois de amanhã" → title="Amanhã" date=2026-08-22 time=null amb=false missing=[TIME]
```

O "amanhã" (21) some da data e sobra no **título**; o rascunho fica com o dia+2. Silencioso
(`amb=false`), e o `HybridParser.deveEscalar` só escala por hora ausente. Mesma causa raiz
do Finding 1.

## P1 — Finding 3: `"daqui a um mês"` / `"em dois meses"` / `"daqui a um ano"` perdem a data

```
"pagar conta daqui a um mês"      → title="Pagar conta"        date=null amb=false (nota genérica)
"pagar conta daqui a dois meses"  → title="Pagar conta dois meses"  date=null   ← a expressão vaza no título
"pagar conta em dois meses"       → title="Pagar conta dois meses"  date=null
"pagar conta daqui a três meses"  → title="Pagar conta três meses"  date=null
"pagar conta daqui a um ano"      → title="Pagar conta"        date=null
```

**Devia sair:** `daqui a um mês` = 20/09/2026; `em dois meses` = 20/10/2026; `daqui a um
ano` = 20/08/2027.

**Contra-exemplo (irmãos que funcionam):** `"daqui a 30 dias"` → 19/09; `"em 45 dias"` →
04/10; `"daqui a 3 semanas"` → 10/09.

**Causa:** `RELATIVE_DAY` (`LocalTaskParser.kt:1907-1909`) só casa `dias?|semanas?` —
"mês"/"ano" caem fora. Não é perda 100% silenciosa (há nota genérica e a IA escala), mas o
local resolve os irmãos "dias/semanas" e não estas formas.

**Cobertura:** zero. **Mutação B** → suíte **0 falhas**.

## P1 — Finding 4: `"depois do almoço às duas"` → 02:00 (re-confirmação)

```
"amanhã depois do almoço às duas"  → date=2026-08-21 time=02:00 amb=true
   notes=["as duas" pode ser de manhã ou de tarde. Confirme o horário.]
```

O horário fica **02:00**; o `extractPeriodHint` (`:1469`) e o `applyPeriod` (`:1507`) não
resolvem a âncora "depois do almoço" (coerente só com 12–13h). Hoje o `amb=true` vem da
regra `1..6 ⇒ ambíguo` (`:837`), **não** da âncora — com hora 7–11 a âncora não barra nada.
**É re-confirmação** do já catalogado em `cacada-fala-2026-10-07-ancoras-de-rotina.md`
(16/16 silenciosos com `qc=true`), não achado novo.

## P2 — Finding 5: ano explícito no passado é aceito calado

```
"consulta 20/10/2020" (relógio 28/12/2026) → date=2020-10-20 amb=false
"consulta 25/12/26"   (25/12/2026 já passou) → date=2026-12-25 amb=false
```

`resolveDate` (`:1574-1585`), quando o ano é **dito**, devolve a data sem checar passado; sem
hora não há `INSTANTE_PASSADO`. Menos grave (ela disse o ano), mas uma data vencida entra sem
aviso.

**Cobertura:** zero. **Mutação H** → suíte **0 falhas**.

## Nota mentirosa: NÃO reproduzi (o eixo está sólido neste ponto)

Exercitei o merge `local ↔ IA` do `HybridParser` (4 estados, remote falso):
nota `IA_DATA_ILEGIVEL` com desfecho **sem** data → nota **permanece** (verdadeira); mesmo com
data no final → **suprimida** (correta); `FALTA_HORA` local + IA traz hora → suprimida;
`INSTANTE_PASSADO` local + final futuro → suprimida. **Nenhuma nota mentirosa nova.** As duas
correções anteriores seguem válidas.

## O que saiu CERTO (para o número não ser só defeito)

- **Ano errado / roll:** `"20/10"` em 28/12 → 20/10/**2027** com nota "Data a confirmar" e
  `ambiguous=true`; `"05/08"` já passado → 2027 + nota. **Correto.**
- **Data no passado:** `"dia 5"` com hoje=07 → rola para 05/09 (nunca agenda no passado);
  `"no dia 31"` em fevereiro → recusa com "Data impossível". **Correto.**
- **Impossíveis:** `31/02`, `dia 32`, `25 horas` → recusa/ambíguo. **Correto.**
- **Composição de horário:** `8:30`, `8h30`, `14h30`, `oito e meia`, `meio-dia e meia`,
  `8h da noite`→20h, `2 da tarde` sem "às" → ambíguo (D3 intencional). **Correto.**
- **Dia da semana + hora:** `"dentista sexta às 14h30"`, `"toda segunda e quarta às 8h"`. **Correto.**

## Prova por mutação (uma por execução, restaurada por `cp`, sha conferido)

Pristine do parser: `cec446372fa4f1325a0054875209340e830cb239db00aa057a21c2cc49441b34`.
`git checkout --` nunca usado. Repo principal intocado.

| Mutação | O que muda | Resultado | Veredito |
|---|---|---|---|
| A | dois dias relativos → escalar | 439 reais, **0 falhas** | Finding 1 não testado |
| B | `RELATIVE_DAY` aceita mês/ano | **0 falhas** | Finding 3 não testado |
| G | `primeiro` como dia-1 | **0 falhas** | `"dia primeiro"`/`"1º"` não testado |
| H | ano explícito passado → ambíguo | **0 falhas** | Finding 5 não testado |
| D | remove `localTime = null` de `looksLikeTwoTasks` | 2 falhas | **coberto** |
| E | desliga nota `INSTANTE_PASSADO` | 3 falhas | **coberto** |
| F | permite data numérica levemente passada | 1 falha | **coberto** |

## Hipóteses DESCARTADAS (honestidade)

- **Colon engolido (`"às 8:30"` → 08:00): falso.** `8:30`, `14:30`, `08:05` resolvem certo
  (`CLOCK_NUMERIC` casa `\d{1,2}[:h]\d{2}`). A mutação que testava isso testava um
  não-defeito — a falha era do oráculo, que exigia 14:00 de `"depois do almoço às duas"`
  (isso é o Finding 4, não colon).
- **`"oito da manhã"` / `"2 da tarde"` sem `"às"`:** perda **intencional** (D3, `:797-801`),
  marca `ambiguous=true`. Não é defeito novo.
- **`"ontem"`, `"mais tarde"`, `"1º de novembro"`:** já catalogados como BAIXA em
  `cacada-fala-2026-10-06.md` e no N4 da auditoria — re-confirmados, não novos.

## Não confirmado

Nenhuma fala rodou no app real/emulador; toda a evidência é do parser (`:domain`, JVM puro)
com `Clock`/`ZoneId` fixos. O caminho da **IA real** não foi medido — usou-se um remote falso;
não se sabe o que o modelo devolve para "daqui a um mês".
