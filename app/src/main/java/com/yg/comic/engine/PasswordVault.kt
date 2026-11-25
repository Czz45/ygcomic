package com.yg.comic.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 压缩包密码永久保存在 Android Keystore，不落明文到数据库 */
class PasswordVault(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("ygcomic_vault", Context.MODE_PRIVATE)
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val alias = "ygcomic_password_key"
        (keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    fun save(fragmentKey: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val enc = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${fragmentKey}_iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .putString("${fragmentKey}_enc", android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP))
            .apply()
    }

    fun load(fragmentKey: String): String? {
        val ivS = prefs.getString("${fragmentKey}_iv", null) ?: return null
        val encS = prefs.getString("${fragmentKey}_enc", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, android.util.Base64.decode(ivS, android.util.Base64.NO_WRAP)),
            )
            String(cipher.doFinal(android.util.Base64.decode(encS, android.util.Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(fragmentKey: String): Boolean = prefs.contains("${fragmentKey}_enc")
    fun remove(fragmentKey: String) {
        prefs.edit().remove("${fragmentKey}_iv").remove("${fragmentKey}_enc").apply()
    }
}
