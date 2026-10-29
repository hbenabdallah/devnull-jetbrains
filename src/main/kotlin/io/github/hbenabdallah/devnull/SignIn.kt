package io.github.hbenabdallah.devnull

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Signs in like the /dev/null command line does: a new key is approved on the web as a device, which sends a one-time
 * code back to a port on the loopback address, and the code is exchanged for the device with the PKCE verifier.
 */
object SignIn {
    private const val TIMEOUT_MINUTES = 5L

    fun run(baseUrl: String, deviceName: String, openBrowser: (String) -> Unit): Pair<DeviceKey, Author> {
        val key = DeviceKey.generate()
        val pkce = Pkce.create()
        val state = randomHex(16)
        val code = CompletableFuture<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                val (name, value) = it.split('=', limit = 2)
                name to URLDecoder.decode(value, Charsets.UTF_8)
            }
            val (status, text) = when {
                query["state"] != state -> 400 to "This sign-in was not started here."
                query["code"] == null -> 200 to "Sign-in cancelled. You can close this tab.".also {
                    code.completeExceptionally(IllegalStateException("Sign-in was cancelled."))
                }
                else -> 200 to "Signed in. You can close this tab and go back to your IDE.".also { code.complete(query.getValue("code")) }
            }
            val page = "<!doctype html><meta charset=\"utf-8\"><title>/dev/null</title><body style=\"font-family:ui-monospace,monospace;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0\"><p>$text</p></body>"
                .toByteArray()

            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(status, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
        }
        server.start()

        try {
            val parameters = mapOf(
                "fingerprint" to key.fingerprint(),
                "device_name" to deviceName.take(100),
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
                "state" to state,
                "redirect_uri" to "http://127.0.0.1:${server.address.port}/callback",
            ).entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }

            openBrowser("${baseUrl.trimEnd('/')}/mobile/authorize?$parameters")

            val received = code.get(TIMEOUT_MINUTES, TimeUnit.MINUTES)

            return key to DevNullClient(baseUrl, key).approveDevice(received, pkce.verifier)
        } finally {
            server.stop(0)
        }
    }
}
