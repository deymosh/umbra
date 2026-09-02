package com.umbra.app.data.amber

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.umbra.app.domain.nip44.Nip44Gateway
import com.umbra.app.domain.nip55.AmberSignerGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AmberSignerGatewayImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requestCoordinator: AmberRequestCoordinator
) : AmberSignerGateway, Nip44Gateway {
    override fun isAmberInstalled(): Boolean = AmberConnector.isAmberInstalled(context)

    override fun createLoginIntent(): Intent = AmberConnector.createLoginIntent()

    override fun createSignEventIntent(eventJson: String, currentUserHex: String?): Intent {
        return AmberConnector.createSignEventIntent(eventJson, currentUserHex)
    }

    override fun createStoreIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = AmberConnector.getAmberAppUri().toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun extractPublicKeyFromResult(data: Intent?): String? {
        return AmberConnector.extractPublicKeyFromResult(data)
    }

    override fun extractSignedEventFromResult(data: Intent?): String? {
        return AmberConnector.extractSignedEventFromResult(data)
    }

    override suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String? {
        return withContext(Dispatchers.IO) {
            AmberConnector.trySignEventContentResolver(context, eventJson, currentUserHex)
        }
    }

    override suspend fun signEvent(eventJson: String, currentUserHex: String?): String? {
        trySignEventInBackground(eventJson, currentUserHex)?.let { return it }
        val result = requestCoordinator.launchAndAwait { AmberConnector.createSignEventIntent(eventJson, currentUserHex) }
        return AmberConnector.extractSignedEventFromResult(result)
    }

    override suspend fun requestPublicKey(): String? {
        val result = requestCoordinator.launchAndAwait { AmberConnector.createLoginIntent() }
        return AmberConnector.extractPublicKeyFromResult(result)
    }

    override fun openStore(): Boolean = requestCoordinator.launch(createStoreIntent())

    override fun createNip44EncryptIntent(plaintext: String, pubkeyHex: String, currentUserHex: String?): Intent {
        return AmberConnector.createNip44EncryptIntent(plaintext, pubkeyHex, currentUserHex)
    }

    override fun createNip44DecryptIntent(ciphertext: String, pubkeyHex: String, currentUserHex: String?): Intent {
        return AmberConnector.createNip44DecryptIntent(ciphertext, pubkeyHex, currentUserHex)
    }

    override fun extractNip44ResultFromResult(data: Intent?): String? {
        return AmberConnector.extractNip44ResultFromResult(data)
    }

    override suspend fun tryNip44EncryptInBackground(plaintext: String, pubkeyHex: String, currentUserHex: String?): String? {
        return withContext(Dispatchers.IO) {
            AmberConnector.tryNip44EncryptContentResolver(context, plaintext, pubkeyHex, currentUserHex)
        }
    }

    override suspend fun tryNip44DecryptInBackground(ciphertext: String, pubkeyHex: String, currentUserHex: String?): String? {
        return withContext(Dispatchers.IO) {
            AmberConnector.tryNip44DecryptContentResolver(context, ciphertext, pubkeyHex, currentUserHex)
        }
    }

    override suspend fun nip44Encrypt(plaintext: String, pubkeyHex: String, currentUserHex: String?): String? {
        tryNip44EncryptInBackground(plaintext, pubkeyHex, currentUserHex)?.let { return it }
        val result = requestCoordinator.launchAndAwait { AmberConnector.createNip44EncryptIntent(plaintext, pubkeyHex, currentUserHex) }
        return AmberConnector.extractNip44ResultFromResult(result)
    }

    override suspend fun nip44Decrypt(ciphertext: String, pubkeyHex: String, currentUserHex: String?): String? {
        tryNip44DecryptInBackground(ciphertext, pubkeyHex, currentUserHex)?.let { return it }
        val result = requestCoordinator.launchAndAwait { AmberConnector.createNip44DecryptIntent(ciphertext, pubkeyHex, currentUserHex) }
        return AmberConnector.extractNip44ResultFromResult(result)
    }
}