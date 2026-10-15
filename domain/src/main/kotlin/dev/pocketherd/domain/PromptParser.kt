package dev.pocketherd.domain

import dev.pocketherd.core.model.PromptFlavor
import dev.pocketherd.core.model.PromptKind
import dev.pocketherd.core.model.PromptOption

/**
 * Works out what a blocked agent is asking, from the text of its screen.
 *
 * Input is `pane.read(source = "detection", strip_ansi = true)`.
 *
 * This is pattern matching against what agents happen to print, so it is wrong sometimes by
 * construction. Everything it fails to recognise becomes [PromptKind.Unrecognised], which
 * the UI renders as a plain text box: the same thing the app did before any of this
 * existed. A bad guess costs a wasted tap; refusing to guess costs nothing.
 *
 * The key is what gets sent, never the label. Labels are shortened for display, and sending
 * a shortened label would answer a different question than the one shown.
 */
object PromptParser {

    private val OPTION = Regex("""^\s*[❯>]?\s*(\d)\.\s+(.+)$""")
    private val YES_NO = Regex("""\((y/N|Y/n)\)""")
    private val PLAN_WORD = Regex("""\bplan(s|ning)?\b""", RegexOption.IGNORE_CASE)
    private val TRAILING_PATH = Regex("""\s+in\s+[~/][^\s,]*""")
    private val DIFF_LINE = Regex("""^\d+\s*[+-]\s""")

    fun parse(text: String): PromptKind {
        val lines = text.lines()

        // Options belong to the *last* question on screen. A plan prints its own numbered
        // steps above the question, and those look exactly like options; anchoring to the
        // last question is what separates "1. Add LOW_12V_WARN" from "1. Yes".
        val questionIndex = lines.indexOfLast { it.isNotBlank() && it.trim().endsWith("?") }
        val options = parseOptions(lines, from = questionIndex + 1)

        if (options.isNotEmpty()) {
            return PromptKind.Menu(
                question = lines.getOrNull(questionIndex)?.trim().orEmpty(),
                options = options,
                flavor = flavorOf(text),
            )
        }

        YES_NO.find(text)?.let { match ->
            return PromptKind.YesNo(
                // The marker sits after the question mark, so the whole line is the
                // question rather than something that ends in "?".
                question = lines.lastOrNull { YES_NO.containsMatchIn(it) }?.trim().orEmpty(),
                // The capitalised letter is the default: (y/N) defaults to no.
                defaultYes = match.groupValues[1] == "Y/n",
            )
        }

        // A bare "> " on the last non-empty line is an open text prompt.
        if (lines.lastOrNull { it.isNotBlank() }?.trim() == ">") {
            return PromptKind.FreeText(lines.getOrNull(questionIndex)?.trim())
        }

        return PromptKind.Unrecognised
    }

    /**
     * Collects numbered options, folding wrapped continuation lines into the option above.
     *
     * Agents wrap long options at the terminal width, so "2. Yes, and don't ask again for
     * python3 commands" followed by "   in ~/gerrit/sanctum" is one option, not two.
     */
    private fun parseOptions(lines: List<String>, from: Int): List<PromptOption> {
        val keys = mutableListOf<String>()
        val labels = mutableListOf<StringBuilder>()

        for (line in lines.drop(from.coerceAtLeast(0))) {
            val match = OPTION.find(line)
            when {
                match != null -> {
                    keys += match.groupValues[1]
                    labels += StringBuilder(match.groupValues[2].trim())
                }

                labels.isNotEmpty() && line.isNotBlank() && line.startsWith("   ") ->
                    labels.last().append(' ').append(line.trim())

                else -> Unit
            }
        }

        return keys.indices.map { i ->
            val raw = labels[i].toString()
            PromptOption(key = keys[i], label = shorten(raw), thenCompose = expectsProse(raw))
        }
    }

    /** "(esc)" and "No, and tell…" mean the agent wants prose next. */
    private fun expectsProse(label: String): Boolean =
        label.contains("(esc)") || label.startsWith("No, and tell", ignoreCase = true)

    /**
     * Display-only shortening. Long options wrap to three lines on a phone and the tail is
     * rarely the part that distinguishes them.
     */
    private fun shorten(label: String): String {
        var out = label.replace("(esc)", "").trim()
        // "Yes, and don't ask again" -> "Yes, don't ask again". The conjunction is filler
        // on a button that has to fit a thumb.
        out = out.replace(", and ", ", ")
        out = out.removePrefix("and ").trim()
        // "don't ask again for python3 commands in ~/long/path" -> "... here"
        out = TRAILING_PATH.replace(out, " here")
        return out.trim().trimEnd(',')
    }

    /**
     * Diff markers are checked before the word "plan".
     *
     * The obvious order, plan first, misfires: an edit to `planner/route_planner.cc`
     * contains "plan" in the filename and gets labelled a plan approval. Numbered +/-
     * lines are a far stronger signal, and a genuine plan does not print them.
     */
    private fun flavorOf(text: String): PromptFlavor = when {
        hasDiffLines(text) -> PromptFlavor.Edit
        PLAN_WORD.containsMatchIn(text) -> PromptFlavor.Plan
        else -> PromptFlavor.Permission
    }

    /**
     * Requires a line-numbered +/- marker. A bare leading "-" is how prose bullets are
     * written, and treating those as a diff would label half the permission prompts as
     * edits.
     */
    private fun hasDiffLines(text: String): Boolean =
        text.lines().any { DIFF_LINE.containsMatchIn(it.trimStart()) }
}
