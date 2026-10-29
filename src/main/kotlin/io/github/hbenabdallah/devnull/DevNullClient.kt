package io.github.hbenabdallah.devnull

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class Author(val handle: String)

data class CommitResult(val shortHash: String, val htmlUrl: String, val status: String?)

class ApiException(message: String, val status: Int) : Exception(message)

/**
 * A client of the /dev/null API that signs every request with the device key.
 */
class DevNullClient(private val baseUrl: String, private val key: DeviceKey) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val gson = Gson()

    /**
     * Redeems the code the web sent back after the device was approved, which makes the key a device of the user.
     */
    fun approveDevice(code: String, verifier: String): Author =
        Author(request("POST", "/api/v1/devices", mapOf("code" to code, "code_verifier" to verifier)).string("handle"))

    fun me(): Author = Author(request("GET", "/api/v1/me").string("handle"))

    fun commit(body: String): CommitResult {
        val commit = request("POST", "/api/v1/commits", mapOf("body" to body))

        return CommitResult(commit.string("short_hash"), commit.string("html_url"), commit.get("status")?.takeIf { it.isJsonPrimitive }?.asString)
    }

    private fun request(method: String, path: String, payload: Map<String, String>? = null): JsonObject {
        val body = payload?.let(gson::toJson) ?: ""
        val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .method(method, if (payload == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))

        if (payload != null) {
            builder.header("Content-Type", "application/json")
        }

        key.signedHeaders(method, path, body).forEach(builder::header)

        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()

        if (response.statusCode() !in 200..299) {
            val firstError = json?.getAsJsonObject("errors")?.entrySet()?.firstOrNull()?.value?.asJsonArray?.firstOrNull()?.asString
            val message = firstError ?: json?.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "/dev/null answered ${response.statusCode()}."

            throw ApiException(message, response.statusCode())
        }

        return json?.getAsJsonObject("data") ?: json ?: JsonObject()
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
}
