package com.umbra.app.data.db

import android.content.Context
import android.util.Base64
import com.umbra.app.data.security.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedDatabasePassphraseProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "umbra_sqlcipher"
        private const val PASSPHRASE_KEY = "encrypted_db_passphrase"
        private const val PASSPHRASE_SIZE_BYTES = 32
    }

    private val securePreferences by lazy { SecurePreferences(context, PREFS_NAME) }

    fun getOrCreatePassphrase(): ByteArray {
        val persisted = securePreferences.getString(PASSPHRASE_KEY)
        if (!persisted.isNullOrBlank()) {
            return Base64.decode(persisted, Base64.NO_WRAP)
        }

        val generated = ByteArray(PASSPHRASE_SIZE_BYTES)
        SecureRandom().nextBytes(generated)
        securePreferences.putString(PASSPHRASE_KEY, Base64.encodeToString(generated, Base64.NO_WRAP))
        return generated
    }
}