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

    // ---- Terceiro review: o ramo do escalar "N mil" ----

    @Test
    fun milSemUnidadeDeDinheiroNaoInventaValor() {
        // O ramo do escalar em dígito disparava sem "reais"/"R$" e multiplicava por 100 um valor
        // que JÁ estava em centavos (o `centsFromNumber` devolve centavos): "pagar 5 mil" gravava
        // R$500.000,00, 100× o certo, com `canQuickConfirm=true` — a caixa rápida confirmava em
        // silêncio. E as METAS de caminhada ganhavam dinheiro que ela não falou: "caminhar 5 mil
        // passos" → R$500.000,00, "correr 10 mil" → R$1.000.000,00.
        val cincoMil = parser.parse("pagar 5 mil amanhã às 10h")
        assertThat(cincoMil.amountCents).isNull()
        assertThat(cincoMil.title).contains("mil")

        val caminhada = parser.parse("caminhar 5 mil passos amanhã às 10h")
        assertThat(caminhada.amountCents).isNull()
        assertThat(caminhada.title).contains("passos")

        val corrida = parser.parse("correr 10 mil amanhã às 10h")
        assertThat(corrida.amountCents).isNull()
        assertThat(corrida.title).isEqualTo("Correr 10 mil")

        assertThat(parser.parse("andar 3 mil passos amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("3 mil km amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("meio milhao de pessoas amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("pagar meio milhão de pessoas amanhã às 10h").amountCents).isNull()

        // A caixa rápida ainda confirma a frase completa — o que não pode é o número inventado.
        assertThat(cincoMil.canQuickConfirm(clock.instant(), zone)).isTrue()
    }

    @Test
    fun dozeMilSemReaisNaoViraUmMilhaoEDuzentosMil() {
        // Medido no review: "pagar 12 mil no medico" gravava R$1.200.000,00 com a caixa rápida
        // confirmando em silêncio. Sem a unidade dita o valor não existe — e o "12 mil" fica no
        // título, que é o único lugar em que a palavra dela pode estar.
        val draft = parser.parse("pagar 12 mil no medico amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).contains("12 mil")
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
    }

    @Test
    fun milComReaisContinuaGravandoOValorCerto() {
        // A mesma intenção dita com "reais" tem que dar o MESMO valor da sem "reais" — ou nenhuma
        // das duas. O certo vem do ramo que exige a unidade: `centsFromNumber` já devolve centavos
        // e o escalar multiplica por mil; o `* 100` extra era o fator de 100 entre as duas.
        assertThat(parser.parse("pagar 5 mil reais amanhã às 10h").amountCents).isEqualTo(500000L)
        assertThat(parser.parse("pagar 1 mil reais amanhã às 10h").amountCents).isEqualTo(100000L)
        assertThat(parser.parse("pagar 12 mil reais amanhã às 10h").amountCents).isEqualTo(1200000L)
        assertThat(parser.parse("pagar 999 mil reais amanhã às 10h").amountCents).isEqualTo(99900000L)
        assertThat(parser.parse("pagar 5 mil de reais amanhã às 10h").amountCents).isEqualTo(500000L)

        // O cifrão no lugar do "reais": o "mil" continua escalando o número.
        assertThat(parser.parse("pagar R$ 5 mil amanhã às 10h").amountCents).isEqualTo(500000L)
        assertThat(parser.parse("pagar R$ 12 mil amanhã às 10h").amountCents).isEqualTo(1200000L)

        // O produto que estoura o `Long` não derruba o parse nem inventa: o valor fica nulo.
        assertThat(parser.parse("pagar 999999999 mil reais amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("pagar 999.999.999.999.999 mil reais amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("pagar 999.999.999.999.999 milhoes de reais amanhã às 10h").amountCents)
            .isNull()
    }

    @Test
    fun escalarEmDigitoNaoRoubaOValorDaFraseComReais() {
        // "1000 mil reais" (mil mil = R$1.000.000,00) era lido como R$1.000,00: o ramo do escalar
        // não ancorava o "reais" que vinha depois, vencia o ramo que exige a unidade e ainda
        // multiplicava por 100. Agora quem lê é o ramo que exige "reais", e o valor é o dito.
        val milMil = parser.parse("pagar 1000 mil reais amanhã às 10h")
        assertThat(milMil.amountCents).isEqualTo(100000000L)
        assertThat(milMil.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar 1500 mil reais amanhã às 10h").amountCents).isEqualTo(150000000L)
    }

    // ---- Terceiro review: a palavra de número casando por prefixo ----

    @Test
    fun palavraDeNumeroNaoCasaPorPrefixo() {
        // A alternância das palavras de número era montada sem `\b` em volta de cada uma: "pagar
        // meio reais" casava "meio" como número e "reais" como unidade, e gravava amountCents=0 —
        // dinheiro inventado. O "meio real" dela são R$0,50, e "meio" sozinho não é número.
        val meioReais = parser.parse("pagar meio reais amanhã às 10h")
        assertThat(meioReais.amountCents).isNull()
        assertThat(meioReais.title).contains("reais")

        // "meio real" (singular) continua sendo cinquenta centavos.
        assertThat(parser.parse("pagar meio real amanhã às 10h").amountCents).isEqualTo(50L)
    }

    @Test
    fun meioRealComConectorNaoInventaCinquentaCentavos() {
        // "meio real e vinte": o "e vinte" é o mesmo conector que o PR recusa em "tres reais e
        // vinte" — sem a palavra "centavos" fechando, não é inequívoco. O ramo do "meio real"
        // devolvia 50 calado e ainda deixava o "e vinte" no título.
        val draft = parser.parse("pagar meio real e vinte amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).contains("vinte")
    }

    // ---- Terceiro review (P3): o inteiro de quatro dígitos sem separador ----

    @Test
    fun digitoRecusadoAntesDoExtensoNaoViraValor() {
        // "30.50 mil reais": o extenso re-ancorava no "mil reais" que vinha DEPOIS do número que o
        // próprio valor recusou (o ponto não é milhar) e gravava R$1.000,00 — dinheiro que ela não
        // falou. O dígito recusado não pode virar valor pelo pedaço que sobrou.
        val draft = parser.parse("pagar 30.50 mil reais amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).contains("30.50")
    }

    @Test
    fun numeroGrandeDemaisNaoDerrubaNemInventa() {
        // O `toLong()` do `BigDecimal` estoura em número de 20 dígitos (exceção, não nulo) e
        // derrubava a interpretação inteira da frase. Acima do teto o valor fica nulo e o texto
        // vai para o título.
        val vinteDigitos = parser.parse("pagar 99999999999999999999 reais amanhã às 10h")
        assertThat(vinteDigitos.amountCents).isNull()
        assertThat(vinteDigitos.title).contains("99999999999999999999")

        assertThat(parser.parse("pagar 99999999999999999999999999999999 reais amanhã às 10h").amountCents)
            .isNull()
        assertThat(parser.parse("pagar 999.999.999.999.999.999.999 reais amanhã às 10h").amountCents)
            .isNull()

        // A fronteira do teto: 15 dígitos ainda são lidos (e não estouram o `Long`).
        assertThat(parser.parse("pagar 999.999.999.999.999 reais amanhã às 10h").amountCents)
            .isEqualTo(99999999999999900L)
        // Um dígito além do teto: nulo, e o texto fica no título.
        assertThat(parser.parse("pagar 1.999.999.999.999.999 reais amanhã às 10h").amountCents).isNull()

        // Sem separador, o padrão do número só aceita até 4 dígitos: o inteiro longo é ambíguo.
        assertThat(parser.parse("pagar 999999999 reais amanhã às 10h").amountCents).isNull()
    }

    @Test
    fun quatroDigitosSemSeparadorViraValor() {
        // "1500 reais" ficava sem valor: o número só casava milhar COM ponto ("1.500") e o
        // `centsFromNumber` recusava inteiro de 4 dígitos. Quatro dígitos sem separador não têm
        // outra leitura — não há ambiguidade a preservar.
        assertThat(parser.parse("pagar 1500 reais amanhã às 10h").amountCents).isEqualTo(150000L)
        assertThat(parser.parse("pagar R$ 1500 amanhã às 10h").amountCents).isEqualTo(150000L)
        assertThat(parser.parse("pagar 9999 reais amanhã às 10h").amountCents).isEqualTo(999900L)
        assertThat(parser.parse("pagar 1234 reais amanhã às 10h").amountCents).isEqualTo(123400L)
        assertThat(parser.parse("pagar 1500,00 reais amanhã às 10h").amountCents).isEqualTo(150000L)

        // O ponto que não é milhar (F-A) e o milhar por ponto ficam como estavam.
        assertThat(parser.parse("pagar R$ 30.50 amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("pagar 30.50 reais amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("pagar 15.000 reais amanhã às 10h").amountCents).isEqualTo(1500000L)
        assertThat(parser.parse("pagar 1.234,56 reais amanhã às 10h").amountCents).isEqualTo(123456L)
        assertThat(parser.parse("pagar 30,50 reais amanhã às 10h").amountCents).isEqualTo(3050L)

        // Cinco dígitos sem separador seguem sem leitura: podem ser milhar malformado.
        assertThat(parser.parse("pagar 12345 reais amanhã às 10h").amountCents).isNull()
    }

    @Test
    fun milharPorPontoComVariosGruposViraValor() {
        // "1.234.567 reais" ficava sem valor: a checagem do ponto olhava tudo depois do PRIMEIRO
        // ponto ("234.567", 7 caracteres) e recusava qualquer número com mais de um grupo. O que
        // define o milhar é o ÚLTIMO grupo ter exatamente 3 dígitos.
        assertThat(parser.parse("pagar 12.345 reais amanhã às 10h").amountCents).isEqualTo(1234500L)
        assertThat(parser.parse("pagar 1.234.567 reais amanhã às 10h").amountCents).isEqualTo(123456700L)
        assertThat(parser.parse("pagar R$ 1.234.567,89 amanhã às 10h").amountCents).isEqualTo(123456789L)

        // O ponto que não fecha grupo de 3 continua recusado.
        assertThat(parser.parse("pagar 1.23 reais amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("pagar 12.3456 reais amanhã às 10h").amountCents).isNull()
    }

    // ---- Oráculo: todo valor falado vira o número certo, ou null com a palavra no título ----

    @Test
    fun oraculoDoValorFalado() {
        class Caso(
            val frase: String,
            /** O número que gerou a frase, por construção — nunca lido do parser. */
            val esperado: Long?,
            val palavras: List<String>,
            /** `true`: tem que ser exatamente [esperado]. `false`: [esperado] ou `null`. */
            val obrigatorio: Boolean,
        )

        val casos = mutableListOf<Caso>()

        // "N reais" / "R$ N": o valor é o número dito, em centavos.
        for (n in 1..9999) {
            val esperado = n.toLong() * 100
            casos += Caso("pagar $n reais amanhã às 10h", esperado, listOf("reais"), true)
            casos += Caso("pagar R$ $n amanhã às 10h", esperado, listOf("r$"), true)
        }
        // Cinco dígitos sem separador: ambíguo, o valor não sai.
        for (n in 10000..10050) {
            casos += Caso("pagar $n reais amanhã às 10h", null, listOf("reais"), true)
            casos += Caso("pagar R$ $n amanhã às 10h", null, listOf("r$"), true)
        }
        // Milhar por ponto, um e vários grupos.
        for (a in 1..9) {
            for (b in listOf(0, 123, 500, 999)) {
                val mmm = b.toString().padStart(3, '0')
                casos += Caso("pagar $a.$mmm reais amanhã às 10h", (a.toLong() * 1000 + b) * 100, listOf("reais"), true)
                casos += Caso("pagar R$ $a.$mmm amanhã às 10h", (a.toLong() * 1000 + b) * 100, listOf("r$"), true)
                for (c in listOf(1, 500)) {
                    val ccc = c.toString().padStart(3, '0')
                    val valor = ((a.toLong() * 1000 + b) * 1000 + c) * 100
                    casos += Caso("pagar $a.$mmm.$ccc reais amanhã às 10h", valor, listOf("reais"), true)
                }
            }
        }
        // Decimal por vírgula.
        for (n in 1..999) {
            for (m in listOf(1, 5, 50, 99)) {
                val mm = m.toString().padStart(2, '0')
                casos += Caso("pagar $n,$mm reais amanhã às 10h", n.toLong() * 100 + m, listOf("reais"), true)
                casos += Caso("pagar R$ $n,$mm amanhã às 10h", n.toLong() * 100 + m, listOf("r$"), true)
            }
        }
        // "N reais e M centavos".
        for (n in 1..999) {
            for (m in listOf(1, 5, 50, 99)) {
                casos += Caso(
                    "pagar $n reais e $m centavos amanhã às 10h",
                    n.toLong() * 100 + m,
                    listOf("reais", "centavos"),
                    true,
                )
            }
        }
        // Por extenso.
        listOf(
            "um" to 1L, "dois" to 2L, "cinco" to 5L, "dez" to 10L, "quinze" to 15L, "vinte" to 20L,
            "cem" to 100L, "cento e vinte e cinco" to 125L, "duzentos e cinquenta" to 250L,
            "mil" to 1000L, "mil e duzentos" to 1200L, "dois mil e quinhentos" to 2500L,
            "um milhão" to 1000000L, "um milhão e duzentos mil" to 1200000L,
        ).forEach { (palavra, valor) ->
            casos += Caso("pagar $palavra reais amanhã às 10h", valor * 100, listOf("reais"), true)
        }
        // "meio": o multiplicador da escala, nunca um valor.
        casos += Caso("pagar meio mil reais amanhã às 10h", 50000L, listOf("reais"), true)
        casos += Caso("pagar meio milhão de reais amanhã às 10h", 50000000L, listOf("reais"), true)
        casos += Caso("pagar meio mil amanhã às 10h", 50000L, listOf("mil"), false)
        casos += Caso("pagar meio milhão amanhã às 10h", 50000000L, listOf("milhao"), false)
        casos += Caso("pagar meio milhão de pessoas amanhã às 10h", null, listOf("milhao"), true)
        casos += Caso("meio milhao de pessoas amanhã às 10h", null, listOf("milhao"), true)
        casos += Caso("pagar meio reais amanhã às 10h", null, listOf("reais"), true)
        // "N milhão" com e sem a unidade.
        for (n in listOf(1, 2, 5)) {
            casos += Caso("pagar $n milhão de reais amanhã às 10h", n.toLong() * 100000000, listOf("reais"), true)
            casos += Caso("pagar $n milhão amanhã às 10h", n.toLong() * 100000000, listOf("milhao"), false)
        }
        // "N mil reais" (unidade dita) e "N mil" (sem unidade): ou o valor certo, ou null.
        for (n in 1..999) {
            casos += Caso("pagar $n mil reais amanhã às 10h", n.toLong() * 100000, listOf("reais"), true)
            casos += Caso("pagar R$ $n mil amanhã às 10h", n.toLong() * 100000, listOf("r$"), true)
            casos += Caso("pagar $n mil amanhã às 10h", n.toLong() * 100000, listOf("mil"), false)
        }
        for (n in listOf(1000, 1500, 2000, 9999)) {
            casos += Caso("pagar $n mil reais amanhã às 10h", n.toLong() * 100000, listOf("reais"), true)
            casos += Caso("pagar $n mil amanhã às 10h", null, listOf("mil"), false)
        }
        // Metas e quantidades com "mil": dinheiro NENHUM.
        listOf("caminhar 5 mil passos", "correr 10 mil", "andar 3 mil passos", "3 mil km").forEach { frase ->
            casos += Caso("$frase amanhã às 10h", null, listOf("mil"), true)
        }
        for (n in 1..200) {
            casos += Caso("caminhar $n mil passos amanhã às 10h", null, listOf("passos"), true)
            casos += Caso("correr $n mil amanhã às 10h", null, listOf("mil"), true)
            casos += Caso("$n mil km amanhã às 10h", null, listOf("km"), true)
            casos += Caso("andar $n mil passos amanhã às 10h", null, listOf("passos"), true)
        }
        // "N mil reais" com N de quatro dígitos é "N mil" reais: "1000 mil reais" = R$1.000.000,00
        // (o "mil mil" dito), e o valor é o mesmo que o número em dígito escalado dá.
        casos += Caso("pagar 1000 mil reais amanhã às 10h", 100000000L, listOf("reais"), true)
        casos += Caso("pagar 1500 mil reais amanhã às 10h", 150000000L, listOf("reais"), true)
        // Ambiguidade que o PR já recusa.
        casos += Caso("pagar R$ 30.50 amanhã às 10h", null, listOf("30.50"), true)
        casos += Caso("pagar 30.50 reais amanhã às 10h", null, listOf("30.50"), true)
        casos += Caso("pagar 30.50 mil reais amanhã às 10h", null, listOf("30.50"), true)
        casos += Caso("pagar 1.23 reais amanhã às 10h", null, listOf("1.23"), true)
        casos += Caso("pagar 12.3456 reais amanhã às 10h", null, listOf("12.3456"), true)
        // Número grande demais para o `Long`: não derruba nem inventa.
        casos += Caso("pagar 99999999999999999 reais amanhã às 10h", null, listOf("99999999999999999"), true)
        casos += Caso("pagar 999.999.999.999.999.999 reais amanhã às 10h", null, listOf("999"), true)
        casos += Caso("pagar 999.999.999.999.999 reais amanhã às 10h", 99999999999999900L, listOf("reais"), true)
        casos += Caso("pagar 1.999.999.999.999.999 reais amanhã às 10h", null, listOf("999"), true)
        // O extenso não resgata um dígito que o próprio valor recusou.
        casos += Caso("pagar 12.3456 mil reais amanhã às 10h", null, listOf("12.3456"), true)
        casos += Caso("pagar 1.23 mil reais amanhã às 10h", null, listOf("1.23"), true)
        // Produto do escalar que estoura o `Long`.
        casos += Caso("pagar 999999999 mil reais amanhã às 10h", null, listOf("mil"), true)
        casos += Caso("pagar 9999999999 milhoes de reais amanhã às 10h", null, listOf("milhoes"), true)

        val violacoes = mutableListOf<String>()
        casos.forEach { caso ->
            val draft = parser.parse(caso.frase)
            val obtido = draft.amountCents
            val tituloDobrado = java.text.Normalizer.normalize(draft.title.lowercase(), java.text.Normalizer.Form.NFD)
                .replace("\\p{M}+".toRegex(), "")
            val palavrasOk = caso.palavras.all { tituloDobrado.contains(it) }
            val tipo = when {
                // O valor dito: tem que ser exatamente o número que gerou a frase.
                caso.obrigatorio && obtido != caso.esperado -> "número"
                // Sem unidade de dinheiro: ou o valor certo, ou nenhum — nunca um inventado.
                !caso.obrigatorio && obtido != null && obtido != caso.esperado -> "número"
                // Sem valor, a palavra dela tem que estar no título — nada some calado.
                obtido == null && !palavrasOk -> "palavra"
                else -> null
            }
            if (tipo != null && violacoes.size < 40) {
                violacoes += "$tipo|${caso.frase}|obtido=$obtido|esperado=${caso.esperado}|" +
                    "obrig=${caso.obrigatorio}|title='${draft.title}'"
            }
        }
        println("ORACULO|total=${casos.size}|violacoes=${violacoes.size}")
        assertThat(violacoes).isEmpty()
    }
}
