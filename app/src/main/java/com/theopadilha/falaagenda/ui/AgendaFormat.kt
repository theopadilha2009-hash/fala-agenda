package com.theopadilha.falaagenda.ui

import com.theopadilha.falaagenda.data.repo.ChoiceSchedule
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.reminder.DraftSchedule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AgendaFormat {
    private val locale: Locale = Locale.forLanguageTag("pt-BR")
    private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", locale)
    private val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' uuuu", locale)
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)

    fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Hoje"
        today.plusDays(1) -> "Amanhã"
        today.minusDays(1) -> "Ontem"
        else -> date.format(dayMonth)
    }

    fun longDate(date: LocalDate): String = date.format(longDate).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(locale) else it.toString()
    }

    fun time(time: LocalTime): String = time.format(clock)

    fun greeting(time: LocalTime): String = when (time.hour) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }

    fun announce(date: LocalDate, time: LocalTime, today: LocalDate): String {
        val whenLabel = dateLabel(date, today).lowercase(locale)
        return "Vai avisar $whenLabel às ${time(time)}."
    }

    fun recap(date: LocalDate, time: LocalTime, recurrence: RecurrenceRule): String =
        "Vai avisar ${longDate(date)} às ${time(time)}. ${recurrence.describePtBr()}."

    /**
     * O que a tela promete antes de salvar: o resumo, a linha que explica uma data descartada
     * e o rótulo do botão. Os três saem das mesmas contas, para não voltarem a divergir entre
     * si — o defeito de origem foram duas contas para a mesma data.
     */
    data class DraftPromise(
        val recap: String,
        /** Nulo quando a data escolhida é a que vai valer. */
        val droppedChoice: String?,
        val saveLabel: String,
    )

    /**
     * [editing] diz qual contrato vale, porque são dois: criar uma série ancora a primeira
     * ocorrência na regra — a data escolhida é só piso, ver [DraftSchedule.firstOccurrence] —,
     * enquanto editar decide por [ChoiceSchedule]: a data escolhida vale, mas se ela já venceu a
     * ocorrência é arquivada e, na regra que repete, quem é armada é a próxima data da regra. A
     * tela descreve o contrato que vai valer, e não o que ela tocou.
     */
    fun promiseOfChoice(
        chosenDate: LocalDate,
        chosenTime: LocalTime,
        recurrence: RecurrenceRule,
        today: LocalDate,
        now: Instant,
        zone: ZoneId,
        editing: Boolean = false,
    ): DraftPromise {
        // A data e o motivo saem das peças compartilhadas — as mesmas contas com que o
        // repositório grava —, para a tela não recalcular nem inventar explicação.
        val first = DraftSchedule.firstOccurrence(recurrence, chosenDate, chosenTime, zone, now)
        val promisedDate: LocalDate
        val movedBecause: DraftSchedule.FirstOccurrence.Reason?
        if (editing) {
            val plan = ChoiceSchedule.plan(recurrence, chosenDate, chosenTime, zone, now)
            promisedDate = plan.date
            // Vencida: o motivo é o horário que passou, e a escolha continua valendo — só não
            // hoje. É a mesma frase que a criação usa, para a data descartada não sumir em
            // silêncio: sem ela a tela prometia a data tocada e o alarme tocava em outra.
            movedBecause = if (plan.expired) DraftSchedule.FirstOccurrence.Reason.TIME_PASSED else null
        } else {
            promisedDate = first.date
            movedBecause = first.movedBecause
        }
        val promisedAt = promisedDate.atTime(chosenTime).atZone(zone).toInstant()
        // A escolha que já passou e não repete: o salvar arquiva a ocorrência como não
        // realizada e não cria alarme nenhum (ver `TaskRepository.occurrencesForChoice`).
        // Prometer "Vai avisar" aqui era o aplicativo anunciar um aviso que ele mesmo não arma —
        // e a home, no toque seguinte, mostrar "Não consegui avisar" sobre a mesma tarefa. O
        // predicado só morde quando a regra não repete: na que repete, a data prometida já é a
        // próxima da regra, futura, e não há nada a desmentir.
        if (DraftSchedule.bornWithoutReminder(promisedAt, recurrence, now)) {
            return DraftPromise(
                recap = "Este horário já passou e a tarefa não repete: não vou avisar.",
                droppedChoice = null,
                saveLabel = "Salvar · ${dateLabel(chosenDate, today).lowercase(locale)} " +
                    "${time(chosenTime)}, sem aviso",
            )
        }
        return DraftPromise(
            recap = recap(promisedDate, chosenTime, recurrence),
            droppedChoice = droppedChoiceLine(
                chosenDate = chosenDate,
                chosenTime = chosenTime,
                promisedDate = promisedDate,
                movedBecause = movedBecause,
                recurrence = recurrence,
                today = today,
            ),
            saveLabel = "Salvar · ${dateLabel(promisedDate, today).lowercase(locale)} ${time(chosenTime)}",
        )
    }

    /**
     * A linha que impede a data descartada de sumir em silêncio: ela tocou o chip "Hoje" numa
     * terça com a regra "toda segunda", o aviso vai ser em 05/10, e ela precisa poder entender
     * por quê — e corrigir. Nula quando a data escolhida é a que vai valer.
     *
     * São dois motivos, e eles não podem virar a mesma frase: a regra que não cai naquele dia
     * ("toda segunda" com a terça do chip) e o horário de hoje que já passou numa regra que
     * repete, em que a escolha vale — só não hoje. Dizer "não cai nesse dia" ali seria falso, e
     * o motivo vem de `DraftSchedule` justamente para a frase não ser uma segunda conta dele.
     */
    private fun droppedChoiceLine(
        chosenDate: LocalDate,
        chosenTime: LocalTime,
        promisedDate: LocalDate,
        movedBecause: DraftSchedule.FirstOccurrence.Reason?,
        recurrence: RecurrenceRule,
        today: LocalDate,
    ): String? {
        if (chosenDate == promisedDate) return null
        val escolhido = dateLabel(chosenDate, today).lowercase(locale)
        if (movedBecause == DraftSchedule.FirstOccurrence.Reason.TIME_PASSED) {
            return "Você escolheu $escolhido às ${time(chosenTime)}, e esse horário já passou: " +
                "o primeiro aviso é ${longDate(promisedDate)} às ${time(chosenTime)}."
        }
        return "Você escolheu $escolhido, e " +
            "“${recurrence.describePtBr()}” não cai nesse dia: o primeiro aviso é " +
            "${longDate(promisedDate)}."
    }

    data class DayShareLine(
        val title: String,
        val time: LocalTime,
        val observation: String = "",
        /** "ontem", "25/08": de que dia é a tarefa, quando não é de hoje. Ver [shareDayMark]. */
        val dayMark: String? = null,
    )

    /**
     * De que dia é a tarefa para quem lê de fora: nulo quando é de hoje, porque aí o
     * cabeçalho já diz o dia. A seção "Hoje" da agenda recebe a pendente que atravessou a
     * meia-noite, então sem esta marca a tarefa de ontem saía no compartilhado como se
     * fosse de hoje.
     */
    fun shareDayMark(date: LocalDate, today: LocalDate): String? =
        if (date == today) null else dateLabel(date, today).lowercase(locale)

    /**
     * A pendente que atravessou a meia-noite entra na seção "Hoje" para continuar ao
     * alcance dela: sem esta marca a linha de ontem ficava igual à de hoje embaixo do mesmo
     * cabeçalho. Onde não é atrasada a resposta é nula, e a linha mostra o de sempre.
     */
    fun lateMark(date: LocalDate, today: LocalDate): String? =
        if (date.isBefore(today)) "atrasada" else null

    fun todayShare(lines: List<DayShareLine>): String {
        if (lines.isEmpty()) return "Hoje no Fala Agenda não tem nada marcado."
        val body = lines.joinToString("\n") { line ->
            val extra = line.observation.trim().takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
            val whenDay = line.dayMark?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
            "• ${line.title}$whenDay às ${time(line.time)}$extra"
        }
        return "Hoje no Fala Agenda:\n$body"
    }

    /**
     * `leituraFalhou` é a leitura da agenda que não voltou. Ela não diz "não há nada": diz
     * "não deu para ler" — e a seção que a falha carrega é a da última leitura boa, então
     * uma agenda vazia por falha é indistinguível de uma agenda vazia de verdade. Havendo
     * próximo, ele continua na frase (pode estar velho, e é o cartão da home que avisa
     * isso); não havendo, a frase do vazio vira a de falha — a saudação fica, que o relógio
     * não depende da leitura.
     *
     * [naoAvisados] é o subconjunto de [missedCount] que o **aplicativo** deixou de avisar
     * (ver `missedReason`/`NOT_WARNED` na home). Os dois entram separados porque "3 recados
     * ficaram para trás" no topo da tela, sobre um remédio que ninguém lembrou de avisar, é a
     * frase que faz uma senhora de 70 anos se achar esquecida por uma falha do aplicativo — e
     * a seção logo abaixo já diz "Não consegui avisar", assumindo a culpa. O que ela não fez
     * continua contado, mas sem verbo que a acuse.
     *
     * A separação não é exclusiva da manchete: o cartão de recap da home (`HomeScreen`) e o
     * resumo do mês (`MonthSummaryScreen`) também atribuem ao aplicativo o aviso que ele não
     * deu — pela mesma decisão (`missedReason`/`NOT_WARNED`, propagada até `MonthInsights.of`
     * pelo `naoAvisada` do `InsightRow`). São a mesma conta de propósito: uma segunda, escrita
     * aqui, divergiria da de baixo na primeira mudança de critério e o topo voltaria a
     * contradizer o fechamento do mês.
     *
     * O contador é do que **ela** deixou de fazer (`missedCount - naoAvisados`): somar os
     * dois contaria duas vezes a mesma ocorrência, e a frase sairia "2 recados ficaram para
     * trás. Não consegui avisar 1 tarefa" sobre um único remédio. Cada motivo tem um sujeito,
     * e nenhuma ocorrência entra nos dois.
     */
    fun headline(
        nowTime: LocalTime,
        today: LocalDate,
        nextTitle: String?,
        nextDate: LocalDate?,
        nextTime: LocalTime?,
        missedCount: Int,
        leituraFalhou: Boolean = false,
        naoAvisados: Int = 0,
    ): String {
        val greet = greeting(nowTime)
        val porFazer = (missedCount - naoAvisados).coerceAtLeast(0)
        val recados = when (porFazer) {
            0 -> ""
            1 -> " 1 recado ficou para trás."
            else -> " $porFazer recados ficaram para trás."
        }
        // O que o app deixou de avisar entra com o app como sujeito, e só quando há: com zero
        // não há falha a assumir, e repetir "0 avisos" seria ruído. O verbo é o mesmo da seção
        // logo abaixo ("Não consegui avisar") — duas frases para a mesma falha, com palavras
        // diferentes, fariam parecer dois problemas.
        val falhaDoApp = when (naoAvisados) {
            0 -> ""
            1 -> " Não consegui avisar 1 tarefa."
            else -> " Não consegui avisar $naoAvisados tarefas."
        }
        // O contador entra montado dentro de cada ramo, e não deduzido à parte por uma
        // segunda conta de "há próximo": escrita duas vezes, a condição do ramo e a da
        // supressão divergem na primeira pessoa que alargar uma delas, e a divergência é a
        // frase contraditória "Não consegui ler a sua agenda agora. 2 recados ficaram para
        // trás." que este conserto existe para não dizer nunca.
        val next = if (nextTitle != null && nextDate != null && nextTime != null) {
            val whenLabel = dateLabel(nextDate, today).lowercase(locale)
            // A pendente que atravessou a meia-noite é a mais urgente e é ela que aparece
            // aqui; chamá-la de "Próximo" fazia a frase se contradizer — "Próximo: Tomar
            // remédio, ontem às 08:00". O cartão da lista já a marca como atrasada (ver
            // [lateMark]); o cabeçalho diz o mesmo. Com próximo o contador fica: ali o dado
            // veio de uma leitura real, mesmo quando a última tentativa falhou.
            val lead = if (lateMark(nextDate, today) != null) "Atrasada" else "Próximo"
            " $lead: $nextTitle, $whenLabel às ${time(nextTime)}.$recados$falhaDoApp"
        } else if (leituraFalhou) {
            // Sem próximo não há o que mostrar, e afirmar ausência é justamente o que a
            // falha não pode fazer: é a mentira que o cartão da home existe para matar,
            // saindo do lugar mais visível da tela. O contador fica de fora exatamente aqui,
            // porque ao lado dessa frase ele diria que algo foi lido.
            " Não consegui ler a sua agenda agora."
        } else {
            " Nada marcado agora.$recados$falhaDoApp"
        }
        return "$greet.$next"
    }

    fun fromNow(target: Instant, now: Instant): String? {
        val minutes = Duration.between(now, target).toMinutes()
        return when {
            minutes in -1L..1L -> "agora"
            minutes in 2L..59L -> "daqui $minutes min"
            minutes in 60L..(24L * 60L - 1L) -> "daqui ${hoursAndMinutes(minutes)}"
            minutes in -59L..-2L -> "há ${-minutes} min"
            // O passado perde os minutos do mesmo jeito que o futuro perdia: 90 minutos atrás
            // saía como "há 1 h". É o que a pessoa lê na lista de hoje, para a tarefa cujo
            // horário já passou e que não leva a marca de atrasada (ver `lateMark`).
            minutes in -(24L * 60L - 1L)..-60L -> "há ${hoursAndMinutes(-minutes)}"
            else -> null
        }
    }

    /** "1 h 30 min", "2 h": o mesmo desenho dos dois lados da frase, para não voltarem a divergir. */
    private fun hoursAndMinutes(minutes: Long): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours h" else "$hours h $rest min"
    }
}
