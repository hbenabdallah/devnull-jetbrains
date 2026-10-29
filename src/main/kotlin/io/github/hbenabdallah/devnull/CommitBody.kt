package io.github.hbenabdallah.devnull

/**
 * The selected lines of a file, with the language the highlighter on /dev/null knows them by.
 */
data class CodeSelection(
    val code: String,
    val language: String,
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val permalink: String?,
)

object CommitBody {
    /**
     * The longest commit /dev/null takes.
     */
    const val MAX_LENGTH = 4000

    /**
     * A commit with the message, then the selected code as a fenced block titled with its path, then a link to the
     * lines. Null when it would be longer than /dev/null takes.
     */
    fun of(message: String, selection: CodeSelection): String? {
        val code = selection.code.trimEnd().trimIndent()
        val longestFence = Regex("`{3,}").findAll(code).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestFence + 1))
        val lines = if (selection.startLine == selection.endLine) "L${selection.startLine}" else "L${selection.startLine}-L${selection.endLine}"
        val title = "${selection.path}#$lines".replace("\"", "")

        val body = listOf(
            message.trim(),
            "$fence${selection.language} title=\"$title\"\n$code\n$fence",
            selection.permalink.orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString("\n\n")

        return body.takeIf { it.length <= MAX_LENGTH }
    }

    /**
     * The GitHub link to lines of a file at a commit, when the remote is on GitHub.
     */
    fun gitHubPermalink(remoteUrl: String, commit: String, path: String, startLine: Int, endLine: Int): String? {
        val match = Regex("""github\.com[:/]([^/]+)/(.+?)(?:\.git)?/?$""").find(remoteUrl.trim()) ?: return null

        if (!commit.matches(Regex("[0-9a-f]{40}"))) {
            return null
        }

        val lines = if (startLine == endLine) "L$startLine" else "L$startLine-L$endLine"
        val encodedPath = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

        return "https://github.com/${match.groupValues[1]}/${match.groupValues[2]}/blob/$commit/$encodedPath#$lines"
    }

    /**
     * IDE language names, lowercased, mostly match the highlighter's. These are the ones that don't.
     */
    fun languageOf(ideLanguage: String, extension: String?): String = when (ideLanguage.lowercase()) {
        "typescript jsx" -> "tsx"
        "javascript", "ecmascript 6" -> if (extension == "jsx") "jsx" else "js"
        "typescript" -> "ts"
        "shell script" -> "bash"
        "text", "plain_text" -> ""
        else -> ideLanguage.lowercase()
    }
}
