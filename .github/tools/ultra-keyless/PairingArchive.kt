package com.sukisu.ultra.backend

import java.nio.ByteBuffer
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable backups are password encrypted. The normal root flow needs no password. */
object PairingArchive {
    private val magic = "UKP1".toByteArray(Charsets.US_ASCII)
    private const val maximumSize = 1024 * 1024

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size >= 12) { "备份密码至少需要 12 个字符" }
        val spec = PBEKeySpec(password, salt, 210000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximumSize) { "配对备份过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun seal(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size in 1..(maximumSize - 64))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt))
        cipher.updateAAD(magic)
        return magic + salt + cipher.iv + cipher.doFinal(payload)
    }

    fun open(archive: ByteArray, password: CharArray): ByteArray {
        require(archive.size in 49..maximumSize) { "配对备份格式或长度无效" }
        val data = ByteBuffer.wrap(archive)
        val header = ByteArray(4).also { data.get(it) }
        require(header.contentEquals(magic)) { "配对备份版本不受支持" }
        val salt = ByteArray(16).also { data.get(it) }
        val iv = ByteArray(12).also { data.get(it) }
        val encrypted = ByteArray(data.remaining()).also { data.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        return cipher.doFinal(encrypted)
    }
}
