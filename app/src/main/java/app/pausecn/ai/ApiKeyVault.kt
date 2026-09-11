package app.pausecn.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ApiCredentialStore {
    fun hasKey(): Boolean
    fun read(): String
    fun save(value: String)
    fun clear()
}

/** Only ciphertext is persisted. Never export this store or fall back to plaintext. */
class ApiKeyVault(
    context: Context,
    preferenceName: String = "deepseek_credentials",
    private val alias: String = "pausecn.deepseek.v1",
) : ApiCredentialStore {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    override fun hasKey() = prefs.contains("ciphertext")

    @Synchronized override fun save(value: String) {
        require(value.length in 8..256 && value.all { it.code in 33..126 })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true))
        val bytes = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
        check(prefs.edit().putString("iv", encode(cipher.iv))
            .putString("ciphertext", encode(encrypted)).commit()) { "无法保存加密凭据" }
    }

    @Synchronized override fun read(): String {
        val ciphertext = requireNotNull(prefs.getString("ciphertext", null))
        val iv = requireNotNull(prefs.getString("iv", null))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false), GCMParameterSpec(128, decode(iv)))
        val bytes = cipher.doFinal(decode(ciphertext))
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    @Synchronized override fun clear() {
        check(prefs.edit().clear().commit()) { "无法删除凭据" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        check(!hasKey() && !store.containsAlias(alias))
    }

    private fun encryptionKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "加密凭据已失效，请重新填写 Key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
