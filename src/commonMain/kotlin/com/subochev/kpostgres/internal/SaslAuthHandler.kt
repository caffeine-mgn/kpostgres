package com.subochev.kpostgres.internal

import com.subochev.kpostgres.PostgresConfig
import com.subochev.kpostgres.PostgresException
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.utils.io.writeFully
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

@OptIn(ExperimentalEncodingApi::class, dev.whyoleg.cryptography.CryptographyProviderApi::class)
internal class SaslAuthHandler {

    private val base64: Base64 = Base64.Default

    suspend fun start(conn: PgConnection, config: PostgresConfig, saslFrame: ServerMessage) {
        try {
            startInternal(conn, config, saslFrame)
        } catch (e: PostgresException) {
            saslFrame.end()
            throw e
        }
    }

    private suspend fun startInternal(conn: PgConnection, config: PostgresConfig, saslFrame: ServerMessage) {
        val mechanisms = saslFrame.readCString()
        saslFrame.end()

        if (!mechanisms.split(",").any { it.trim().equals("SCRAM-SHA-256", ignoreCase = true) }) {
            throw PostgresException("Server did not offer SCRAM-SHA-256; offered: $mechanisms")
        }

        val provider = CryptographyProvider.Default
        val clientNonce = generateNonce(18)

        val clientFirstBare = "n=${saslName(config.user)},r=$clientNonce"
        val clientFirst = "n,,$clientFirstBare"

        conn.sendSaslInitialResponse("SCRAM-SHA-256", clientFirst)

        val continueFrame = conn.receiveFrame()
        require(continueFrame.tag == MessageTag.AUTHENTICATION) {
            "Expected Authentication message, got tag=${continueFrame.tag.toInt()}"
        }
        val continueAuthType = continueFrame.readInt()
        if (continueAuthType != AuthType.SASL_CONTINUE) {
            continueFrame.end()
            throw PostgresException("Expected SASLContinue ($AuthType.SASL_CONTINUE), got $continueAuthType")
        }
        val serverFirst = readSaslString(continueFrame)
        continueFrame.end()

        val parsed = parseServerFirst(serverFirst)
        val salt = base64.decode(parsed.salt)
        val iterations = parsed.iterations
        val combinedNonce = parsed.nonce

        val saltedPassword = pbkdf2Sha256(
            password = config.password,
            salt = salt,
            iterations = iterations,
            outputSize = 32,
            provider = provider,
        )

        val clientKey = hmacSha256(saltedPassword, "Client Key".encodeToByteArrayUtf8(), provider)
        val storedKey = sha256(clientKey, provider)

        val channelBinding = "c=biws"
        val clientFinalNoProof = "$channelBinding,r=$combinedNonce"
        val authMessage = "$clientFirstBare,$serverFirst,$clientFinalNoProof"

        val clientSignature = hmacSha256(storedKey, authMessage.encodeToByteArrayUtf8(), provider)
        val proof = xor(clientKey, clientSignature)
        val proofBase64 = base64.encode(proof)

        val clientFinal = "$clientFinalNoProof,p=$proofBase64"

        conn.sendSaslResponse(clientFinal)

        val finalFrame = conn.receiveFrame()
        if (finalFrame.tag == MessageTag.ERROR_RESPONSE) {
            val err = readErrorResponse(finalFrame)
            finalFrame.end()
            throw PostgresException("SCRAM final: $err")
        }
        require(finalFrame.tag == MessageTag.AUTHENTICATION) {
            "Expected Authentication message after client-final, got tag=${finalFrame.tag.toInt()}"
        }
        val finalAuthType = finalFrame.readInt()
        if (finalAuthType != AuthType.SASL_FINAL) {
            finalFrame.end()
            throw PostgresException("Expected SASLFinal ($AuthType.SASL_FINAL), got $finalAuthType")
        }
        val serverFinal = readSaslString(finalFrame)
        finalFrame.end()

        if (serverFinal.startsWith("v=")) {
            val serverSignature = base64.decode(serverFinal.substring(2))
            val serverKey = hmacSha256(saltedPassword, "Server Key".encodeToByteArrayUtf8(), provider)
            val expectedServerSig = hmacSha256(serverKey, authMessage.encodeToByteArrayUtf8(), provider)
            if (!constantTimeEquals(serverSignature, expectedServerSig)) {
                throw PostgresException("Server signature verification failed")
            }
        } else if (serverFinal.startsWith("e=")) {
            throw PostgresException("SCRAM error from server: ${serverFinal.substring(2)}")
        } else {
            throw PostgresException("Unexpected server-final: $serverFinal")
        }
    }

    private suspend fun pbkdf2Sha256(
        password: String,
        salt: ByteArray,
        iterations: Int,
        outputSize: Int,
        provider: CryptographyProvider,
    ): ByteArray {
        val derivation = provider.get(PBKDF2).secretDerivation(
            digest = SHA256,
            iterations = iterations,
            outputSize = outputSize.bytes,
            salt = salt,
        )
        return derivation.deriveSecretToByteArray(password.encodeToByteArrayUtf8())
    }

    private suspend fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
        provider: CryptographyProvider,
    ): ByteArray {
        val hmacKey = provider.get(HMAC).keyDecoder(SHA256).decodeFromByteArrayBlocking(
            format = HMAC.Key.Format.RAW,
            bytes = key,
        )
        return hmacKey.signatureGenerator().generateSignature(data)
    }

    private suspend fun sha256(data: ByteArray, provider: CryptographyProvider): ByteArray {
        return provider.get(SHA256).hasher().hash(data)
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size)
        val out = ByteArray(a.size)
        for (i in a.indices) {
            out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun saslName(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            if (c == ',') sb.append("=2C")
            else if (c == '=') sb.append("=3D")
            else sb.append(c)
        }
        return sb.toString()
    }

    private fun generateNonce(length: Int): String {
        val bytes = ByteArray(length)
        kotlin.random.Random.nextBytes(bytes)
        return base64.encode(bytes)
    }

    private fun String.encodeToByteArrayUtf8(): ByteArray {
        val buf = Buffer()
        buf.writeString(this)
        return buf.readByteArray()
    }

    private data class ServerFirst(val nonce: String, val salt: String, val iterations: Int)

    private fun parseServerFirst(serverFirst: String): ServerFirst {
        val parts = serverFirst.split(",")
        var nonce: String? = null
        var salt: String? = null
        var iterations: Int? = null
        for (p in parts) {
            when {
                p.startsWith("r=") -> nonce = p.substring(2)
                p.startsWith("s=") -> salt = p.substring(2)
                p.startsWith("i=") -> iterations = p.substring(2).toIntOrNull()
                    ?: throw PostgresException("Bad SCRAM iteration count: ${p.substring(2)}")
            }
        }
        if (nonce == null || salt == null || iterations == null) {
            throw PostgresException("Malformed server-first message: $serverFirst")
        }
        return ServerFirst(nonce, salt, iterations)
    }
}

internal suspend fun PgConnection.sendSaslInitialResponse(mechanism: String, initialResponse: String) {
    val body = Buffer()
    body.writeString(mechanism)
    body.writeByte(0)
    val initBytes = initialResponse.toUtf8Bytes()
    body.writeInt(initBytes.size)
    body.write(initBytes)
    val bodyBytes = body.readByteArray()
    val totalLen = bodyBytes.size + 4
    val out = ByteArray(1 + totalLen)
    out[0] = MessageTag.PASSWORD
    out[1] = (totalLen shr 24).toByte()
    out[2] = (totalLen shr 16).toByte()
    out[3] = (totalLen shr 8).toByte()
    out[4] = totalLen.toByte()
    bodyBytes.copyInto(out, destinationOffset = 5)
    writeChannel.writeFully(out, 0, out.size)
    writeChannel.flush()
}

internal suspend fun PgConnection.sendSaslResponse(clientFinal: String) {
    val payload = clientFinal.toUtf8Bytes()
    val bodyLen = payload.size
    val totalLen = bodyLen + 4
    val out = ByteArray(1 + totalLen)
    out[0] = MessageTag.PASSWORD
    out[1] = (totalLen shr 24).toByte()
    out[2] = (totalLen shr 16).toByte()
    out[3] = (totalLen shr 8).toByte()
    out[4] = totalLen.toByte()
    payload.copyInto(out, destinationOffset = 5)
    writeChannel.writeFully(out, 0, out.size)
    writeChannel.flush()
}

private fun String.toUtf8Bytes(): ByteArray {
    val buf = Buffer()
    buf.writeString(this)
    return buf.readByteArray()
}

private suspend fun readSaslString(frame: ServerMessage): String {
    val remaining = frame.remainingBytes()
    val bytes = frame.readBytes(remaining)
    val buf = Buffer()
    buf.write(bytes)
    return buf.readString()
}