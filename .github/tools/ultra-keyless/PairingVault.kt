package com.sukisu.ultra.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class PairingVault(private val context: Context) {
    private val storage = AtomicFile(File(context.noBackupFilesDir, "kernelpatch-pairing.sealed"))
    private fun identifier(bytes: ByteArray): String {
        check(bytes.size > 64) { "本机配对数据损坏" }
        val id = String(bytes.copyOfRange(0, 36), Charsets.US_ASCII)
        check(java.util.UUID.fromString(id).toString() == id) { "配对存储标识无效" }
        return id
    }

    private fun encryptionKey(id: String, create: Boolean): SecretKey {
        val alias = "UltraKernelPatchPairingV1-$id"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create) { "本机配对密钥已丢失，请恢复加密配对备份" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized
    fun read(): JSONObject {
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val value = JSONObject().put("version", 1)
                .put("credential", bytes.joinToString("") { "%02x".format(it) })
                .put("grants", JSONObject())
            write(value)
            return value
        }
        val sealed = storage.readFully()
        val id = identifier(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(id, false), GCMParameterSpec(128, sealed.copyOfRange(36, 48)))
        val value = JSONObject(String(cipher.doFinal(sealed.copyOfRange(48, sealed.size)), Charsets.UTF_8))
        validate(value)
        return value
    }

    @Synchronized
    fun write(value: JSONObject, newKey: Boolean = false) {
        validate(value)
        val id = if (newKey || (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists())) {
            java.util.UUID.randomUUID().toString()
        } else identifier(storage.readFully())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(id, true))
        val encrypted = id.toByteArray(Charsets.US_ASCII) + cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val stream = storage.startWrite()
        try {
            stream.write(encrypted)
            stream.fd.sync()
            storage.finishWrite(stream)
        } catch (failure: Exception) {
            storage.failWrite(stream)
            throw failure
        }
        check(storage.readFully().contentEquals(encrypted)) { "加密配对数据读回校验失败" }
    }

    fun validate(value: JSONObject) {
        require(value.getInt("version") == 1) { "不支持的配对版本" }
        require(value.getString("credential").matches(Regex("[0-9a-f]{48}"))) { "配对凭证无效" }
        val grants = value.getJSONObject("grants")
        require(grants.length() <= 4096)
        for (key in grants.keys()) {
            val record = grants.getJSONObject(key)
            require(key.toInt() >= 0 && record.getInt("uid") == key.toInt())
            require(record.getString("package").isNotBlank())
            require(record.getJSONArray("certificates").length() > 0)
        }
    }

    @Suppress("DEPRECATION")
    fun identity(packageName: String): JSONObject {
        val info = context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        val signatures = info.signatures.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }.sorted()
        require(signatures.isNotEmpty()) { "无法核对应用签名" }
        return JSONObject().put("package", packageName).put("uid", info.applicationInfo!!.uid)
            .put("certificates", JSONArray(signatures))
    }

    fun matches(record: JSONObject): Boolean = try {
        val actual = identity(record.getString("package"))
        actual.getInt("uid") == record.getInt("uid") &&
            actual.getJSONArray("certificates").toString() == record.getJSONArray("certificates").toString()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }
}
