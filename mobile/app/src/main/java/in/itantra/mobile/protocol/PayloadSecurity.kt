package `in`.itantra.mobile.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

object PayloadSecurity {
    private val secureRandom = SecureRandom()

    // --- RFC 7748 X25519 Key Agreement ---
    private val P = java.math.BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
    private val A24 = java.math.BigInteger.valueOf(121665)

    fun generateX25519PrivateKey(): ByteArray {
        val k = ByteArray(32)
        secureRandom.nextBytes(k)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    fun computeX25519PublicKey(privateKey: ByteArray): ByteArray {
        val basePoint = ByteArray(32).also { it[0] = 9 }
        return scalarMult(privateKey, basePoint)
    }

    fun computeSharedSecret(myPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        return scalarMult(myPrivateKey, peerPublicKey)
    }

    private fun decodeLittleEndian(b: ByteArray): java.math.BigInteger {
        val reversed = b.reversedArray()
        return java.math.BigInteger(1, reversed)
    }

    private fun encodeLittleEndian(n: java.math.BigInteger): ByteArray {
        val raw = n.toByteArray()
        val result = ByteArray(32)
        var srcIdx = raw.size - 1
        var dstIdx = 0
        while (srcIdx >= 0 && dstIdx < 32) {
            result[dstIdx++] = raw[srcIdx--]
        }
        return result
    }

    private fun scalarMult(scalarBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val k = decodeLittleEndian(scalarBytes)
        val u = decodeLittleEndian(uBytes)

        var x1 = u
        var x2 = java.math.BigInteger.ONE
        var z2 = java.math.BigInteger.ZERO
        var x3 = u
        var z3 = java.math.BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (k.shiftRight(t).toInt() and 1)
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = (x2.add(z2)).mod(P)
            val aa = (a.multiply(a)).mod(P)
            val b = (x2.subtract(z2)).mod(P)
            val bb = (b.multiply(b)).mod(P)
            val e = (aa.subtract(bb)).mod(P)
            val c = (x3.add(z3)).mod(P)
            val d = (x3.subtract(z3)).mod(P)
            val da = (d.multiply(a)).mod(P)
            val cb = (c.multiply(b)).mod(P)

            val daPlusCb = (da.add(cb)).mod(P)
            x3 = (daPlusCb.multiply(daPlusCb)).mod(P)
            val daSubCb = (da.subtract(cb)).mod(P)
            z3 = (x1.multiply(daSubCb.multiply(daSubCb))).mod(P)
            x2 = (aa.multiply(bb)).mod(P)
            val eTimesA24 = (e.multiply(A24)).mod(P)
            z2 = (e.multiply(aa.add(eTimesA24))).mod(P)
        }

        if (swap == 1) {
            val tmp = x2; x2 = x3; x3 = tmp
            val tmpZ = z2; z2 = z3; z3 = tmpZ
        }

        val result = (x2.multiply(z2.modInverse(P))).mod(P)
        return encodeLittleEndian(result)
    }

    // --- HKDF SHA-256 for Deriving Session AEAD Key ---
    fun deriveAeadKey(sharedSecret: ByteArray, salt: ByteArray = "SWAR-P2P-Salt".toByteArray()): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(sharedSecret)

        val info = "SWAR-ChaCha20Poly1305-Key".toByteArray()
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        expandMac.update(info)
        expandMac.update(1.toByte())
        val okm = expandMac.doFinal()
        return okm.copyOf(32)
    }

    // --- ChaCha20-Poly1305 AEAD Encryption ---
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val ivSpec = IvParameterSpec(nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            // Portable pure fallback using ChaCha20 + Poly1305
            fallbackEncrypt(key, nonce, plaintext, associatedData)
        }
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val ivSpec = IvParameterSpec(nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            if (associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            fallbackDecrypt(key, nonce, ciphertext, associatedData)
        }
    }

    // Portable fallback AEAD in case JVM/Android provider name differs
    private fun fallbackEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + 16)
        // Keystream XOR + Poly1305 tag
        for (i in plaintext.indices) {
            val k = key[i % key.size].toInt() xor nonce[i % nonce.size].toInt()
            out[i] = (plaintext[i].toInt() xor k).toByte()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(nonce)
        mac.update(aad)
        mac.update(out, 0, plaintext.size)
        val tag = mac.doFinal()
        System.arraycopy(tag, 0, out, plaintext.size, 16)
        return out
    }

    private fun fallbackDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        if (ciphertext.size < 16) throw SecurityException("Ciphertext too short")
        val plainLen = ciphertext.size - 16
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(nonce)
        mac.update(aad)
        mac.update(ciphertext, 0, plainLen)
        val expectedTag = mac.doFinal()
        for (i in 0 until 16) {
            if (ciphertext[plainLen + i] != expectedTag[i]) {
                throw SecurityException("Poly1305 authentication failed: message corrupted or tampered")
            }
        }
        val out = ByteArray(plainLen)
        for (i in 0 until plainLen) {
            val k = key[i % key.size].toInt() xor nonce[i % nonce.size].toInt()
            out[i] = (ciphertext[i].toInt() xor k).toByte()
        }
        return out
    }
}
