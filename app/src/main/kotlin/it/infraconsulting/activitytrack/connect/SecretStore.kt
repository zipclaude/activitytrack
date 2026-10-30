/* SPDX-License-Identifier: GPL-3.0-or-later */
package it.infraconsulting.activitytrack.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Connector secrets (tokens, passwords) per company and field (M4, owner
 * decision 2026-10-17): AES-256-GCM with a non-exportable Android Keystore
 * key; only the ciphertext reaches the preferences file, which Android
 * backup never copies (allowBackup=false). Lost with the app data by design.
 */
class SecretStore(context: Context) {
	private val prefs = context.applicationContext.getSharedPreferences("connector_secrets", Context.MODE_PRIVATE)

	fun put(companyId: Long, field: String, value: String?) = store("$companyId/$field", value)

	fun get(companyId: Long, field: String): String? = load("$companyId/$field")

	/* the app's own secrets (the sync token, issue #113), apart from the companies' */
	fun putApp(field: String, value: String?) = store("app/$field", value)

	fun getApp(field: String): String? = load("app/$field")

	private fun store(name: String, value: String?) {
		if (value.isNullOrEmpty()) {
			prefs.edit { remove(name) }
			return
		}
		val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
		val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
		prefs.edit { putString(name, b64(cipher.iv) + ":" + b64(data)) }
	}

	private fun load(name: String): String? {
		val stored = prefs.getString(name, null) ?: return null
		val (iv, data) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
		val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
		return String(cipher.doFinal(data), Charsets.UTF_8)
	}

	fun has(companyId: Long, field: String): Boolean = prefs.contains("$companyId/$field")

	fun clear(companyId: Long) {
		prefs.edit { prefs.all.keys.filter { it.startsWith("$companyId/") }.forEach { remove(it) } }
	}

	private fun key(): SecretKey {
		val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
			init(
				KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
					.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setKeySize(256)
					.build(),
			)
		}.generateKey()
	}

	private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

	private companion object {
		const val KEYSTORE = "AndroidKeyStore"
		const val ALIAS = "connector-secrets"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
	}
}
