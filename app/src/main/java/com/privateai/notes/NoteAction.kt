package com.privateai.notes

/** mapReduce = result summarizes the whole note; false = transforms text part by part (rewrite, grammar, translate). */
enum class NoteAction(val label: String, val mapReduce: Boolean) {
    SUMMARIZE("Summarize", true),
    EXPLAIN("Explain simply", true),
    KEY_POINTS("Extract key points", true),
    ACTION_ITEMS("Generate action items", true),
    REWRITE("Rewrite professionally", false),
    GRAMMAR("Correct grammar", false),
    TRANSLATE("Translate", false),
    QUESTIONS("Generate questions", true),
    STUDY_NOTES("Create study notes", true);

    fun instruction(lang: String?): String = when (this) {
        SUMMARIZE -> "Summarize the following note in a short, clear paragraph. Use only information from the note."
        EXPLAIN -> "Explain the following note in very simple language that anyone can understand. Use short sentences. Use only information from the note."
        KEY_POINTS -> "Extract the key points of the following note as a bulleted list, one '- ' line per point. Use only information from the note."
        ACTION_ITEMS -> "List the action items and tasks found in the following note as a checklist, one '- [ ] task' line each. If there are none, answer exactly: No action items found."
        REWRITE -> "Rewrite the following text in a professional, clear tone. Keep the meaning and all facts unchanged."
        GRAMMAR -> "Correct the grammar, spelling and punctuation of the following text. Do not change its meaning or wording style."
        TRANSLATE -> "Translate the following text into ${lang?.ifBlank { null } ?: "English"}. Keep the formatting."
        QUESTIONS -> "Write 5 good questions that test understanding of the following note, numbered 1 to 5, one per line. Base them only on the note."
        STUDY_NOTES -> "Turn the following note into concise study notes: a short title, headings, and bullet points with the key facts and definitions. Use only information from the note."
    }
}
