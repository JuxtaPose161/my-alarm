package io.github.juxtapose161.myalarm.course

/**
 * JSON schemas for Claude's structured outputs. Every object lists all its properties as required
 * and forbids extra ones, as structured outputs expect.
 */
object Schemas {
    private val str = mapOf("type" to "string")
    private val bool = mapOf("type" to "boolean")
    private val int = mapOf("type" to "integer")
    private fun arr(items: Map<String, Any>) = mapOf("type" to "array", "items" to items)
    private fun obj(vararg props: Pair<String, Map<String, Any>>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to props.toMap(),
        "required" to props.map { it.first },
        "additionalProperties" to false,
    )

    val written = obj(
        "title" to str,
        "instructionsRu" to str,
        "material" to str,
        "hideMaterial" to bool,
        "speakMaterial" to bool,
        "items" to arr(str),
        "targetWords" to arr(str),
        "targetGrammar" to arr(str),
        "prepSeconds" to int,
        "minWords" to int,
    )

    val chat = obj(
        "title" to str,
        "situationRu" to str,
        "aiRole" to str,
        "userGoalRu" to str,
        "opening" to str,
        "targetWords" to arr(str),
        "targetGrammar" to arr(str),
    )

    val chatTurn = obj(
        "reply" to str,
        "usedWords" to arr(str),
        "usedGrammar" to arr(str),
        "goalReached" to bool,
        "correction" to str,
    )

    val dictation = obj("title" to str, "text" to str)

    val evaluation = obj(
        "criteria" to arr(obj("name" to str, "ok" to bool, "comment" to str)),
        "corrections" to arr(obj("wrong" to str, "right" to str, "why" to str)),
        "feedbackRu" to str,
        "usedWords" to arr(str),
        "grammarOk" to arr(str),
        "grammarErrors" to arr(str),
        "strengths" to arr(str),
        "weaknesses" to arr(str),
    )

    val aliasGuess = obj("guess" to str, "alternatives" to arr(str))

    val profileSummary = obj("summary" to str)

    val verdict = obj("acceptable" to bool, "comment" to str)
}
