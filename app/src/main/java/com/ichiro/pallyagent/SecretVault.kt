package com.ichiro.pallyagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small device-local AES-GCM vault backed by an Android Keystore key. */
object SecretVault {
    private const val alias = "pally_agent_local_secret_v1"
    private const val prefsName = "pally_agent_encrypted_secrets"

    fun save(context: Context, name: String, value: String) {
        if (value.isEmpty()) {
            clear(context, name)
            return
        }
        // An empty Compose snapshot can occur while the first history read is still running.
        // Do not let that startup snapshot erase an already-saved encrypted conversation.
        if (name.startsWith("chat_history:") && value == "[]") return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context, name: String): String? {
        val encoded = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val iv = packed.copyOfRange(0, 12)
            val data = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(context, name)
            null
        }
    }

    fun clear(context: Context, name: String) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().remove(name).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
