package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A classificação da fala em recado novo ou intenção de comando.
 *
 * Os casos que falham aqui são os da auditoria: hoje todos viram tarefa. "cancela o médico"
 * cria a tarefa "Cancela o médico" e ela acredita que cancelou. Os falsos positivos, do outro
 * lado, são o que a camada nova NÃO pode roubar: "Tomar remédio às 8" é uma tarefa de verdade e
 * continua sendo.
 */
class SpeechIntentTest {

    private fun intent(text: String) = SpeechIntentClassifier.classify(text)

    // --- os casos da auditoria -------------------------------------------------------

    @Test
    fun cancelaOMedicoViraComandoDeCancelar() {
        val intent = intent("cancela o médico")
        assertThat(intent).isInstanceOf(SpeechIntent.Cancel::class.java)
        // O alvo sai dobrado (sem acento): é a forma que o casamento com os títulos usa.
        assertThat((intent as SpeechIntent.Cancel).target).isEqualTo("medico")
    }

    @Test
    fun jaTomeiViraComandoDeConcluir() {
        val intent = intent("já tomei")
        assertThat(intent).isInstanceOf(SpeechIntent.Complete::class.java)
        // Sem alvo: "já tomei" sozinho não diz de quê — quem resolve o alvo é a matcher, e
        // sem nome ela não escolhe nada.
        assertThat((intent as SpeechIntent.Complete).target).isEmpty()
    }

    /**
     * "já tomei o remédio": o alvo sai do resto da frase, sem o artigo.
     */
    @Test
    fun jaTomeiO_RemedioTrazOAlvo() {
        val intent = intent("já tomei o remédio")
        assertThat((intent as SpeechIntent.Complete).target).isEqualTo("remedio")
    }

    /**
     * "apaga isso" é destrutivo e não aponta nome nenhum: o app NÃO pode escolher no chute.
     * Ele reconhece a intenção de apagar e diz que ainda não sabe fazer — o que ele não pode
     * é criar a tarefa "Apaga isso".
     */
    @Test
    fun apagaIssoEhReconhecidoENaoExecutado() {
        assertThat(intent("apaga isso"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.ERASE))
    }

    // --- "apaga o remédio": apagar pelo NOME, com verbo que também é tarefa --------------
    //
    // A fala mais provável dela — "apaga o remédio" — criava a tarefa "Apaga remédio" e ela
    // acreditava ter apagado. O verbo "apaga" sozinho não resolve: "apagar a luz" e "tirar o
    // lixo" são tarefas de verdade, e tratá-las como comando engoliria o recado. O que separa
    // os dois casos é a agenda (o alvo existe?), que o classificador puro não vê — por isso
    // ele devolve o alvo e NÃO decide o desfecho (ver [SpeechIntent.EraseNamed]): quem decide
    // é o `HomeViewModel`, com a agenda na mão.

    @Test
    fun apagaORemedioViraApagarPeloNome() {
        assertThat(intent("apaga o remédio")).isEqualTo(SpeechIntent.EraseNamed("remedio"))
    }

    @Test
    fun excluiAConsultaViraApagarPeloNome() {
        assertThat(intent("exclui a consulta")).isEqualTo(SpeechIntent.EraseNamed("consulta"))
    }

    @Test
    fun deletaAMissaViraApagarPeloNome() {
        assertThat(intent("deleta a missa")).isEqualTo(SpeechIntent.EraseNamed("missa"))
    }

    @Test
    fun tiraORemedioViraApagarPeloNome() {
        assertThat(intent("tira o remédio")).isEqualTo(SpeechIntent.EraseNamed("remedio"))
    }

    @Test
    fun removeAConsultaViraApagarPeloNome() {
        assertThat(intent("remove a consulta")).isEqualTo(SpeechIntent.EraseNamed("consulta"))
    }

    /**
     * "apaga a luz" NOMEIA o alvo — e é justamente por isso que ele não pode cair no ERASE
     * sem nome ("ainda não sei apagar falando"): a decisão de apagar ou capturar depende da
     * agenda, e é o `HomeViewModel` quem a toma. Aqui se prende só o que o classificador pode
     * saber: o alvo é "luz", e não um "apaga isso" disfarçado.
     */
    @Test
    fun apagaALuzTrazOAlvoEPedeAagenda() {
        assertThat(intent("apaga a luz")).isEqualTo(SpeechIntent.EraseNamed("luz"))
    }

    /**
     * O infinitivo continua captura, pela mesma razão do "cancelar a consulta": "me lembra de
     * apagar a luz" é um recado, não um imperativo dirigido ao app.
     */
    @Test
    fun apagarInfinitivoContinuaTarefa() {
        assertThat(intent("apagar a luz")).isEqualTo(SpeechIntent.Capture)
        assertThat(intent("me lembra de apagar a luz")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun oQueTenhoHojeViraPerguntaDeHoje() {
        assertThat(intent("o que tenho hoje?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    @Test
    fun oQueTenhoAmanhaViraPerguntaDeAmanha() {
        assertThat(intent("o que tenho amanhã?")).isEqualTo(SpeechIntent.Ask(AskWhen.TOMORROW))
    }

    @Test
    fun oQueTemHojeMarcadoViraPergunta() {
        assertThat(intent("o que tem hoje marcado?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    @Test
    fun quaisOsCompromissosDeHojeViraPergunta() {
        assertThat(intent("quais os compromissos de hoje?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    @Test
    fun temAlgoAmanhaViraPerguntaDeAmanha() {
        assertThat(intent("tem algo amanhã?")).isEqualTo(SpeechIntent.Ask(AskWhen.TOMORROW))
    }

    /**
     * "muda pra quinta" muda uma tarefa que já existe. O app ainda não faz isso, e o certo é
     * dizer que não sabe — não criar a tarefa "Muda pra", que era o que acontecia.
     */
    @Test
    fun mudaPraQuintaEhReconhecidoENaoExecutado() {
        assertThat(intent("muda pra quinta"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CHANGE))
    }

    @Test
    fun remarcaAConsultaEhReconhecidoENaoExecutado() {
        assertThat(intent("remarca a consulta"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CHANGE))
    }

    // --- falsos positivos: isto é captura --------------------------------------------

    @Test
    fun tomarRemedioAsOitoContinuaTarefa() {
        assertThat(intent("Tomar remédio às 8")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun consultaMedicaTercaContinuaTarefa() {
        assertThat(intent("Consulta médica terça")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun comprarRemedioContinuaTarefa() {
        assertThat(intent("Comprar remédio")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun tenhoQueTomarRemedioContinuaTarefa() {
        assertThat(intent("tenho que tomar remédio às 8")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun mudarOleoDoCarroContinuaTarefa() {
        assertThat(intent("mudar o óleo do carro")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun adiarAReuniaoContinuaTarefa() {
        assertThat(intent("adiar a reunião")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun cancelarAConsultaContinuaTarefa() {
        // "me lembra de cancelar a consulta" é a captura; o infinitivo não é imperativo.
        assertThat(intent("me lembra de cancelar a consulta")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun concluirAFaculdadeContinuaTarefa() {
        assertThat(intent("concluir a faculdade")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun pagarAContaContinuaTarefa() {
        assertThat(intent("pagar a conta de luz amanhã")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun textoVazioEhCaptura() {
        assertThat(intent("   ")).isEqualTo(SpeechIntent.Capture)
    }

    // --- a regra de ouro: na dúvida, captura -----------------------------------------

    @Test
    fun tenhoAlgoMarcadoComODentistaContinuaTarefa() {
        // "tenho algo" sozinho não pergunta: sem o dia/indefinido de pergunta, é afirmação.
        assertThat(intent("tenho algo marcado com o dentista")).isEqualTo(SpeechIntent.Capture)
    }

    // --- o gatilho só vale no COMEÇO da fala -----------------------------------------
    //
    // Estas são as frases em que a mesma palavra aparece no meio: ali ela é o verbo de outra
    // oração, não um imperativo dirigido ao app. Sem a âncora, "cancela" e "já paguei"
    // disparavam no meio e a camada apagava ou concluía uma tarefa de verdade — o pior
    // desfecho deste app.

    @Test
    fun cancelaComoVerboNoMeioDaFraseContinuaTarefa() {
        assertThat(intent("Perguntar se a médica cancela a consulta")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun verSeOPlanoCancelaAConsultaContinuaTarefa() {
        assertThat(intent("Ver se o plano cancela a consulta")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun ligarPraSaberSeCancelaContinuaTarefa() {
        assertThat(intent("Ligar pra clínica pra saber se cancela a consulta"))
            .isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun cancelaComoSubstantivoContinuaTarefa() {
        // "a cancela do estacionamento" é a cancela física (o objeto), não o verbo.
        assertThat(intent("Pagar a cancela do estacionamento")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun checarSeJaPagueiContinuaTarefa() {
        assertThat(intent("Checar se já paguei o aluguel")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun confirmarSeJaTomeiContinuaTarefa() {
        assertThat(intent("Confirmar se já tomei o remédio da manhã")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun concluiAFaculdadeContinuaTarefa() {
        // "conclui a faculdade em dezembro" é o que ela QUER registrar. No começo da frase
        // "conclui" ainda é ambíguo (o presente "ele conclui" e o imperativo são iguais), e
        // na dúvida a regra é captura.
        assertThat(intent("Conclui a faculdade em dezembro")).isEqualTo(SpeechIntent.Capture)
    }

    // --- o preâmbulo de cortesia não quebra o comando ---------------------------------

    @Test
    fun porFavorNaoImpedeOCancelamento() {
        // "por favor" é o único material tolerado antes do gatilho: ela fala assim.
        val intent = intent("por favor, cancela o médico")
        assertThat(intent).isInstanceOf(SpeechIntent.Cancel::class.java)
        assertThat((intent as SpeechIntent.Cancel).target).isEqualTo("medico")
    }

    @Test
    fun porFavorNaoImpedeAPergunta() {
        assertThat(intent("por favor, o que tenho hoje?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    // --- a pergunta da agenda também não rouba tarefa --------------------------------

    @Test
    fun tarefaQueContemOQueTemNaListaContinuaTarefa() {
        // "o que tem" no meio é o objeto da lista, não uma pergunta sobre a agenda.
        assertThat(intent("Comprar o que tem na lista amanhã às 10")).isEqualTo(SpeechIntent.Capture)
    }

    // --- "cancela isso" é apagar sem nome --------------------------------------------

    @Test
    fun cancelaIssoEhReconhecidoENaoExecutado() {
        // "cancela isso" aponta para o que ela vê na tela e não nomeia a tarefa: o app diz
        // que ainda não sabe apagar falando, em vez de procurar um alvo que não foi dito.
        assertThat(intent("cancela isso")).isEqualTo(SpeechIntent.Unknown(UnsupportedKind.ERASE))
    }

    @Test
    fun desmarcaIssoEhReconhecidoENaoExecutado() {
        // "desmarca" é sinônimo de cancelar e tem de cair no MESMO caminho de "cancela isso":
        // sem isto ele virava um cancelamento com o alvo literal "isso" e respondia
        // "Não achei nenhuma tarefa com esse nome."
        assertThat(intent("desmarca isso")).isEqualTo(SpeechIntent.Unknown(UnsupportedKind.ERASE))
    }

    @Test
    fun desmarqueIssoEhReconhecidoENaoExecutado() {
        assertThat(intent("desmarque isso")).isEqualTo(SpeechIntent.Unknown(UnsupportedKind.ERASE))
    }

    // --- a abertura tolera as interjeições e cortesias de quem fala --------------------

    @Test
    fun ahNaoImpedeOCancelamento() {
        val intent = intent("Ah, cancela o médico")
        assertThat(intent).isInstanceOf(SpeechIntent.Cancel::class.java)
        assertThat((intent as SpeechIntent.Cancel).target).isEqualTo("medico")
    }

    @Test
    fun bomNaoImpedeOCancelamento() {
        assertThat(intent("Bom, cancela o médico")).isInstanceOf(SpeechIntent.Cancel::class.java)
    }

    @Test
    fun porGentilezaNaoImpedeOCancelamento() {
        assertThat(intent("Por gentileza, cancela o médico"))
            .isInstanceOf(SpeechIntent.Cancel::class.java)
    }

    @Test
    fun vePraMimNaoImpedeOCancelamento() {
        assertThat(intent("Vê pra mim, cancela o médico"))
            .isInstanceOf(SpeechIntent.Cancel::class.java)
    }

    // --- "tenho algo" e as perguntas por substantivo também ancoram --------------------

    @Test
    fun tenhoAlgoMarcadoComODentistaAmanhaContinuaTarefa() {
        // "tenho algo ... amanhã" no MEIO ("tenho algo marcado com o dentista amanhã") é uma
        // afirmação sobre a tarefa, não uma pergunta: sem a âncora ela viraria Ask e a tarefa
        // nunca nasceria.
        assertThat(intent("tenho algo marcado com o dentista amanhã")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun tenhoAlgoPraFazerAmanhaContinuaTarefa() {
        assertThat(intent("Tenho algo pra fazer amanhã")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun temAlgoAmanhaSemOOQueContinuaPergunta() {
        // O que o "tem algo" acrescenta é a pergunta SEM o "o que": "tem algo amanhã?" continua
        // sendo pergunta mesmo ancorado.
        assertThat(intent("tem algo amanhã?")).isEqualTo(SpeechIntent.Ask(AskWhen.TOMORROW))
    }

    @Test
    fun comprarQualTarefaEstaFaltandoContinuaTarefa() {
        // "qual tarefa" no meio é o objeto da compra, não uma pergunta sobre a agenda.
        assertThat(intent("Comprar qual tarefa está faltando")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun perguntarQualOCompromissoContinuaTarefa() {
        assertThat(intent("Perguntar qual o compromisso de sexta")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun pedirPraElaMeFalaODiaContinuaTarefa() {
        assertThat(intent("Pedir pra ela me fala o dia")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun quaisOsCompromissosAindaEhPergunta() {
        // A forma que abre a fala continua pergunta — a âncora não pode matar o caso legítimo.
        assertThat(intent("quais os compromissos de hoje?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    /**
     * O reconhecedor devolve a hipótese pontuada com frequência, e a pontuação grudava no alvo:
     * "já tomei o remédio." dava o alvo `remedio.`, que a matcher — comparando palavra com
     * palavra — não achava. A rotina do remédio seguia pendente e ela achava que tinha
     * registrado. O `LocalTaskParser` já tirava essa pontuação do título; a camada nova não.
     */
    @Test
    fun pontoFinalNaoFicaNoAlvo() {
        assertThat(intent("já tomei o remédio.")).isEqualTo(SpeechIntent.Complete("remedio"))
        assertThat(intent("cancela o dentista.")).isEqualTo(SpeechIntent.Cancel("dentista"))
    }

    @Test
    fun cortesiaDepoisDaVirgulaNaoEntraNoAlvo() {
        // "cancela o médico, por favor": a vírgula colava na palavra e o alvo virava
        // "medico, por favor" — nada casava.
        assertThat(intent("cancela o médico, por favor")).isEqualTo(SpeechIntent.Cancel("medico"))
    }

    /**
     * "eu" e "hoje" abrem a fala mais natural de uma rotina. Sem eles no preâmbulo, a âncora de
     * "já <verbo>" não via o gatilho e a frase virava a tarefa "Eu já tomei o remédio".
     */
    @Test
    fun sujeitoEHojeNaoEscondemOJa() {
        assertThat(intent("eu já tomei o remédio")).isEqualTo(SpeechIntent.Complete("remedio"))
        assertThat(intent("hoje já tomei o remédio")).isEqualTo(SpeechIntent.Complete("remedio"))
        assertThat(intent("eu já paguei o aluguel")).isEqualTo(SpeechIntent.Complete("aluguel"))
    }
}
