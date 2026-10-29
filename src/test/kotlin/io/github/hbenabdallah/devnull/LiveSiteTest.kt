package io.github.hbenabdallah.devnull

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Signs in to a real /dev/null site and commits, when DEVNULL_E2E_URL names one. Someone approves the device by opening
 * the address written to DEVNULL_E2E_LINK, or printed, in a browser signed in to that site.
 */
class LiveSiteTest {
    @Test
    fun `signs in and commits a selection`() {
        val url = System.getenv("DEVNULL_E2E_URL").orEmpty()
        assumeTrue(url.isNotEmpty())

        val (key, author) = SignIn.run(url, "PhpStorm on e2e") { link ->
            println("AUTHORIZE $link")
            System.getenv("DEVNULL_E2E_LINK")?.let { File(it).writeText(link) }
        }
        println("SIGNED IN @${author.handle} ${key.fingerprint()}")

        val client = DevNullClient(url, key)
        println("ME @${client.me().handle}")

        val body = CommitBody.of(
            "Posted from the JetBrains plugin",
            CodeSelection("fun main() {\n    println(\"/dev/null\")\n}", "kotlin", "src/Main.kt", 1, 3, null),
        )!!
        val commit = runCatching { client.commit(body) }
        println("COMMIT ${commit.map { "${it.shortHash} ${it.status} ${it.htmlUrl}" }.getOrElse { "failed: ${it.message}" }}")
    }
}
