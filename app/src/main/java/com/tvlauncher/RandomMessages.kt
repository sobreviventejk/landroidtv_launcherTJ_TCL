package com.tvlauncher

/**
 * Built-in pool of short messages shown under the greeting on the home screen.
 *
 * Every template may contain [PLACEHOLDER], replaced by "Thiel", "Jéssica" or "Thiel e Jéssica"
 * depending on the chosen [Target]. Messages rotate together with the greeting (every
 * [GreetingText.ROTATE_MS]).
 */
object RandomMessages {

    enum class Kind { MESSAGE, POEM, JOKE }

    /** Who the message talks to. */
    enum class Target { ALTERNATE, THIEL, JESSICA, BOTH }

    data class Entry(val kind: Kind, val template: String)

    const val PLACEHOLDER = "{nome}"

    /** All three kinds enabled (default). */
    const val ALL_KINDS_MASK: Int = 0b111

    fun maskOf(kinds: Set<Kind>): Int = kinds.fold(0) { mask, kind -> mask or (1 shl kind.ordinal) }

    fun kindsOf(mask: Int): Set<Kind> =
        Kind.values().filter { mask and (1 shl it.ordinal) != 0 }.toSet()

    fun isEnabled(mask: Int, kind: Kind): Boolean = mask and (1 shl kind.ordinal) != 0

    val ALL: List<Entry> = listOf(
        // ---- mensagens ----
        Entry(Kind.MESSAGE, "{nome}, o sofá já está te esperando. Vem descansar um pouco."),
        Entry(Kind.MESSAGE, "Lembrete do dia: quem ama também divide o controle remoto."),
        Entry(Kind.MESSAGE, "{nome}, hoje o plano é simples: filme, coberta e nada de pressa."),
        Entry(Kind.MESSAGE, "Se o dia foi longo, o colo é logo ali. Respira, {nome}."),
        Entry(Kind.MESSAGE, "Café passado, série escolhida. Falta só você, {nome}."),
        Entry(Kind.MESSAGE, "{nome}, essa launcher é pequena, mas foi feita pensando em você."),
        Entry(Kind.MESSAGE, "Briga de hoje? Melhor deixar para depois do jantar."),
        Entry(Kind.MESSAGE, "{nome}, dia de preguiça também é dia de cuidar da gente."),
        Entry(Kind.MESSAGE, "Nada como chegar em casa e ver que o outro escolheu o filme errado de novo."),
        Entry(Kind.MESSAGE, "{nome}, você é a melhor parte da rotina."),
        Entry(Kind.MESSAGE, "A casa fica mais leve quando {nome} está por perto."),
        Entry(Kind.MESSAGE, "Hoje vale um abraço demorado, {nome}. Sem motivo, só vale."),
        // ---- poemas ----
        Entry(Kind.POEM, "{nome}, teu abraço é o lugar onde o dia finalmente descansa."),
        Entry(Kind.POEM, "Há quem prefira o mar; eu prefiro o teu riso, {nome}."),
        Entry(Kind.POEM, "Se o mundo pesa, {nome}, eu carrego contigo."),
        Entry(Kind.POEM, "Teu nome é a palavra que deixa a casa mais bonita, {nome}."),
        Entry(Kind.POEM, "Entre um café e outro, {nome}, o melhor do dia é te ver chegar."),
        Entry(Kind.POEM, "A noite fica mais curta quando a conversa é com você, {nome}."),
        Entry(Kind.POEM, "Se amor fosse filme, {nome}, a gente ia querer sessão dupla."),
        Entry(Kind.POEM, "Meu lugar preferido continua sendo do teu lado, {nome}."),
        Entry(Kind.POEM, "Tem dias que o silêncio ao teu lado já é a melhor conversa, {nome}."),
        Entry(Kind.POEM, "Guardo teu sorriso como quem guarda o melhor pedaço do dia, {nome}."),
        // ---- piadas ----
        Entry(Kind.JOKE, "Por que o controle remoto nunca fica no lugar? Porque aqui em casa é ele quem manda."),
        Entry(Kind.JOKE, "{nome}, o Wi-Fi caiu. Aproveita e conversa comigo."),
        Entry(Kind.JOKE, "Que tal um filme? Você escolhe o título e eu escolho reclamar depois."),
        Entry(Kind.JOKE, "Meu plano de academia continua o mesmo: começar segunda. Toda segunda."),
        Entry(Kind.JOKE, "A geladeira abriu e disse: filho, fecha que eu não sou porta de shopping."),
        Entry(Kind.JOKE, "{nome}, se eu dormir no sofá a culpa é da série, não da conversa."),
        Entry(Kind.JOKE, "Casal que assiste série junto discute menos. Entre um episódio e outro."),
        Entry(Kind.JOKE, "Meu superpoder é achar o controle remoto. Depois de procurar quarenta minutos."),
        Entry(Kind.JOKE, "O plano era dormir cedo. Aí começou o episódio um de novo."),
        Entry(Kind.JOKE, "{nome}, hoje o jantar é surpresa. Provavelmente miojo.")
    )

    /** Name used in the message for a given [target] and rotation [slot]. */
    fun nameFor(target: Target, slot: Long): String = when (target) {
        Target.THIEL -> "Thiel"
        Target.JESSICA -> "Jéssica"
        Target.BOTH -> "Thiel e Jéssica"
        Target.ALTERNATE -> when ((positive(slot) % 3L).toInt()) {
            0 -> "Thiel"
            1 -> "Jéssica"
            else -> "Thiel e Jéssica"
        }
    }

    /**
     * Message to show at [nowMs], or null when no kind is enabled.
     * The same text is repeated only after every enabled message has been shown.
     */
    fun pick(kindsMask: Int, target: Target, nowMs: Long): String? {
        val allowed = ALL.filter { isEnabled(kindsMask, it.kind) }
        if (allowed.isEmpty()) return null
        val slot = positive(nowMs / GreetingText.ROTATE_MS)
        val entry = allowed[(slot % allowed.size).toInt()]
        return entry.template.replace(PLACEHOLDER, nameFor(target, slot))
    }

    private fun positive(value: Long): Long = ((value % Long.MAX_VALUE) + Long.MAX_VALUE) % Long.MAX_VALUE
}
