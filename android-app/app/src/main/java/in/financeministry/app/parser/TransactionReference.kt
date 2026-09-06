package `in`.financeministry.app.parser

/** Transient only: callers must HMAC this value before persisting it. */
object TransactionReference {
    fun extract(body: String): String? {
        val references = Regex("""\b(?:ref(?:erence)?(?:\s*no\.?)?|rrn|utr)\s*[:#.-]?\s*(\d{8,24})(?!\d)""",
            RegexOption.IGNORE_CASE).findAll(body).map { it.groupValues[1] }.distinct().toList()
        return references.singleOrNull()
    }
}
