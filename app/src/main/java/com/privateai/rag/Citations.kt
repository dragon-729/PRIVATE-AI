package com.privateai.rag

object Citations {
    private val cite = Regex("[\\[(]\\s*(?:pages?|pp?\\.?)\\s*([0-9][0-9,\\s\\u2013\\-and&]*)[\\])]", RegexOption.IGNORE_CASE)
    private val digits = Regex("\\d+")

    /**
     * Keeps only page citations that point to excerpts really given to the model; never trusts invented pages.
     * If no valid citation survives, appends the most relevant retrieved pages instead.
     */
    fun sanitize(answer: String, allowed: Set<Int>, fallback: List<Int>): String {
        var valid = 0
        val cleaned = cite.replace(answer) { m ->
            val nums = digits.findAll(m.groupValues[1]).map { it.value.toInt() }.toList()
            if (nums.isNotEmpty() && nums.all { it in allowed }) {
                valid++
                nums.distinct().joinToString(" ") { "[Page $it]" }
            } else ""
        }.replace(Regex("[ \\t]{2,}"), " ").trim()
        return if (valid > 0) cleaned
        else cleaned + "\n\nMost relevant pages (the model did not cite specific pages): " +
            fallback.joinToString(", ") { "[Page $it]" }
    }
}
