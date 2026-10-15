package dev.pocketherd.core.ui

/**
 * How long a status has been in effect, in the shortest form that stays unambiguous.
 *
 * `exact = false` prefixes "≥" because the duration was inferred from when the app
 * connected, not observed. An agent that was already blocked when you opened the app may
 * have been blocked for hours; claiming "2m" because the connection is two minutes old
 * would actively mislead the one decision this screen exists to support.
 */
fun formatAge(minutes: Long): String = when {
    minutes < 1 -> "now"
    minutes < 60 -> "${minutes}m"
    minutes < 1440 -> "${minutes / 60}h" + if (minutes % 60 != 0L) " ${minutes % 60}m" else ""
    else -> "${minutes / 1440}d"
}

fun formatAgeDisplay(minutes: Long, exact: Boolean): String =
    if (exact) formatAge(minutes) else "≥" + formatAge(minutes)

/** Spoken form, so a screen reader does not have to pronounce "≥18m". */
fun formatAgeSpoken(minutes: Long, exact: Boolean): String {
    val spelled = when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minute${plural(minutes)}"
        minutes < 1440 -> {
            val h = minutes / 60
            val m = minutes % 60
            "$h hour${plural(h)}" + if (m != 0L) " $m minute${plural(m)}" else ""
        }
        else -> {
            val d = minutes / 1440
            "$d day${plural(d)}"
        }
    }
    return if (exact) "for $spelled" else "for at least $spelled"
}

private fun plural(n: Long) = if (n == 1L) "" else "s"
