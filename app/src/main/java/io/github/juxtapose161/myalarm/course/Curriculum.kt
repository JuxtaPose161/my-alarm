package io.github.juxtapose161.myalarm.course

/**
 * The course the generated tasks follow.
 *
 * The spine is English File (4th edition, Oxford): the same split into levels and roughly the same
 * order of grammar and topics, from Elementary to Advanced Plus, plus a C2 block in the spirit of
 * Cambridge C2 Proficiency. Only the plan lives here (grammar points and topics); every text is
 * written by the model, nothing is copied from the book.
 */
object Curriculum {

    data class Grammar(val id: String, val name: String, val ru: String)
    data class Level(val cefr: String, val book: String, val grammar: List<Grammar>, val topics: List<String>)

    private fun g(id: String, name: String, ru: String) = Grammar(id, name, ru)

    val levels: List<Level> = listOf(
        Level(
            "A1", "English File Elementary (1–6)",
            listOf(
                g("a1_be", "to be: am / is / are", "глагол to be"),
                g("a1_present_simple", "Present Simple", "настоящее простое"),
                g("a1_have_got", "have got", "have got — иметь"),
                g("a1_can", "can / can't", "can — уметь, мочь"),
                g("a1_there_is", "there is / there are", "оборот there is / are"),
                g("a1_adverbs_freq", "adverbs of frequency", "наречия частоты: always, usually…"),
                g("a1_like_ing", "like / love / hate + -ing", "like + -ing"),
                g("a1_object_pronouns", "object pronouns", "объектные местоимения: me, him…"),
            ),
            listOf("о себе и семье", "мой день", "дом и город", "еда и кафе", "покупки", "свободное время"),
        ),
        Level(
            "A2", "English File Elementary (7–12) / Pre-Intermediate (1–4)",
            listOf(
                g("a2_past_simple", "Past Simple (regular & irregular)", "прошедшее простое"),
                g("a2_past_continuous", "Past Continuous", "прошедшее длительное"),
                g("a2_present_continuous", "Present Continuous (now and future plans)", "настоящее длительное, планы"),
                g("a2_going_to", "be going to", "be going to — намерения"),
                g("a2_comparatives", "comparatives & superlatives", "сравнительная и превосходная степень"),
                g("a2_countable", "countable / uncountable, some / any, much / many", "исчисляемые и неисчисляемые"),
                g("a2_present_perfect_exp", "Present Perfect (experience, ever / never)", "Present Perfect: опыт"),
                g("a2_will_offers", "will / won't (predictions, offers, decisions)", "will: решения, обещания"),
            ),
            listOf("путешествия", "погода и времена года", "праздники", "работа", "здоровье", "транспорт и дорога"),
        ),
        Level(
            "B1", "English File Pre-Intermediate (5–12) / Intermediate",
            listOf(
                g("b1_present_perfect_for_since", "Present Perfect with for / since, Present Perfect Continuous", "Present Perfect + for/since"),
                g("b1_first_conditional", "First conditional", "условные 1-го типа"),
                g("b1_second_conditional", "Second conditional", "условные 2-го типа"),
                g("b1_passive_present_past", "Passive (present & past)", "страдательный залог"),
                g("b1_used_to", "used to", "used to — раньше бывало"),
                g("b1_modals_obligation", "must / have to / should", "модальные: долженствование, совет"),
                g("b1_relative_clauses", "defining relative clauses (who / which / that / where)", "определительные придаточные"),
                g("b1_gerund_infinitive", "gerund or infinitive", "герундий или инфинитив"),
                g("b1_past_perfect", "Past Perfect", "предпрошедшее"),
                g("b1_reported_speech", "reported speech (statements)", "косвенная речь"),
            ),
            listOf("знакомства и отношения", "деньги", "образование", "технологии", "кино и книги", "спорт", "решение проблем в сервисе"),
        ),
        Level(
            "B2", "English File Intermediate Plus / Upper-Intermediate",
            listOf(
                g("b2_narrative_tenses", "narrative tenses (past simple / continuous / perfect / perfect continuous)", "повествовательные времена"),
                g("b2_future_perfect_continuous", "Future Perfect & Future Continuous", "Future Perfect и Future Continuous"),
                g("b2_third_conditional", "Third conditional", "условные 3-го типа"),
                g("b2_mixed_conditionals", "mixed conditionals", "смешанные условные"),
                g("b2_wish_regret", "wish / if only", "wish / if only — сожаление"),
                g("b2_passive_advanced", "passive: all tenses, have something done", "пассив во всех временах, have smth done"),
                g("b2_modals_deduction", "modals of deduction (must have / can't have / might have)", "модальные предположения о прошлом"),
                g("b2_reported_questions", "reported questions & reporting verbs", "косвенные вопросы, глаголы передачи речи"),
                g("b2_non_defining", "non-defining relative clauses", "неопределительные придаточные"),
                g("b2_linkers_contrast", "linkers of contrast and purpose (although, despite, so that…)", "связки противопоставления и цели"),
            ),
            listOf("работа и карьера", "собеседование", "медиа и новости", "окружающая среда", "наука", "путешествия и культура", "переговоры и жалобы"),
        ),
        Level(
            "C1", "English File Advanced",
            listOf(
                g("c1_inversion", "inversion after negative adverbials (Never have I…, Not only…)", "инверсия для эмфазы"),
                g("c1_cleft", "cleft sentences (What I need is…, It was … that…)", "расщеплённые предложения"),
                g("c1_participle_clauses", "participle clauses", "причастные обороты"),
                g("c1_distancing", "distancing (It is said that…, seem / appear)", "дистанцирование: it is said…"),
                g("c1_unreal_past", "unreal past (It's time…, I'd rather…, as if)", "нереальное прошедшее"),
                g("c1_future_in_past", "future in the past (was going to, was about to)", "будущее в прошедшем"),
                g("c1_ellipsis_substitution", "ellipsis & substitution", "эллипсис и замещение"),
                g("c1_advanced_modals", "modals: needn't have, should have, would rather", "сложные модальные"),
            ),
            listOf("психология", "общество и политика", "бизнес и стартапы", "искусство", "медицина", "этика технологий", "переговоры"),
        ),
        Level(
            "C2", "English File Advanced Plus + C2 Proficiency",
            listOf(
                g("c2_subjunctive", "subjunctive (I suggest that he be…, were it not for…)", "сослагательное наклонение"),
                g("c2_advanced_inversion", "conditional inversion (Had I known…, Should you need…)", "инверсия в условных"),
                g("c2_nominalisation", "nominalisation in formal style", "номинализация"),
                g("c2_hedging", "hedging & softening (arguably, it would appear…)", "смягчение и осторожные формулировки"),
                g("c2_fronting", "fronting for emphasis", "вынос в начало для акцента"),
                g("c2_idiomatic", "idioms & fixed expressions in context", "идиомы и устойчивые выражения"),
            ),
            listOf("философия", "академические дебаты", "литература", "экономика", "право", "дипломатия"),
        ),
    )

    val cefrLevels: List<String> = levels.map { it.cefr }

    fun level(cefr: String): Level = levels.firstOrNull { it.cefr == cefr } ?: levels[2]

    fun grammar(id: String): Grammar? = levels.firstNotNullOfOrNull { l -> l.grammar.firstOrNull { it.id == id } }

    /** Grammar of the level and all levels below it (for review). */
    fun grammarUpTo(cefr: String): List<Grammar> {
        val idx = levels.indexOfFirst { it.cefr == cefr }.let { if (it < 0) 2 else it }
        return levels.take(idx + 1).flatMap { it.grammar }
    }
}
