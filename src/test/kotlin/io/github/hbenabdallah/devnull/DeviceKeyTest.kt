package io.github.hbenabdallah.devnull

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class DeviceKeyTest {
    private fun ByteBuffer.readString(): ByteArray = ByteArray(int).also { get(it) }

    @Test
    fun `has the fingerprint the web asks to approve, and survives being stored`() {
        val key = DeviceKey.generate()

        assertTrue(key.fingerprint().matches(Regex("^SHA256:[A-Za-z0-9+/]{43}$")))
        assertEquals(key.fingerprint(), DeviceKey.decode(key.encode()).fingerprint())
    }

    @Test
    fun `signs in the SSHSIG format, over the namespace and the SHA-512 of the message`() {
        val key = DeviceKey.generate()
        val blob = ByteBuffer.wrap(Base64.getDecoder().decode(key.sign("hello")))

        assertEquals("SSHSIG", String(ByteArray(6).also { blob.get(it) }))
        assertEquals(1, blob.int)
        val publicKey = blob.readString()
        assertArrayEquals(key.publicKeyBlob(), publicKey)
        assertEquals("devnull-api", String(blob.readString()))
        assertEquals(0, blob.readString().size)
        assertEquals("sha512", String(blob.readString()))
        val signature = ByteBuffer.wrap(blob.readString())
        assertEquals("ssh-ed25519", String(signature.readString()))
        val raw = signature.readString()

        val keyBlob = ByteBuffer.wrap(publicKey).also { it.readString() }.readString()
        val x509Header = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        val verifier = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(x509Header + keyBlob))
        val signed = "SSHSIG".toByteArray() + DeviceKey.sshString("devnull-api") + DeviceKey.sshString("") +
            DeviceKey.sshString("sha512") + DeviceKey.sshString(MessageDigest.getInstance("SHA-512").digest("hello".toByteArray()))

        assertTrue(Signature.getInstance("Ed25519").run { initVerify(verifier); update(signed); verify(raw) })
    }

    @Test
    fun `signs the timestamp, nonce, method, path and body of a request`() {
        val headers = DeviceKey.generate().signedHeaders("post", "/api/v1/commits", "{}", nowSeconds = 1793275200)

        assertEquals("1793275200", headers["X-Devnull-Timestamp"])
        assertTrue(headers.getValue("X-Devnull-Nonce").matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun `makes a PKCE verifier and challenge the web takes`() {
        val pkce = Pkce.create()

        assertTrue(pkce.verifier.matches(Regex("^[A-Za-z0-9._~-]{43,128}$")))
        assertEquals(
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray())),
            pkce.challenge,
        )
    }
}
