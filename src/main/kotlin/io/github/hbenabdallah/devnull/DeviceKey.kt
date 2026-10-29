package io.github.hbenabdallah.devnull

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Requests to the /dev/null API are signed with an Ed25519 key kept by the plugin, in the SSHSIG format
 * `ssh-keygen -Y sign` produces, so the key never leaves the machine and there is no token to leak.
 */
class DeviceKey private constructor(private val privateKey: PrivateKey, private val publicKey: PublicKey) {
    companion object {
        const val API_NAMESPACE = "devnull-api"

        // An Ed25519 public key in X.509 is this fixed header, then its 32 bytes.
        private const val X509_HEADER_LENGTH = 12

        fun generate(): DeviceKey {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

            return DeviceKey(pair.private, pair.public)
        }

        /**
         * A key stored with [encoded], its private half in PKCS #8 and its public half in X.509.
         */
        fun decode(encoded: String): DeviceKey {
            val (private, public) = encoded.split(':').map { Base64.getDecoder().decode(it) }
            val factory = KeyFactory.getInstance("Ed25519")

            return DeviceKey(factory.generatePrivate(PKCS8EncodedKeySpec(private)), factory.generatePublic(X509EncodedKeySpec(public)))
        }

        fun sshString(value: ByteArray): ByteArray =
            ByteBuffer.allocate(4 + value.size).putInt(value.size).put(value).array()

        fun sshString(value: String): ByteArray = sshString(value.toByteArray())

        private fun concat(vararg parts: ByteArray): ByteArray =
            ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    }

    fun encode(): String =
        Base64.getEncoder().encodeToString(privateKey.encoded) + ":" + Base64.getEncoder().encodeToString(publicKey.encoded)

    /**
     * The public key as SSH writes it: its type, then its 32 bytes.
     */
    fun publicKeyBlob(): ByteArray {
        val encoded = publicKey.encoded

        return concat(sshString("ssh-ed25519"), sshString(encoded.copyOfRange(X509_HEADER_LENGTH, encoded.size)))
    }

    /**
     * The fingerprint the web shows when approving the device, as `ssh-keygen -l` prints it.
     */
    fun fingerprint(): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKeyBlob()))

    /**
     * The message signed in the given namespace, as the base64 body of an SSH signature.
     */
    fun sign(message: String, namespace: String = API_NAMESPACE): String {
        val hash = MessageDigest.getInstance("SHA-512").digest(message.toByteArray())
        val signedData = concat("SSHSIG".toByteArray(), sshString(namespace), sshString(""), sshString("sha512"), sshString(hash))
        val raw = Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(signedData)
            sign()
        }
        val signature = concat(sshString("ssh-ed25519"), sshString(raw))

        return Base64.getEncoder().encodeToString(
            concat(
                "SSHSIG".toByteArray(),
                ByteBuffer.allocate(4).putInt(1).array(),
                sshString(publicKeyBlob()),
                sshString(namespace),
                sshString(""),
                sshString("sha512"),
                sshString(signature),
            ),
        )
    }

    /**
     * The headers of a signed request. The path includes the query, and the body is the exact JSON sent, or empty.
     */
    fun signedHeaders(method: String, path: String, body: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        val timestamp = nowSeconds.toString()
        val nonce = randomHex(16)

        return mapOf(
            "X-Devnull-Timestamp" to timestamp,
            "X-Devnull-Nonce" to nonce,
            "X-SSH-Signature" to sign(listOf(timestamp, nonce, method.uppercase(), path, body).joinToString("\n")),
        )
    }
}

private val random = SecureRandom()

fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

/**
 * A PKCE verifier and its S256 challenge, for exchanging the sign-in code for a device.
 */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        fun create(): Pkce {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val verifier = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

            return Pkce(verifier, encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
        }
    }
}
