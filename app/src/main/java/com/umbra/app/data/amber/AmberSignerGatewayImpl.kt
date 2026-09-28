package com.umbra.app.data.amber

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.umbra.app.data.security.SecurePreferences
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
    // The NIP-55 signer chosen at login. Not secret, but kept with the app's other private state.
    private val prefs by lazy { SecurePreferences(context, "nip55_signer") }

    /**
     * The signer every request goes to: the one chosen at login while it's still installed,
     * otherwise the only installed signer, otherwise Amber (whose absence then surfaces as the
     * usual "no signer" path).
     */
    private fun signerPackage(): String {
        val installed = AmberConnector.installedSignerPackages(context)
        prefs.getString(KEY_PACKAGE)?.takeIf { it in installed }?.let { return it }
        return installed.singleOrNull() ?: installed.firstOrNull { it == AmberConnector.AMBER_PACKAGE } ?: installed.firstOrNull() ?: AmberConnector.AMBER_PACKAGE
    }

    private fun rememberSigner(result: Intent?) {
        val chosen = AmberConnector.extractSignerPackageFromResult(result)
            ?: AmberConnector.installedSignerPackages(context).singleOrNull()
            ?: return
        prefs.putString(KEY_PACKAGE, chosen)
    }

    override fun isAmberInstalled(): Boolean = AmberConnector.isSignerInstalled(context)

    // Several signers installed: no package, so Android asks which one to use.
    override fun createLoginIntent(): Intent =
        AmberConnector.createLoginIntent(AmberConnector.installedSignerPackages(context).singleOrNull())

    override fun createSignEventIntent(eventJson: String, currentUserHex: String?): Intent {
        return AmberConnector.createSignEventIntent(signerPackage(), eventJson, currentUserHex)
    }

    override fun createStoreIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = AmberConnector.getAmberAppUri().toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun extractPublicKeyFromResult(data: Intent?): String? {
        return AmberConnector.extractPublicKeyFromResult(data)?.also { rememberSigner(data) }
    }

    override fun extractSignedEventFromResult(data: Intent?): String? {
        return AmberConnector.extractSignedEventFromResult(data)
    }

    override suspend fun trySignEventInBackground(eventJson: String, currentUserHex: String?): String? {
        return withContext(Dispatchers.IO) {
            AmberConnector.trySignEventContentResolver(context, signerPackage(), eventJson, currentUserHex)
        }
    }

    override suspend fun signEvent(eventJson: String, currentUserHex: String?): String? {
        trySignEventInBackground(eventJson, currentUserHex)?.let { return it }
        val result = requestCoordinator.launchAndAwait { AmberConnector.createSignEventIntent(signerPackage(), eventJson, currentUserHex) }
        return AmberConnector.extractSignedEventFromResult(result)
    }

    override suspend fun requestPublicKey(): String? {
        val result = requestCoordinator.launchAndAwait { createLoginIntent() }
        return extractPublicKeyFromResult(result)
    }

    override fun openStore(): Boolean = requestCoordinator.launch(createStoreIntent())

    override fun createNip44EncryptIntent(plaintext: String, pubkeyHex: String, currentUserHex: String?): Intent {
        return AmberConnector.createNip44EncryptIntent(signerPackage(), plaintext, pubkeyHex, currentUserHex)
    }

    override fun createNip44DecryptIntent(ciphertext: String, pubkeyHex: String, currentUserHex: String?): Intent {
        return AmberConnector.createNip44DecryptIntent(signerPackage(), ciphertext, pubkeyHex, currentUserHex)
    }

    override fun extractNip44ResultFromResult(data: Intent?): String? {
        return AmberConnector.extractNip44ResultFromResult(data)
    }

    override suspend fun tryNip44EncryptInBackground(plaintext: String, pubkeyHex: String, currentUserHex: String?): String? {
        return withContext(Dispatchers.IO) {
            AmberConnector.tryNip44EncryptContentResolver(context, signerPackage(), plaintext, pubkeyHex, currentUserHex)
        }
    }

    override suspend fun tryNip44DecryptInBackground(ciphertext: String, pubkeyHex: String, currentUserHex: String?): String? {
        return withContext(Dispatchers.IO) {
            AmberConnector.tryNip44DecryptContentResolver(context, signerPackage(), ciphertext, pubkeyHex, currentUserHex)
        }
    }

    override suspend fun nip44Encrypt(plaintext: String, pubkeyHex: String, currentUserHex: String?): String? {
        tryNip44EncryptInBackground(plaintext, pubkeyHex, currentUserHex)?.let { return it }
        val result = requestCoordinator.launchAndAwait { AmberConnector.createNip44EncryptIntent(signerPackage(), plaintext, pubkeyHex, currentUserHex) }
        return AmberConnector.extractNip44ResultFromResult(result)
    }

    override suspend fun nip44Decrypt(ciphertext: String, pubkeyHex: String, currentUserHex: String?): String? {
        tryNip44DecryptInBackground(ciphertext, pubkeyHex, currentUserHex)?.let { return it }
        val result = requestCoordinator.launchAndAwait { AmberConnector.createNip44DecryptIntent(signerPackage(), ciphertext, pubkeyHex, currentUserHex) }
        return AmberConnector.extractNip44ResultFromResult(result)
    }

    private companion object {
        const val KEY_PACKAGE = "package"
    }
}
