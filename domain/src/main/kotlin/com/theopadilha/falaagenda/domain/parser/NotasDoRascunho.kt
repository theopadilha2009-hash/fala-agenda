package com.theopadilha.falaagenda.domain.parser

/**
 * Os prefixos das notas que o `LocalTaskParser` escreve sobre **data e hora** — o que ele não
 * cravou e o que a IA pode resolver depois.
 *
 * As notas do rascunho são frases prontas para a tela, e a tela as mostra em vermelho
 * (`ConfirmDraftScreen`). Quando o `HybridParser` completa a data ou a hora pela IA, a nota que
 * dizia "falta isso" vira mentira visível — "Falta o horário" logo acima do horário preenchido.
 * O desmentido é por prefixo, e é por isso que a marcação mora aqui, na origem da nota, e não numa
 * lista de frases completas dentro do `HybridParser`: uma lista lá só desmente o que alguém
 * lembrou de escrever nela, e a nota nova passa em silêncio — foi o que aconteceu com as notas de
 * data deste parser. Marcada na origem, a nota nova já nasce desmentível.
 *
 * Os prefixos são curtos e sem acento de propósito: sobrevivem a um complemento na redação (a data
 * que rolou o ano, o dia que não existe no mês) sem que o casamento quebre.
 *
 * O critério do desmentido **não** é o mesmo para todos, e a diferença é deliberada: as notas de
 * "falta" falam do campo, então caem quando a IA traz aquele campo (ver `HybridParser`); a de
 * instante vencido fala do resultado, então cai quando o rascunho final tem as duas metades.
 */
object NotasDoRascunho {
    /** Sai quando a IA traz a data. */
    const val FALTA_DATA = "Falta a data. Não inventamos um dia."

    /** Sai quando a IA traz a hora. */
    const val FALTA_HORA = "Falta o horário. Não inventamos uma hora."

    /** Sai quando o rascunho final tem data e hora — a nota fala do resultado, não do campo. */
    const val INSTANTE_PASSADO = "Essa data e horário já passaram."

    /**
     * A data que o texto aponta não existe no calendário ("31/02"), ou o dia não existe no mês
     * deduzido ("no dia 31" ouvido em fevereiro). Sai quando a IA traz a data.
     */
    const val DATA_IMPOSSIVEL = "Data impossível"

    /**
     * O ano que ela não disse foi completado por palpite — "05/08" já passou este ano e ficou em
     * 2027. Sai quando a IA traz a data.
     */
    const val DATA_A_CONFIRMAR = "Data a confirmar"

    /** A data não foi entendida por inteiro e o parser não cravou. Sai quando a IA traz a data. */
    const val DATA_AMBIGUA = "A data ficou ambígua."

    /** Notas cujo assunto é a data: a IA trazendo a data já as tornou falsas. */
    val SOBRE_A_DATA = listOf(FALTA_DATA, DATA_IMPOSSIVEL, DATA_A_CONFIRMAR, DATA_AMBIGUA)

    /** Notas cujo assunto é a hora: a IA trazendo a hora já as tornou falsas. */
    val SOBRE_A_HORA = listOf(FALTA_HORA)

    /** Notas que falam do instante final: caem quando o rascunho final tem data e hora. */
    val SOBRE_O_INSTANTE = listOf(INSTANTE_PASSADO)

    /**
     * A nota da IA que diz que a data **não deu para entender** e "ficou sem essa parte".
     *
     * Ao contrário das de cima, ela não nasce do parser local: nasce na fronteira da IA
     * (`SupabaseFunctions`), quando `local_date` não é uma data. Quem decide se ela é verdade,
     * porém, é o mesmo juiz: o **desfecho do merge**. O `mergeRemote` só aceita a data do remoto
     * quando ela existe, então uma data que o **local** cravou volta e a parte não sumiu — e aí a
     * nota mente. O `HybridParser` a suprime pelo prefixo; por isso a marca mora aqui, junto das
     * outras, e a fronteira compõe o texto a partir dela — o par escreve/suprime não pode divergir
     * sem que o compilador veja.
     */
    const val IA_DATA_ILEGIVEL = "A ajuda extra devolveu uma data que não deu para entender."

    /** A mesma ideia para a hora: o prefixo da nota de `local_time` ilegível da IA. */
    const val IA_HORA_ILEGIVEL = "A ajuda extra devolveu um horário que não deu para entender."

    /** As notas da IA que falam da data/hora perdida: caem quando o rascunho final tem aquele campo. */
    val IA_SOBRE_A_DATA = listOf(IA_DATA_ILEGIVEL)
    val IA_SOBRE_A_HORA = listOf(IA_HORA_ILEGIVEL)
}
