package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Duas tomadas no mesmo dia ("às 8 da manhã e 8 da noite") — a fala de recorrência mais comum de
 * quem toma remédio.
 *
 * O `TaskSeries` tem UM só `localTime`: não há onde guardar duas doses. O desfecho correto não é
 * escolher uma delas nem inventar uma terceira — é marcar AMBÍGUO com nota, como o app já faz
 * quando a hora não foi dita. O que não pode acontecer é a caixa "Pode salvar?" oferecer um horário
 * que ninguém falou e ela confirmar em um toque.
 *
 * O esperado de cada caso é a doutrina escrita à mão — "ela disse dois horários, o app não pode
 * cravar um terceiro" —, nunca derivada da lógica do parser. Os controles do fim são o outro lado:
 * uma hora só com o minuto dito ("às 8 e meia" = 08:30) é legítima e NÃO pode virar ambígua.
 */
class LocalTaskParserDuasTomadasTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    // ---- D1: a segunda tomada em DÍGITO sem o "às" — o caso relatado ----

    @Test
    fun duasTomadasEmDigitoNaoViramUmHorarioInventado() {
        // "às 8 da manhã e 8 da noite": o relógio acha UMA hora (só o primeiro "8" casa com o "às")
        // e o "e 8" virava o MINUTO dela — 08:08, sem nota e com qc=true. A segunda tomada (20h) não
        // existe em campo nenhum e a caixa rápida confirma 08:08 em um toque. Nem 08:08 nem 08:00:
        // ela disse DOIS horários e o modelo só guarda um.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e 8 da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.localTime).isNull()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        assertThat(draft.notes).isNotEmpty()

        // O mesmo com o dia da semana no lugar do "todo dia": o relógio devolvia 08:00 cravado, e a
        // caixa rápida confirmava segunda-feira 08:00 com qc=true para as duas doses.
        val segunda = parser.parse("tomar remédio toda segunda às 8 da manhã e 8 da noite")
        assertThat(segunda.ambiguous).isTrue()
        assertThat(segunda.localTime).isNull()
        assertThat(segunda.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    @Test
    fun aSegundaTomadaEmDigitoNaoViraMinutoDaPrimeira() {
        // O dano exato: o "8" da segunda tomada entrava no MINUTO da primeira. Qualquer número dito
        // depois do primeiro período é um horário novo, nunca o minuto de uma hora já fechada.
        listOf(
            "tomar remédio todo dia às 8 da manhã e 20 da noite",
            "tomar remédio todo dia às 8 da manhã e 15 da tarde",
            "tomar remédio todo dia às 8 da manhã e 12 da tarde",
            "tomar remédio todo dia às 8 da manhã e 30 da noite",
            "tomar remédio todo dia às 8 da manhã e 45 da noite",
            "tomar remédio todo dia às 8 da manhã e 5 da tarde",
            "tomar remédio às 8 da manhã e 8 da tarde",
            "tomar remédio às 8 da manhã e 8 da madrugada",
            "tomar remédio às 8 da manhã e 8 de noite",
            "tomar remédio às 8 da manhã e 8 na noite",
            "tomar remédio às 8 da noite e 8 da manhã",
            "tomar remédio às 8 da manhã e 8 da noite e 2 da tarde",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    @Test
    fun segundaTomadaPorExtensoNaoViraMinutoNemTitulo() {
        // "e oito da noite": o extenso não casava o `MINUTE_TAIL` numérico, então a hora ficava 08:00
        // e o "oito" ia para o TÍTULO — 20h perdida com o nome da tarefa poluído.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e oito da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.localTime).isNull()
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()

        // "e vinte da noite": o "vinte" caía no `MINUTE_TAIL_WORDS` e virava 08:20.
        val vinte = parser.parse("tomar remédio todo dia às 8 da manhã e vinte da noite")
        assertThat(vinte.ambiguous).isTrue()
        assertThat(vinte.localTime).isNull()
        assertThat(vinte.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    // ---- D2: a segunda tomada em DÍGITO sem o "às" — o caso relatado ----

    @Test
    fun numeroSoltoQueCabeComoHoraContinuaSendoMinuto() {
        // O outro lado do critério: depois de um período já fechado, um número NU — dito ou não como
        // hora — é o MINUTO da mesma tomada. "às 8 da manhã e 12" são 08:12, e a base já lia assim.
        //
        // A alternativa de ler o número nu como hora nova porque ele CABE em 0–23 foi medida e
        // descartada: ela move a fronteira sem critério (derrubava "e 15" = 08:15 e mantinha
        // "e 30" = 08:30) e derrubava 9 formas naturais de minuto. Quem abre tomada é o PERÍODO
        // PRÓPRIO ("e 12 da noite"), não o valor do número.
        listOf(
            "tomar remédio todo dia às 8 da manhã e 8" to java.time.LocalTime.of(8, 8),
            "tomar remédio todo dia às 8 da manhã e 12" to java.time.LocalTime.of(8, 12),
            "tomar remédio todo dia às 8 da manhã e 15" to java.time.LocalTime.of(8, 15),
            "tomar remédio todo dia às 8 da manhã e 10" to java.time.LocalTime.of(8, 10),
            "tomar remédio todo dia às 8 da manhã e 20" to java.time.LocalTime.of(8, 20),
            "tomar remédio todo dia às 8 da manhã e 0" to java.time.LocalTime.of(8, 0),
            "tomar remédio todo dia às 8 da manhã e 5" to java.time.LocalTime.of(8, 5),
            "tomar remédio todo dia às 8 da manhã e 23" to java.time.LocalTime.of(8, 23),
            "tomar remédio todo dia às 8 da manhã e 30" to java.time.LocalTime.of(8, 30),
            "tomar remédio todo dia às 8 da manhã e 45" to java.time.LocalTime.of(8, 45),
        ).forEach { (frase, esperado) ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isEqualTo(esperado)
            assertThat(draft.ambiguous).isFalse()
        }
    }

    @Test
    fun numeroComPeriodoProprioAbreSegundaTomada() {
        // O que separa a hora do minuto é o PERÍODO PRÓPRIO, não o valor. "e 12" é minuto (08:12);
        // "e 12 da noite" é hora nova. O par abaixo é o que prende a distinção de natureza.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e 12 da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    // ---- D3: a segunda tomada em EXTENSO com "às" (a forma que já funcionava) ----

    @Test
    fun segundaTomadaComAsExplicitoContinuaAmbigua() {
        // O caminho que a caçada dizia estar certo: dois "às" ⇒ duas horas encontradas ⇒ ambíguo.
        // Não pode regredir para um horário cravado.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e às 8 da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.localTime).isNull()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    // ---- Controles: o `trailingMinutes` existe por um motivo legítimo e não pode morrer ----

    @Test
    fun minutoDitoNaMesmaTomadaContinuaSomando() {
        // ESTE é o motivo do `trailingMinutes`: a hora e o minuto na MESMA tomada, sem período
        // entre eles. "às 8 e meia" são 08:30 — não dois horários. Uma regra que só olhasse o "e
        // <número>" derrubaria justamente a forma como ela fala minuto.
        assertThat(parser.parse("tomar remédio às 8 e meia").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8 e quinze").localTime).isEqualTo(java.time.LocalTime.of(8, 15))
        assertThat(parser.parse("tomar remédio às 8 e 15").localTime).isEqualTo(java.time.LocalTime.of(8, 15))
        assertThat(parser.parse("tomar remédio às 8 e 30").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8 e 45").localTime).isEqualTo(java.time.LocalTime.of(8, 45))
        assertThat(parser.parse("tomar remédio às 8 e 12").localTime).isEqualTo(java.time.LocalTime.of(8, 12))
        assertThat(parser.parse("tomar remédio às 8 e 5").localTime).isEqualTo(java.time.LocalTime.of(8, 5))
        assertThat(parser.parse("tomar remédio às 8 e 0").localTime).isEqualTo(java.time.LocalTime.of(8, 0))

        // Sem hora anterior (o "e meia" colado num relógio já formado) e por extenso.
        assertThat(parser.parse("tomar remédio às 8 horas e meia").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio oito e meia").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("me lembrar de tomar remédio amanhã às 9 e meia").localTime)
            .isEqualTo(java.time.LocalTime.of(9, 30))
        assertThat(parser.parse("consulta meio-dia e meia").localTime).isEqualTo(java.time.LocalTime.of(12, 30))
    }

    @Test
    fun minutoDitoNaMesmaTomadaComPeriodoDepoisContinuaSomando() {
        // "às 8 e meia da noite": UM horário só (20:30) — o minuto vem colado na hora e o período
        // vem depois dele. Não é duas tomadas, e não pode virar ambíguo.
        assertThat(parser.parse("tomar remédio às 8 e meia da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(20, 30))
        assertThat(parser.parse("tomar remédio às 8 e 30 da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(20, 30))
        assertThat(parser.parse("tomar remédio às 8 e 20 da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(20, 20))
        assertThat(parser.parse("tomar remédio às 3 e meia da tarde").localTime)
            .isEqualTo(java.time.LocalTime.of(15, 30))
        assertThat(parser.parse("dormir às 12 e meia da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(0, 30))
    }

    @Test
    fun relogioEscritoComHhMmContinuaIgual() {
        // Os formatos com "h"/":" nem passam pelo `trailingMinutes`: a hora já vem fechada.
        assertThat(parser.parse("tomar remédio às 8:30").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8h30").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8:30 da manhã").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8h30 da manhã").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 30))
    }

    // ---- O teto não pode ler um número que OUTRO extrator já consumiu ----

    @Test
    fun valorEmReaisDepoisDoPeriodoNaoViraSegundaTomada() {
        // O valor em reais é lido ANTES do relógio, e o texto que o relógio recebe já não o tem.
        // O "12" de "e 12 reais" não é horário nenhum — é dinheiro. Um critério que varre a frase
        // INTEIRA conta esse número como segunda tomada e derruba o 08:00 que ela disse: a fala
        // vira ambígua por causa do preço, não por causa de uma dose a mais.
        //
        // O par "12 reais" (ambíguo) × "30 reais" (seguro) é o que denuncia o defeito: o gatilho é
        // o número caber em 0–23, e não existir um segundo horário.
        val draft = parser.parse("pagar a conta às 8 da manhã e 12 reais")
        assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.amountCents).isEqualTo(1200L)

        val vinte = parser.parse("pagar a conta às 8 da manhã e 20 reais")
        assertThat(vinte.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(vinte.ambiguous).isFalse()

        // O "30" passa do teto, então já ficava seguro na base — é o controle que mostra que o
        // problema é o teto lendo o número, não o número em si.
        val trinta = parser.parse("pagar a conta às 8 da manhã e 30 reais")
        assertThat(trinta.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(trinta.ambiguous).isFalse()
    }

    @Test
    fun segundaTomadaContinuaSendoVistaQuandoOValorNaoEstaLá() {
        // O outro lado do conserto: tirar o valor do caminho não pode cegar a regra para o caso
        // que ela existe para pegar. Sem os "reais", o mesmo "e 12" é um horário.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e 12 da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    @Test
    fun periodoDaSegundaParteSemNumeroNaoCravaNemInventa() {
        // "às 8 da manhã e de noite": a segunda parte só diz o período, sem número. NÃO há um
        // segundo horário dito, então a regra dos dois horários não tem o que pegar — e o 08:00 é
        // a primeira dose, que ela falou.
        //
        // FRONTEIRA, e por isso NÃO DISCRIMINA: as 10 variantes "só período" medidas dão idêntico
        // nas três revisões (base 55f7448, head e o fix). Não existe asserção que falhe na base
        // neste recorte — o que este teste guarda é o EXCESSO, o dia em que alguém afrouxar o
        // critério e o período solto virar segunda tomada. O que DISCRIMINA o eixo é
        // `eDistanteNaoAbreSegundaTomada`, que fica vermelho no head e verde na base e no fix.
        val draft = parser.parse("tomar remédio às 8 da manhã e de noite")
        assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(draft.ambiguous).isFalse()
        // Confirma rápido é falso aqui só porque não há data dita — o horário, esse, é o que ela falou.
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()

        // A variante com preposição ("e a noite") e a sem número nenhum depois do "e" também não
        // podem abrir tomada.
        listOf(
            "tomar remédio às 8 da manhã e a noite",
            "tomar remédio às 8 da manhã e na noite",
            "tomar remédio às 8 da manhã e de tarde",
            "tomar remédio às 8 da manhã e depois",
            "tomar remédio às 8 da manhã e também",
        ).forEach { frase ->
            val d = parser.parse(frase)
            assertThat(d.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
            assertThat(d.ambiguous).isFalse()
        }
    }

    // ---- P2: o "e X" que pertence a OUTRA oração não abre segunda tomada ----

    @Test
    fun eDistanteNaoAbreSegundaTomada() {
        // O casamento sem âncora lia o primeiro "e X" que aparecesse em QUALQUER lugar depois do
        // período e derrubava o horário por causa de um "e" que pertence a outra oração. O "e
        // depois e cinco" e o "e cinco minutos de caminhada" não são uma segunda dose: são o
        // segundo "e" de uma oração nova. ESTE é o teste que discrimina o eixo — ele morre no
        // head que casa o "e X" distante e passa na base e no fix.
        //
        // A asserção que MORRE NO HEAD é a do "e depois e cinco" (o "e cinco" distante casava o
        // alternador e derrubava o 08:00). A do "caminhada" é controle: os dois lados já a liam
        // certo, e ela guarda a unidade de minuto depois do "e" de outra oração.
        val distante = parser.parse("tomar remédio às 8 da manhã e depois e cinco")
        assertThat(distante.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(distante.ambiguous).isFalse()

        val caminhada = parser.parse("tomar remédio às 8 da manhã e cinco minutos de caminhada")
        assertThat(caminhada.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(caminhada.ambiguous).isFalse()

        // E o caso em que o "e X" DISTANTE traz o período próprio — o que prende a ANCORA. Sem ela,
        // o `SEGUNDA_TOMADA.find` casa este "e 8 da noite" (que está a uma oração de distância) e
        // derruba o 08:00. Medido: com a âncora, 08:00/amb=false; sem ela, null/amb=true.
        val oracao = parser.parse("tomar remédio às 8 da manhã e depois e 8 da noite")
        assertThat(oracao.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(oracao.ambiguous).isFalse()
    }

    // ---- P1: o MINUTO dito depois de um período volta a somar ----

    @Test
    fun minutoPorExtensoDepoisDoPeriodoSoma() {
        // A forma como ela diz o minuto depois de já ter dito o período: "às 8 da manhã e quinze"
        // são 08:15. O alternador `WORD_HOUR_ALT` da correção incluía `quinze`/`vinte` e não
        // incluía `trinta`/`quarenta` — daí "e quinze" virar ambíguo e "e trinta" continuar 08:30,
        // sem critério nenhum. O minuto é o minuto: quem desempata é o PERÍODO PRÓPRIO do número
        // ("e 20 da noite" é hora nova), não a palavra em si.
        listOf(
            "quinze" to 15,
            "vinte" to 20,
            "quarenta e cinco" to 45,
            "vinte e cinco" to 25,
            "vinte e dois" to 22,
            "trinta" to 30,
            "cinquenta" to 50,
        ).forEach { (palavra, minuto) ->
            val draft = parser.parse("tomar remédio às 8 da manhã e $palavra")
            assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(8, minuto))
            assertThat(draft.ambiguous).isFalse()
        }

        // "e dez" / "e cinco" NÃO somam — e nunca somaram: o `MINUTE_TAIL_WORDS` não os tem, então
        // a palavra sobra no título e a hora fica 08:00, idêntico na base. É pré-existente e está
        // registrado, não consertado. O que o teste prende é o que o head quebrou: a ambiguidade.
        listOf("dez", "cinco").forEach { palavra ->
            val draft = parser.parse("tomar remédio às 8 da manhã e $palavra")
            assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
            assertThat(draft.ambiguous).isFalse()
        }

        // Com a unidade de minuto dita: o mesmo minuto, não uma segunda tomada.
        assertThat(parser.parse("tomar remédio às 8 da manhã e quinze minutos").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 15))
        assertThat(parser.parse("tomar remédio às 8 da manhã e vinte minutos").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 20))

        // O número que a régua lia como segunda tomada e é o DIA do mês: "e 12 do mês que vem"
        // são 08:12, não 12h. A data é a outra leitura do número, e ela não é hora.
        val doMes = parser.parse("tomar remédio às 8 da manhã e 12 do mês que vem")
        assertThat(doMes.localTime).isEqualTo(java.time.LocalTime.of(8, 12))
        assertThat(doMes.ambiguous).isFalse()
    }

    @Test
    fun quantidadeDepoisDoEDoseNaoViraSegundaTomada() {
        // A doutrina do `p2EmPontoComQuantidadeNaoViraHora`: o "e <quantidade>" é DOSE, não segunda
        // hora. O teto numérico da correção lia o "2" de "e 2 comprimidos" como hora (cabe em 0–23)
        // e marcava ambíguo — uma afirmação falsa sobre a frase ("Há mais de um horário"), que é
        // exatamente o que aquele teste proíbe. A dose não afirma dois horários.
        //
        // Quem já garante isso no fix é a própria `SEGUNDA_TOMADA`: a unidade fica entre o número e
        // qualquer período, então a dose não casa. O teste prende o desfecho (não ambíguo), não o
        // mecanismo — é o que mantém a doutrina viva se a regex mudar de forma.
        //
        // O VALOR da hora é o que a base já fazia (08:02 etc. — o número entrando no minuto é
        // pré-existente e está fora do escopo deste lote). O que este teste prende é o que a
        // correção tinha quebrado: a AMBIGUIDADE. Por isso o esperado é `ambiguous = false` com o
        // horário idêntico ao da base — quem quiser fechar o 08:02 abre outro lote.
        listOf(
            "tomar remédio às 8 da manhã e 2 comprimidos" to java.time.LocalTime.of(8, 2),
            "tomar remédio às 8 da manhã e 1 comprimido" to java.time.LocalTime.of(8, 1),
            "tomar remédio às 8 da manhã e 12 gotas" to java.time.LocalTime.of(8, 12),
            "tomar remédio às 8 da manhã e 3 gotas" to java.time.LocalTime.of(8, 3),
            "tomar remédio às 8 da manhã e uma colher" to java.time.LocalTime.of(8, 0),
            "tomar água às 8 da manhã e 2 litros" to java.time.LocalTime.of(8, 2),
            "estudar às 8 da manhã e 20 páginas" to java.time.LocalTime.of(8, 20),
            "tomar remédio às 8 da manhã e 2 vezes ao dia" to java.time.LocalTime.of(8, 2),
            "tomar remédio às 8 da manhã e 20 minutos" to java.time.LocalTime.of(8, 20),
        ).forEach { (frase, esperado) ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isEqualTo(esperado)
            assertThat(draft.ambiguous).isFalse()
        }
    }
}
