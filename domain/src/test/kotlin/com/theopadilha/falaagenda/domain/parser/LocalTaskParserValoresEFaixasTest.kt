package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Faixa de horário ("das 14 às 16h"), valor em reais, quantidade por dígito, "meia dúzia" e o
 * intervalo com a primeira dose dita. São as frases em que o app entendia errado e a caixa rápida
 * confirmava em silêncio — o alarme tocava na hora errada ou o título perdia o que ela falou.
 */
class LocalTaskParserValoresEFaixasTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)


    // ---- Faixa: o compromisso é no INÍCIO, nunca na hora de término ----

    @Test
    fun faixaDasQuatorzeAsDezesseisTocaNoInicio() {
        // Antes: o "14" não casava como hora (sem "h") e o "16h" virava a hora do alarme —
        // 2h depois do que ela disse, com a caixa rápida confirmando sozinha.
        val draft = parser.parse("fisioterapia das 14 às 16h amanhã")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(draft.title).isEqualTo("Fisioterapia")
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.missingFields).isEmpty()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
    }

    @Test
    fun faixaSemHNasDuasHorasTocaNoInicio() {
        val draft = parser.parse("trabalho das 8 às 17 amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Trabalho")
    }

    @Test
    fun faixaComPeriodoNoFimTocaNoInicio() {
        val draft = parser.parse("médico das 9 às 10 da manhã amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.title).isEqualTo("Médico")
    }

    @Test
    fun faixaPorExtensoComPeriodoNaoDeixaRestoNoTitulo() {
        // Antes: time=16:00 e title="Duas" — a hora de término e um fragmento do início.
        val draft = parser.parse("das duas às quatro da tarde amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(draft.title).doesNotContain("Duas")
    }

    @Test
    fun faixaPorExtensoSemPeriodoFicaAmbigua() {
        // "das duas às quatro" pode ser 2h ou 14h: mantém o palpite do início, mas marca ambíguo
        // para ela confirmar — a mesma regra de "às três" sem período. Cravar 14h seria inventar.
        val draft = parser.parse("reunião das duas às quatro amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(2, 0))
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.title).isEqualTo("Reunião")
    }

    @Test
    fun faixaComHNasDuasHorasTambemTocaNoInicio() {
        val draft = parser.parse("reunião das 14h às 16h amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(draft.title).isEqualTo("Reunião")
    }

    @Test
    fun faixaDeXASemHNaoCravaAHora() {
        // "de 14 a 16" não diz qual das duas é o compromisso: fica sem hora e escala, como hoje.
        // O que melhora é o título — antes o "14 16" sobrava nele.
        val draft = parser.parse("reunião de 14 a 16 amanhã")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isNull()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.title).isEqualTo("Reunião")
    }

    @Test
    fun entreNaoRegride() {
        // "entre 9 e 10" fica como estava: o alvo é a faixa com "das ... às ...".
        val draft = parser.parse("entre 9 e 10")
        assertThat(draft.title).isEqualTo("Entre")
    }

    @Test
    fun dasComHoraAvulsaContinuaVirandoHora() {
        // O "das 8" sozinho é a hora (o "das" abre a hora em pt-BR). Sem isto o "8" sobrava no
        // título depois que o filtro de dígitos saiu de `extractTitle`.
        val draft = parser.parse("reunião das 8 amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Reunião")
    }

    // ---- Valor em reais ----

    @Test
    fun valorEmDigitosComReais() {
        val draft = parser.parse("pagar a conta de luz de 120 reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(12000L)
        assertThat(draft.title).isEqualTo("Pagar conta luz")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
    }

    @Test
    fun valorPorExtenso() {
        val draft = parser.parse("pagar a conta de luz de cento e vinte reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(12000L)
        assertThat(draft.title).isEqualTo("Pagar conta luz")
    }

    @Test
    fun valorComCifrao() {
        val draft = parser.parse("pagar R$ 30 amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(3000L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun valorPorExtensoComCentenaEDezema() {
        val draft = parser.parse("paguei duzentos e cinquenta reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(25000L)
        assertThat(draft.title).isEqualTo("Paguei")
    }

    @Test
    fun valorComMil() {
        val draft = parser.parse("pagar mil e duzentos reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(120000L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun semValorNaoInventa() {
        val draft = parser.parse("comprar duas caixas de leite amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).isEqualTo("Comprar duas caixas leite")
    }

    // ---- O review: multiplicador antes de "mil" e separador de milhar por ponto ----

    @Test
    fun valorPorExtensoComMultiplicadorAntesDeMil() {
        // "dois mil e quinhentos" é justaposto, sem o "e" entre "dois" e "mil". A repetição do
        // regex só aceitava o "e", então o motor re-ancorava em "mil e quinhentos" e gravava
        // R$1.500,00 em vez de R$2.500,00 — e a caixa rápida confirmava sozinha.
        val doisMilQuinhentos = parser.parse("pagar dois mil e quinhentos reais amanhã às 10h")
        assertThat(doisMilQuinhentos.amountCents).isEqualTo(250000L)
        assertThat(doisMilQuinhentos.title).isEqualTo("Pagar")
        assertThat(doisMilQuinhentos.canQuickConfirm(clock.instant(), zone)).isTrue()

        assertThat(parser.parse("pagar dois mil reais amanhã às 10h").amountCents).isEqualTo(200000L)
        assertThat(parser.parse("pagar tres mil reais amanhã às 10h").amountCents).isEqualTo(300000L)
        assertThat(parser.parse("pagar dez mil reais amanhã às 10h").amountCents).isEqualTo(1000000L)
        assertThat(parser.parse("pagar quinhentos mil reais amanhã às 10h").amountCents).isEqualTo(50000000L)
        assertThat(parser.parse("pagar mil duzentos reais amanhã às 10h").amountCents).isEqualTo(120000L)
    }

    @Test
    fun valorEmDigitoComSeparadorDeMilharPorPonto() {
        // "15.000 reais" entrava com amountCents=0 — o ponto era lido como decimal e a captura
        // parava em "15.00" (não numérico). O título ainda guardava o "15.000".
        val quinzeMil = parser.parse("pagar 15.000 reais amanhã às 10h")
        assertThat(quinzeMil.amountCents).isEqualTo(1500000L)
        assertThat(quinzeMil.title).isEqualTo("Pagar")

        // "R$ 1.234,56": o ramo do cifrão parava em "1.23" e gravava R$1,23.
        val cifrao = parser.parse("pagar R$ 1.234,56 amanhã às 10h")
        assertThat(cifrao.amountCents).isEqualTo(123456L)
        assertThat(cifrao.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar R$ 1.500 amanhã às 10h").amountCents).isEqualTo(150000L)
        assertThat(parser.parse("pagar 1.200 reais amanhã às 10h").amountCents).isEqualTo(120000L)

        // A vírgula decimal já funcionava e não pode regredir.
        assertThat(parser.parse("pagar 30,50 reais amanhã às 10h").amountCents).isEqualTo(3050L)
    }

    // ---- O review: centavos falados, "meio real" e "rs 30" ----

    @Test
    fun centavosFaladosSomamAoValorESaemDoTitulo() {
        // "quinze reais e cinquenta centavos" gravava 1500 e deixava "cinquenta centavos" no título.
        val draft = parser.parse("pagar quinze reais e cinquenta centavos amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(1550L)
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar 15 reais e 50 centavos amanhã às 10h").amountCents).isEqualTo(1550L)
    }

    @Test
    fun meioRealViraCinquentaCentavos() {
        val draft = parser.parse("pagar meio real amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(50L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun rsPorExtensoDoReconhecimentoViraValor() {
        // O Vosk às vezes devolve "rs 30" no lugar de "R$ 30"; antes não virava valor nenhum.
        val draft = parser.parse("pagar rs 30 amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(3000L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun valorPorExtensoComFemininoAntesDeMil() {
        // "trezentas" existe no léxico do parser mas não no regex: a frase ficava sem valor.
        val draft = parser.parse("pagar duas mil e trezentas reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(230000L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    // ---- Quantidade por dígito e "meia dúzia" ----

    @Test
    fun quantidadeEmDigitoFicaNoTitulo() {
        // Antes: "Comprar caixas leite" — ela não sabia quantas caixas eram.
        val caixas = parser.parse("comprar 2 caixas de leite amanhã às 10h")
        assertThat(caixas.title).isEqualTo("Comprar 2 caixas leite")

        val remedios = parser.parse("levar 3 remédios amanhã às 10h")
        assertThat(remedios.title).isEqualTo("Levar 3 remédios")

        val ovos = parser.parse("comprar 12 ovos amanhã às 10h")
        assertThat(ovos.title).isEqualTo("Comprar 12 ovos")
    }

    @Test
    fun meiaDuziaNaoViraDuzia() {
        // Antes: title="Comprar dúzia ovos" — o dobro do que ela pediu.
        val draft = parser.parse("comprar meia dúzia de ovos amanhã às 10h")
        assertThat(draft.title).isEqualTo("Comprar meia dúzia ovos")
    }

    @Test
    fun digitoJaInterpretadoNaoVoltaParaOTitulo() {
        // O alvo 3 ("2 caixas" fica no título) preservava também o dígito que o parser JÁ usou como
        // dia, hora ou recorrência. Os valores esperados são os títulos do `main` (fbebfa9) medidos
        // com este mesmo relógio de 2026-08-20 10:00 — a regressão é o PR tê-los mudado.
        val doMain = mapOf(
            "todo dia 5 caminhar" to "Caminhar",
            "tomar remédio 8 da manhã" to "Tomar remédio",
            "amanhã consulta dia 5 de manhã" to "Consulta dia",
            "todo dia 5 da tarde" to "",
            "toda semana 5 da tarde" to "Toda semana",
            "tomar remédio todo dia 5 da tarde" to "Tomar remédio",
            "pagar conta no dia 25 e no dia 30" to "Pagar conta dia dia",
            "tomar remédio às 8 em ponto e às 20h" to "Tomar remédio ponto",
            "marcar médico às 10 em ponto e dentista às 15h" to "Médico ponto dentista",
            "amanhã reunião dia 12 em ponto" to "Reunião dia ponto",
        )
        doMain.forEach { (frase, tituloNoMain) ->
            assertThat(parser.parse(frase).title).isEqualTo(tituloNoMain)
        }

        // A quantidade genuína continua no título — é o alvo 3, e não pode regredir de volta.
        assertThat(parser.parse("comprar 2 caixas de leite amanhã às 10h").title)
            .isEqualTo("Comprar 2 caixas leite")
        assertThat(parser.parse("levar 3 remédios amanhã às 10h").title).isEqualTo("Levar 3 remédios")
        assertThat(parser.parse("comprar 12 ovos amanhã às 10h").title).isEqualTo("Comprar 12 ovos")
    }

    // ---- Intervalo com a primeira dose dita ----

    @Test
    fun intervaloComPrimeiraDoseNaoJogaAHoraFora() {
        // Antes: time=null e a nota pedia exatamente o horário que ela acabou de falar.
        val draft = parser.parse("tomar remédio a cada 12 horas começando amanhã às 8h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.missingFields).doesNotContain(MissingDraftField.TIME)
    }

    @Test
    fun intervaloSemPrimeiraDoseContinuaPedindo() {
        // Sem hora dita não há o que preencher: continua ambíguo e pedindo a primeira dose.
        val draft = parser.parse("remédio a cada duas horas")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.title).isEqualTo("Remédio")
        assertThat(draft.notes.joinToString()).contains("intervalo")
    }

    // ---- Review adversarial: o valor só quando a leitura é inequívoca ----

    @Test
    fun pontoQueNaoEMilharNaoViraValor() {
        // "30.50" não é 30,50 (decimal) nem 30.500 (milhar): as duas leituras são plausíveis e o
        // parser não escolhe por conta própria. Antes o "R$ 30.50" virava R$30,00 e o "30.50 reais"
        // virava R$50,00 — dinheiro inventado com a caixa rápida confirmando em silêncio.
        val cifrao = parser.parse("pagar R$ 30.50 amanhã às 10h")
        assertThat(cifrao.amountCents).isNull()
        assertThat(cifrao.title).contains("30.50")

        val porExtenso = parser.parse("pagar 30.50 reais amanhã às 10h")
        assertThat(porExtenso.amountCents).isNull()
        assertThat(porExtenso.title).contains("30.50")

        // O ponto de milhar (exatamente 3 dígitos) e a vírgula decimal continuam valendo.
        assertThat(parser.parse("pagar R$ 30,50 amanhã às 10h").amountCents).isEqualTo(3050L)
        assertThat(parser.parse("pagar R$ 0,50 amanhã às 10h").amountCents).isEqualTo(50L)
        assertThat(parser.parse("pagar 15.000,00 reais amanhã às 10h").amountCents).isEqualTo(1500000L)
    }

    @Test
    fun quantidadeComOMesmoDigitoDaHoraFicaNoTitulo() {
        // A quantidade e a hora com o MESMO dígito: é aqui que o contador por VALOR errava. O
        // "2h" gastava a única unidade do "2" e o filtro comia o "2 caixas" junto. A contagem é
        // por ocorrência (posição na frase), então o "h" não muda mais o resultado.
        listOf(
            "comprar 2 caixas às 2h amanhã",
            "comprar 2 caixas às 2 amanhã",
            "comprar 8 caixas às 8h amanhã",
            "comprar 4 caixas às 4 em ponto amanhã",
            "comprar 3 caixas às 3 da tarde amanhã",
        ).forEach { frase ->
            val quantidade = frase.split(" ")[1]
            assertThat(parser.parse(frase).title).isEqualTo("Comprar $quantidade caixas")
        }

        // A mesma frase com e sem o "h" tem que dar o mesmo título.
        assertThat(parser.parse("comprar 2 caixas às 2h amanhã").title)
            .isEqualTo(parser.parse("comprar 2 caixas às 2 amanhã").title)
    }

    @Test
    fun milhaoEscalaOMilhar() {
        // "um milhão e duzentos mil reais" gravava R$200.000,00: "milhão" não estava na alternância
        // nem no motor, então o casamento re-ancorava em "duzentos mil".
        val umMilhaoEDuzentosMil = parser.parse("pagar um milhão e duzentos mil reais amanhã às 10h")
        assertThat(umMilhaoEDuzentosMil.amountCents).isEqualTo(120000000L)
        assertThat(umMilhaoEDuzentosMil.title).isEqualTo("Pagar")

        val umMilhao = parser.parse("pagar um milhão de reais amanhã às 10h")
        assertThat(umMilhao.amountCents).isEqualTo(100000000L)
        assertThat(umMilhao.title).isEqualTo("Pagar")

        val meioMilhao = parser.parse("pagar meio milhão de reais amanhã às 10h")
        assertThat(meioMilhao.amountCents).isEqualTo(50000000L)
        assertThat(meioMilhao.title).isEqualTo("Pagar")

        val umEMeioMilhao = parser.parse("pagar 1,5 milhão de reais amanhã às 10h")
        assertThat(umEMeioMilhao.amountCents).isEqualTo(150000000L)
        assertThat(umEMeioMilhao.title).isEqualTo("Pagar")

        // O escalar em dígito ("15 mil") também é valor inequívoco.
        val quinzeMil = parser.parse("pagar 15 mil reais amanhã às 10h")
        assertThat(quinzeMil.amountCents).isEqualTo(1500000L)
        assertThat(quinzeMil.title).isEqualTo("Pagar")
    }

    @Test
    fun conectorSemCentavosNaoInventaValor() {
        // "tres reais e vinte": sem a palavra "centavos", o "e vinte" não é centavo e o valor não é
        // inequívoco. Antes gravava 300 e o "vinte" saía do título sem virar nada (perda dupla).
        val draft = parser.parse("pagar tres reais e vinte amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).contains("vinte")

        // Com a palavra "centavos" o casamento é fechado, e o valor é o que ela disse.
        assertThat(parser.parse("pagar quinze reais e cinquenta centavos amanhã às 10h").amountCents)
            .isEqualTo(1550L)
    }

    @Test
    fun meioMilViraQuinhentosReais() {
        // "meio mil reais" gravava R$1.000,00: o "meio" ficava fora do casamento e só o "mil reais"
        // virava valor — o dobro do que ela disse.
        val draft = parser.parse("pagar meio mil reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(50000L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun contosDeReisNaoViramDoisReais() {
        // "dois contos de réis" gravava R$2,00, com o "réis" sobrando no título. "Conto" não é a
        // unidade de reais (e "dois contos de fadas" não é dinheiro nenhum): o parser não escolhe
        // entre o conto antigo e o coloquial — devolve null e deixa o texto no título.
        val draft = parser.parse("pagar dois contos de réis amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).contains("contos")
    }

    @Test
    fun reaisDuplicadoDepoisDoCifraoNaoSobraNoTitulo() {
        // "R$ 1.234,56 reais": o "reais" escrito depois do cifrão ficava pendurado no título.
        val draft = parser.parse("pagar R$ 1.234,56 reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(123456L)
        assertThat(draft.title).isEqualTo("Pagar")
    }
}
