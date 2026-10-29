package io.github.hbenabdallah.devnull

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitBodyTest {
    private val selection = CodeSelection(
        code = "    public function handle(): void\n    {\n        //\n    }\n",
        language = "php",
        path = "app/Jobs/Example.php",
        startLine = 10,
        endLine = 13,
        permalink = null,
    )

    @Test
    fun `puts the selected code in a block titled with its path and lines, under the message`() {
        assertEquals(
            "Look at this\n\n```php title=\"app/Jobs/Example.php#L10-L13\"\npublic function handle(): void\n{\n    //\n}\n```",
            CommitBody.of("Look at this", selection),
        )
    }

    @Test
    fun `leaves the message out when there is none, and adds the GitHub link`() {
        assertEquals(
            "```php title=\"app/Jobs/Example.php#L3\"\necho 1;\n```\n\nhttps://github.com/a/b/blob/c/f.php#L3",
            CommitBody.of("  ", selection.copy(code = "echo 1;", startLine = 3, endLine = 3, permalink = "https://github.com/a/b/blob/c/f.php#L3")),
        )
    }

    @Test
    fun `uses a longer fence than any in the code`() {
        assertEquals("````md title=\"README.md#L1\"\n```php\n````", CommitBody.of("", selection.copy(code = "```php", language = "md", path = "README.md", startLine = 1, endLine = 1)))
    }

    @Test
    fun `refuses a selection longer than a commit`() {
        assertNull(CommitBody.of("", selection.copy(code = "x".repeat(CommitBody.MAX_LENGTH))))
    }

    @Test
    fun `links to the lines on GitHub from an https or ssh remote`() {
        val sha = "a".repeat(40)

        for (remote in listOf("https://github.com/dev-null-sh/dev-null.sh.git", "git@github.com:dev-null-sh/dev-null.sh.git")) {
            assertEquals(
                "https://github.com/dev-null-sh/dev-null.sh/blob/$sha/docs/my%20file.md#L4-L9",
                CommitBody.gitHubPermalink(remote, sha, "docs/my file.md", 4, 9),
            )
        }
        assertNull(CommitBody.gitHubPermalink("https://gitlab.com/a/b.git", sha, "f", 1, 1))
        assertNull(CommitBody.gitHubPermalink("https://github.com/a/b.git", "HEAD", "f", 1, 1))
    }

    @Test
    fun `names languages as the highlighter does`() {
        assertEquals("php", CommitBody.languageOf("PHP", "php"))
        assertEquals("tsx", CommitBody.languageOf("TypeScript JSX", "tsx"))
        assertEquals("bash", CommitBody.languageOf("Shell Script", "sh"))
        assertEquals("", CommitBody.languageOf("Text", "txt"))
    }
}
