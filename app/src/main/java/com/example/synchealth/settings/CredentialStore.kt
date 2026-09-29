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
 * Was die App braucht, um an den Wochenplan zu kommen.
 *
 * [marker] ist die eigene Marke, wie sie in der `note` eines Planeintrags steht, also mit
 * `@`. Jede Installation holt ihre eigene, deshalb wandern auch keine Zugangsdaten ins
 * fremde Haus.
 *
 * [eaters] ist die Zahl der Esser im Haushalt. Sie zaehlt nur fuer Eintraege ohne Marke,
 * also fuer die gemeinsamen Abendessen; wo Marken stehen, geben die den Teiler vor.
 */
data class TandoorAccess(
    val baseUrl: String,
    val token: String,
    val marker: String,
    val eaters: Int = DEFAULT_EATERS,
)

/** Zwei, denn der Haushalt sind Leo und Jazz. Ein Feld im Dialog ueberschreibt es. */
const val DEFAULT_EATERS = 2

/**
 * Bewahrt den Vault-Zugang auf. Basis-URL und Benutzername stehen im Klartext, das
 * App-Passwort verschluesselt mit einem AES/GCM-Schluessel aus dem Android Keystore.
 * Der Tandoor-Token liegt daneben und genauso verschluesselt, die Tandoor-Adresse und die
 * eigene Marke im Klartext: Beides ist kein Geheimnis.
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

    /**
     * Der Tandoor-Zugang, oder `null`, solange er nicht eingetragen ist. Dann bleibt es
     * bei Samsung Health allein.
     *
     * Fehlt eines der drei Felder, gilt der ganze Zugang als nicht eingetragen: Eine
     * Adresse ohne Token nuetzt nichts, und ein Token ohne Marke wuerde jeden Anteil auf
     * null rechnen und damit stillschweigend nichts beitragen.
     */
    fun loadTandoor(): TandoorAccess? {
        val url = prefs.getString(KEY_TANDOOR_URL, null) ?: return null
        val secret = prefs.getString(KEY_TANDOOR_TOKEN, null) ?: return null
        val token = decrypt(secret) ?: return null
        val marker = prefs.getString(KEY_TANDOOR_MARKER, null) ?: return null
        // Nicht `?: return null`: Eine Installation von vor diesem Feld kennt den
        // Schluessel nicht, und der ganze Tandoor-Zugang faellt sonst nach dem Update
        // weg, obwohl er verschluesselt danebenliegt. Siehe KEY_FOLDER.
        val eaters = prefs.getInt(KEY_TANDOOR_EATERS, DEFAULT_EATERS)
        return TandoorAccess(url, token, marker, eaters)
    }

    /** [access] als `null` loescht den Zugang wieder, etwa wenn die Felder geleert werden. */
    fun saveTandoor(access: TandoorAccess?) {
        val editor = prefs.edit()
        if (access == null) {
            editor.remove(KEY_TANDOOR_URL)
                .remove(KEY_TANDOOR_TOKEN)
                .remove(KEY_TANDOOR_MARKER)
                .remove(KEY_TANDOOR_EATERS)
        } else {
            editor.putString(KEY_TANDOOR_URL, access.baseUrl.trimEnd('/'))
                .putString(KEY_TANDOOR_TOKEN, encrypt(access.token))
                .putString(KEY_TANDOOR_MARKER, marker(access.marker))
                .putInt(KEY_TANDOOR_EATERS, access.eaters)
        }
        editor.apply()
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
        private const val KEY_TANDOOR_URL = "tandoor_url"
        private const val KEY_TANDOOR_TOKEN = "tandoor_token"
        private const val KEY_TANDOOR_MARKER = "tandoor_marke"
        private const val KEY_TANDOOR_EATERS = "tandoor_esser"
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
        /**
         * Die Marke so, wie sie in der `note` steht: klein und mit genau einem `@` davor.
         * Ohne diese Normalisierung haengt der Abgleich daran, ob beim Eintippen ein `@`
         * mitgetippt wurde, und ein fehlendes waere still: Es rechnet jeden Anteil auf
         * null statt einen Fehler zu melden.
         */
        fun marker(input: String): String = "@" + input.trim().trimStart('@').lowercase()

        fun validateBaseUrl(input: String): String? = when {
            input.isBlank() -> "Die Basis-URL fehlt."
            !input.startsWith("https://", ignoreCase = true) ->
                "Nur https. Über http ginge dein App-Passwort im Klartext raus."
            input.toHttpUrlOrNull() == null -> "Das ist keine gültige Adresse."
            else -> null
        }
    }
}
