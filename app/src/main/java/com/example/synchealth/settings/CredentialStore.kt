package com.example.synchealth.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.synchealth.vault.DailyNoteSync
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Was die App braucht, um an den Vault zu kommen. */
data class VaultAccess(
    val baseUrl: String,
    val username: String,
    val password: String,
    /** Ordner der Tagesnotizen, relativ zur Basis-URL. Leer heisst: direkt im Vault-Root. */
    val dailyNotes: String = DailyNoteSync.DEFAULT_DAILY_NOTES,
)

/**
 * Bewahrt den Vault-Zugang auf. Basis-URL und Benutzername stehen im Klartext, das
 * App-Passwort verschluesselt mit einem AES/GCM-Schluessel aus dem Android Keystore.
 *
 * Der Schluessel selbst verlaesst den Keystore nie, ein Auslesen der Preferences-Datei
 * bringt also nur den Chiffretext. Bewusst ohne `androidx.security:security-crypto`:
 * die Bibliothek ist eingestellt, und fuer ein einzelnes Passwort sind das hier
 * sechzig Zeilen statt einer Abhaengigkeit, die niemand mehr pflegt.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): VaultAccess? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val secret = prefs.getString(KEY_PASSWORD, null) ?: return null
        val password = decrypt(secret) ?: return null
        // Bewusst kein "?: return null": Eine Installation von vor diesem Feld hat den
        // Schluessel nicht und wuerde sonst ihren ganzen Zugang verlieren.
        val folder = prefs.getString(KEY_FOLDER, null) ?: DailyNoteSync.DEFAULT_DAILY_NOTES
        return VaultAccess(url, user, password, folder)
    }

    fun save(access: VaultAccess) {
        prefs.edit()
            .putString(KEY_URL, access.baseUrl.trimEnd('/'))
            .putString(KEY_USER, access.username)
            .putString(KEY_PASSWORD, encrypt(access.password))
            // Schraegstriche an den Raendern raus, sonst entsteht beim Zusammensetzen
            // des Pfads ein leeres Segment.
            .putString(KEY_FOLDER, access.dailyNotes.trim().trim('/'))
            .apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // Der IV wird bei GCM je Vorgang neu erzeugt und muss mit abgelegt werden.
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
        )
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE = "vault-zugang"
        private const val KEY_URL = "basis_url"
        private const val KEY_USER = "benutzer"
        private const val KEY_PASSWORD = "app_passwort"
        private const val KEY_FOLDER = "tagesnotizen"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "synchealth-vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /**
         * Gibt einen Fehlertext zurueck oder null, wenn die Adresse taugt.
         *
         * Hier und nicht im [com.example.synchealth.vault.WebDavClient] sitzt die
         * https-Pflicht: an dieser Stelle kommt die Eingabe herein, und der Client bleibt
         * gegen einen lokalen Testserver pruefbar. Ueber http ginge das App-Passwort
         * lesbar auf die Leitung, Basic Auth ist nur Base64 und keine Verschluesselung.
         */
        fun validateBaseUrl(input: String): String? = when {
            input.isBlank() -> "Die Basis-URL fehlt."
            !input.startsWith("https://", ignoreCase = true) ->
                "Nur https. Über http ginge dein App-Passwort im Klartext raus."
            input.toHttpUrlOrNull() == null -> "Das ist keine gültige Adresse."
            else -> null
        }
    }
}
