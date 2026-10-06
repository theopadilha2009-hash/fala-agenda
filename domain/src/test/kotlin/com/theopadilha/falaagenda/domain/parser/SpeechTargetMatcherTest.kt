package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O casamento do alvo falado com os títulos da agenda.
 *
 * O caso que importa é o de dois "remédio": escolher um no chute e cancelar o errado é pior
 * que não cancelar nada. A matcher nunca escolhe — ela devolve [SpeechTargetResolution.Ambiguous]
 * e quem executa pergunta.
 */
class SpeechTargetMatcherTest {

    private fun candidates(vararg titles: String) =
        titles.mapIndexed { i, t -> SpeechCandidate(id = "id$i", title = t) }

    /**
     * O caminho REAL da fala: `classify` decide a intenção e produz o alvo, `resolve` casa o
     * alvo com a agenda. Chamar a matcher com a frase crua esconde o que o parser já tirou (o
     * gatilho, o artigo) — e foi um alvo irreal desses que sustentou o ramo composite.
     */
    private fun resolveBySpeech(fala: String, vararg titles: String): SpeechTargetResolution {
        val target = when (val intent = SpeechIntentClassifier.classify(fala)) {
            is SpeechIntent.Complete -> intent.target
            is SpeechIntent.Cancel -> intent.target
            else -> error("A fala não virou comando de concluir/cancelar: $intent")
        }
        return SpeechTargetMatcher.resolve(target, candidates(*titles))
    }

    @Test
    fun umUnicoTituloCasa() {
        val r = SpeechTargetMatcher.resolve("médico", candidates("Consulta médica"))
        assertThat(r).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun doisRemediosSaoAmbiguos() {
        val r = SpeechTargetMatcher.resolve(
            "remédio",
            candidates("Tomar remédio", "Comprar remédio"),
        )
        assertThat(r).isInstanceOf(SpeechTargetResolution.Ambiguous::class.java)
        assertThat((r as SpeechTargetResolution.Ambiguous).ids).containsExactly("id0", "id1")
    }

    @Test
    fun nomeQueNaoExisteNaoCasa() {
        val r = SpeechTargetMatcher.resolve("dentista", candidates("Consulta médica"))
        assertThat(r).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun oTituloInteiroDentroDoAlvoCasa() {
        // O alvo REAL que o parser produz em "cancela a consulta médica de amanhã": o gatilho e
        // o artigo saem, "amanhã" é temporal e não conta. Chega como "consulta medica de
        // amanha" e casa pela MAIORIA ESTRITA (2 de 2) — sem precisar do ramo composite, que
        // era sustentado por este teste passando a frase crua.
        assertThat(resolveBySpeech("cancela a consulta médica de amanhã", "Consulta médica"))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun raizEmComumCasaMesmoComAcentoDiferente() {
        // "medico" (falado) e "médica" (título) compartilham a raiz "medic".
        val r = SpeechTargetMatcher.resolve("medico", candidates("Consulta médica"))
        assertThat(r).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoCurtoDemaisNaoCasa() {
        // "a" não pode casar com tudo — o piso de tamanho existe para isso.
        assertThat(SpeechTargetMatcher.resolve("a", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun raizNoMeioDaPalavraNaoCasa() {
        // "dia" e "remédio" compartilham "dio" no meio — não é a mesma palavra.
        assertThat(SpeechTargetMatcher.resolve("dia", candidates("Tomar remédio")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun semCandidatosNaoCasa() {
        assertThat(SpeechTargetMatcher.resolve("médico", emptyList()))
            .isEqualTo(SpeechTargetResolution.None)
    }

    // --- a raiz em comum não pode ser um prefixo curto qualquer -----------------------
    //
    // Quatro letras iguais no começo de duas palavras DIFERENTES não são a mesma palavra:
    // "carro" e "carregador" começam as duas com "carr". Casar por prefixo curto concluía ou
    // apagava a tarefa errada.

    @Test
    fun carroNaoCasaCarregador() {
        assertThat(SpeechTargetMatcher.resolve("carro", candidates("Carregador do celular")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun luzNaoCasaLuzia() {
        assertThat(SpeechTargetMatcher.resolve("luz", candidates("Luzia, aniversário")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun contaNaoCasaContrato() {
        // "conta" e "contrato" só compartilham "cont": não é a mesma palavra.
        assertThat(SpeechTargetMatcher.resolve("conta", candidates("Contrato do aluguel")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun remedioCasaRemedioInteiro() {
        // O que o casamento precisa cobrir de verdade continua casando: uma palavra inteira
        // igual à do título.
        assertThat(SpeechTargetMatcher.resolve("remédio", candidates("Remédio do cachorro")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    /**
     * A raiz flexiva continua valendo: "médico" falado casa "Consulta médica" no título, e
     * "médica" no título casa "médico" no alvo — o reconhecimento de fala erra gênero.
     */
    @Test
    fun raizFlexivaAindaCasa() {
        assertThat(SpeechTargetMatcher.resolve("medico", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    // --- prefixo de 5 letras não é a mesma palavra ------------------------------------
    //
    // Radicais DIFERENTES que começam igual não podem casar: com um alvo só na agenda, o
    // casamento é `One` e a ação acontece calada — conclui ou apaga a tarefa errada.

    @Test
    fun medicoNaoCasaMedicamento() {
        // O caso provável na agenda dela: "médico" e "medicamento" só compartilham "medic".
        assertThat(SpeechTargetMatcher.resolve("medico", candidates("Tomar medicamento")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun contaNaoCasaContador() {
        assertThat(SpeechTargetMatcher.resolve("conta", candidates("Contador de água")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun carroNaoCasaCarroca() {
        assertThat(SpeechTargetMatcher.resolve("carro", candidates("Carroça do vizinho")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun carteiraNaoCasaCarteirinha() {
        assertThat(SpeechTargetMatcher.resolve("carteira", candidates("Carteirinha do ônibus")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    // --- o que DEVE continuar casando -------------------------------------------------

    @Test
    fun palavraInteiraAindaCasa() {
        assertThat(SpeechTargetMatcher.resolve("consulta", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun flexaoRegularAindaCasa() {
        // "remedio" (falado, sem acento) e "remédio" (título) são a mesma palavra.
        assertThat(SpeechTargetMatcher.resolve("remedio", candidates("Remédio do cachorro")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    // --- alvo de VÁRIAS palavras: a maioria estrita tem de casar ----------------------
    //
    // Com UMA tarefa só na agenda, um alvo que compartilhava uma única palavra genérica
    // virava `One` e a ação acontecia calada — apagava ou concluía a tarefa errada. "cancela
    // o remédio do cachorro" apagava "Passear com o cachorro"; "já tomei o remédio do
    // cachorro" concluía o passeio. O alvo tem de casar com a TAREFA, não com uma palavra
    // solta dela. Uma palavra só continua casando como sempre (o caso mais comum).

    @Test
    fun alvoDeUmaPalavraContinuaCasando() {
        assertThat(SpeechTargetMatcher.resolve("medico", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoDeTresPalavrasComUmaTemporalCasa() {
        // 2 de 3 significativas casam ("de" não conta): é a maioria, e o nome composto está
        // lá dentro. É o que o parser entrega em "cancela a consulta médica de amanhã".
        assertThat(
            SpeechTargetMatcher.resolve("consulta medica de amanha", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun remedioDoCachorroNaoApagaOPasseio() {
        // "remedio do cachorro": 1 de 2 significativas casa (só "cachorro"). NÃO é a tarefa.
        assertThat(
            SpeechTargetMatcher.resolve(
                "remedio do cachorro",
                candidates("Passear com o cachorro"),
            ),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun consultaDoDentistaNaoApagaAConsultaMedica() {
        // "consulta do dentista": 1 de 2 significativas casa (só "consulta"). NÃO é a médica.
        assertThat(
            SpeechTargetMatcher.resolve("consulta do dentista", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun alvoDeDuasPalavrasComSoUmaCasandoNaoCasa() {
        // Mesmo com a segunda palavra sendo do mesmo campo ("medico do coracao"), 1 de 2 não
        // é maioria — na dúvida, `None` é o desfecho seguro.
        assertThat(
            SpeechTargetMatcher.resolve("medico do coracao", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun alvoDeTresPalavrasComSoUmaCasandoNaoCasa() {
        assertThat(
            SpeechTargetMatcher.resolve(
                "remedio do cachorro amanha",
                candidates("Passear com o cachorro"),
            ),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    // --- o que continua casando com várias palavras ----------------------------------

    @Test
    fun alvoComOTituloInteiroCasa() {
        assertThat(
            SpeechTargetMatcher.resolve("remedio do cachorro", candidates("Remédio do cachorro")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoDeDuasPalavrasAsDuasCasandoCasa() {
        assertThat(
            SpeechTargetMatcher.resolve("tomar remedio", candidates("Tomar remédio em jejum")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun pluralEGeneroNoAlvoInteiroCasa() {
        // "consultas medicas" (falado) casa "Consulta médica": as duas palavras flexionam.
        assertThat(
            SpeechTargetMatcher.resolve("consultas medicas", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoDeTresPalavrasComTemporalNoFimCasa() {
        assertThat(
            SpeechTargetMatcher.resolve("tomar remedio hoje", candidates("Tomar remédio")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun tituloInteiroComTemporalNoAlvoCasa() {
        // O título inteiro está no alvo; a palavra a mais (tempo) não pode derrubar o casamento.
        assertThat(
            SpeechTargetMatcher.resolve(
                "remedio do cachorro amanha",
                candidates("Remédio do cachorro"),
            ),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    // --- P1: o ramo composite reabria o buraco destrutivo, pelo caminho REAL -------------
    //
    // Com o título de UMA palavra significativa, o ramo "título inteiro dentro do alvo" casa
    // sempre que aquela palavra aparece no alvo — ele era MAIS permissivo que a maioria
    // estrita. "já tomei o remédio do dentista" com [Dentista, Tomar remédio] na agenda virava
    // `One(Dentista)` e o app concluía "Dentista" respondendo "Feito." — exatamente a ação
    // destrutiva que mente que o PR existe para eliminar. Antes do PR, era `Ambiguous`.

    @Test
    fun remedioDoDentistaNaoConcluiODentista() {
        // Título de uma palavra só ("Dentista") NÃO pode casar por conter a palavra no alvo:
        // o alvo "remedio do dentista" é de duas significativas e só 1 casa. Ambíguo — nunca
        // concluir a tarefa errada calado.
        assertThat(resolveBySpeech("já tomei o remédio do dentista", "Dentista", "Tomar remédio"))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun remedioDoDentistaComODentistaSozinhoNaAgendaNaoCasa() {
        // O caso mais grave do ramo: com UMA tarefa só ("Dentista") na agenda, o alvo
        // "remedio do dentista" (1 de 2) casava o título inteiro de uma palavra e virava `One`
        // — apagava ou concluía "Dentista" calado. Tem de ser `None`.
        assertThat(resolveBySpeech("cancela o remédio do dentista", "Dentista"))
            .isEqualTo(SpeechTargetResolution.None)
    }

    // --- P2: a maioria estrita derrubava alvos legítimos ---------------------------------
    //
    // O reconhecedor anexa ao alvo o marcador temporal ("amanhã", "de manhã") que o parser já
    // consumiu como data/hora. Não é conteúdo da tarefa e não pode derrubar um casamento que
    // sem ele aconteceria. Todos estes devolviam `One` antes do #54.

    @Test
    fun remedioDeManhaConcluiOTomarRemedio() {
        // "de manha" é hora do dia (o parser já a consome), não conteúdo: não pode derrubar.
        assertThat(resolveBySpeech("já tomei o remédio de manhã", "Tomar remédio"))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun consultaDeAmanhaCancelaAConsultaMedica() {
        // "amanha" é data consumida pelo parser, não conteúdo do alvo: "consulta medica de
        // amanha" tem de casar "Consulta médica" (o "medica" flexiona).
        assertThat(resolveBySpeech("cancela a consulta de amanhã", "Consulta médica"))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun tomarRemedioHojeContinuaCasando() {
        // Mesmo caso, pelo outro gatilho: "hoje" é circunstância, não a segunda palavra.
        assertThat(resolveBySpeech("já tomei o remédio hoje", "Tomar remédio"))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    // --- P2 residual: a palavra a mais NÃO é temporal ------------------------------------
    //
    // "remédio da pressão" e "conta de luz" têm duas significativas e só uma casa (1 de 2):
    // "remedio" casa "Tomar remédio", "conta" casa "Pagar conta". É o MESMO formato dos
    // guardas que precisam continuar barrando — "consulta do dentista" contra "Consulta
    // médica" (1 de 2, só "consulta") e "remédio do cachorro" contra "Passear com o cachorro"
    // (1 de 2, só "cachorro"). "pressao" e "luz" são CONTEÚDO, não circunstância: não há sinal
    // lexical que separe "luz" (qualificador que a tarefa omite) de "dentista" (especialista
    // que CONTRADIZ a "médica" do título). Afrouxar aqui reabre a ação destrutiva que mente —
    // cancelar "Conta de luz" quando ela falou "conta de água". O desfecho seguro é `None`.

    @Test
    fun remedioDaPressaoAindaNaoCasa_limiteConhecido() {
        assertThat(resolveBySpeech("já tomei o remédio da pressão", "Tomar remédio"))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun contaDeLuzAindaNaoCasa_limiteConhecido() {
        assertThat(resolveBySpeech("cancela a conta de luz", "Pagar conta"))
            .isEqualTo(SpeechTargetResolution.None)
    }

    // --- o que o endurecimento NÃO pode quebrar -----------------------------------------

    @Test
    fun remedioDoCachorroContinuaNaoCasandoOPasseio() {
        // Sem temporal no alvo, a maioria estrita continua barrando "remedio do cachorro"
        // contra "Passear com o cachorro" (1 de 2). O fix do P2 não pode reabrir este buraco.
        assertThat(resolveBySpeech("já tomei o remédio do cachorro", "Passear com o cachorro"))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun consultaDoDentistaContinuaNaoCasandoAConsultaMedica() {
        assertThat(resolveBySpeech("cancela a consulta do dentista", "Consulta médica"))
            .isEqualTo(SpeechTargetResolution.None)
    }

    // --- D1 pelo caminho LITERAL do relatório: alvo longo x título parecido ---------------
    //
    // Alvo "pagar a conta de luz" e título "conta de água": o alvo tem três significativas
    // ("pagar", "conta", "luz") e o título duas ("conta", "agua"); só "conta" casa. Concluir
    // ou cancelar "Conta de água" porque ela falou "conta de luz" é a ação destrutiva que
    // mente — o desfecho tem de ser não casar.
    //
    // NÃO é `Ambiguous`: só existe UM candidato plausível. `Ambiguous` é para quando mais de
    // uma tarefa casa por inteiro e a resposta certa é perguntar; aqui nenhuma casa por
    // inteiro, então `None` (não age) é o desfecho seguro.
    //
    // NOTA HONESTA: este caso passa ANTES e DEPOIS do fix — o ramo composite do d0ca6df exigia
    // que TODAS as significativas do TÍTULO estivessem no alvo, e "agua" nunca está em "pagar
    // a conta de luz". Ele é guarda de regressão, não prova do D1; a prova do D1 são os testes
    // de título de UMA palavra ("Dentista"), que é onde o ramo composite de fato agia.
    @Test
    fun contaDeLuzNaoCasaAContaDeAgua() {
        assertThat(
            SpeechTargetMatcher.resolve("pagar a conta de luz", candidates("conta de água")),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    // --- a regra de ouro: na dúvida, não agir ---------------------------------------------
    //
    // Um alvo que casa PARCIALMENTE com cada candidato (1 de 2 em cada) não escolhe nenhum no
    // chute. `None` é seguro: não age, então nunca apaga a tarefa errada. `Ambiguous` fica
    // reservado para quando DUAS tarefas casam por inteiro — aí perguntar é o certo, e é o que
    // `doisRemediosSaoAmbiguos` prende.
    @Test
    fun alvoParcialContraDoisCandidatosNaoEscolheNoChute() {
        assertThat(
            SpeechTargetMatcher.resolve(
                "remedio do cachorro",
                candidates("Tomar remédio", "Passear com o cachorro"),
            ),
        ).isEqualTo(SpeechTargetResolution.None)
    }
}
