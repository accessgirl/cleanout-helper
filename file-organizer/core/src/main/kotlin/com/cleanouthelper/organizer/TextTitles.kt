package com.cleanouthelper.organizer

/** Picks a good short title out of a document's text or metadata. */
object TextTitles {
    private val junkTitle = Regex(
        "(?i)^(microsoft (word|excel|powerpoint)( -.*)?|untitled.*|document\\d*|doc\\d*|book\\d*|presentation\\d*|slide \\d+|" +
            "powerpoint presentation|title|page \\d+( of \\d+)?|sheet\\d*|new document|click to edit.*|\\d+|[a-z]:\\\\.*|.*\\.(docx?|pdf|xlsx?|pptx?|txt))$",
    )
    private val junkLine = Regex(
        "(?i)^(page \\d+( of \\d+)?|\\d+ ?/ ?\\d+|confidential|draft|www\\..*|https?://.*|.*@.*\\..*|tel[:.].*|phone[:.].*|fax[:.].*|" +
            "[0-9 ()+\\-./:,$€£%]+|©.*|copyright.*|all rights reserved.*|\\W+|[0-9: ]+\\s*(am|pm)|mon|tue|wed|thu|fri|sat|sun)$",
    )

    fun isUsefulTitle(t: String): Boolean {
        val s = t.trim()
        return s.length in 3..120 && !junkTitle.matches(s) && s.any { it.isLetter() }
    }

    /** Looks like a person's name rather than "Owner", "User" or "Microsoft Office User". */
    fun isPersonName(s: String): Boolean {
        val t = s.trim()
        if (t.length < 3 || t.length > 60) return false
        if (Regex("(?i)(user|owner|admin|microsoft|office|windows|unknown|author|pc|computer|hp|dell|lenovo)").containsMatchIn(t)) return false
        return t.any { it.isLetter() }
    }

    /**
     * The first line that reads like a title: has real words, isn't a page number, date, web address,
     * or phone number. Long lines are cut to the first few words.
     */
    fun firstGoodLine(text: String, maxWords: Int = 9): String? {
        for (raw in text.lineSequence().take(60)) {
            val line = raw.replace(Regex("[\\t ]+"), " ").trim().trim('•', '*', '-', '#', '|', '"', '“', '”', ':', '=', '_')
                .trim()
            if (line.length < 3) continue
            if (junkLine.matches(line)) continue
            val words = Names.words(line)
            val letterWords = words.count { w -> w.count { it.isLetter() } >= 2 }
            if (letterWords == 0) continue
            if (letterWords == 1 && (words[0].length < 4 || line.length < 4)) continue
            // OCR noise: too many odd characters.
            val odd = line.count { !it.isLetterOrDigit() && it !in " .,'&-()/:!?$€£%+#" }
            if (odd * 4 > line.length) continue
            val short = if (words.size > maxWords) {
                val cut = line.split(' ').take(maxWords).joinToString(" ").trimEnd(',', ';', ':', '-')
                cut
            } else line
            return Names.tidyCase(short.trimEnd('.', ',', ';', ':'))
        }
        return null
    }
}
