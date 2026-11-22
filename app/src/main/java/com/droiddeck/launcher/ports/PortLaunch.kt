package com.droiddeck.launcher.ports

/** A port's launch settings as the user types them: arguments on one line, variables as NAME=value. */
object PortLaunch {
    /** Words split on spaces, with quotes keeping a word together; null when a quote is left open. */
    fun words(text: String): List<String>? {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var open = false
        for (c in text.trim()) {
            when {
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '"' || c == '\'' -> { quote = c; open = true }
                c.isWhitespace() -> { if (open) out += word.toString(); word.setLength(0); open = false }
                else -> { word.append(c); open = true }
            }
        }
        if (quote != null) return null
        if (open) out += word.toString()
        return out
    }

    fun args(text: String): List<String>? = words(text)?.takeIf { it.all(PortCatalog::launchValue) }

    fun env(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (word in words(text) ?: return null) {
            val name = word.substringBefore('=', "")
            if (!PortCatalog.envName(name) || !PortCatalog.launchValue(word)) return null
            out[name] = word.substringAfter('=')
        }
        return out
    }

    private fun shown(word: String): String = when {
        word.isNotEmpty() && word.none { it.isWhitespace() || it == '"' || it == '\'' } -> word
        '"' !in word -> "\"$word\""
        else -> "'$word'"
    }

    fun show(args: List<String>): String = args.joinToString(" ", transform = ::shown)

    fun show(env: Map<String, String>): String = env.entries.joinToString(" ") { shown("${it.key}=${it.value}") }
}
