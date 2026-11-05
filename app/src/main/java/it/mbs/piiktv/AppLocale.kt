package it.mbs.piiktv

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Lingua dell'interfaccia (impostazione «Language / Lingua», salvata in [Prefs.language]).
 *
 * "Automatica" non cambia nulla: valgono la lingua di sistema e la scelta normale delle risorse
 * (TV in italiano = values-it, TV in cinese = values-zh, altre lingue = inglese). Con una lingua scelta si usa un
 * Context la cui configurazione cambia solo la lingua (Configuration.setLocale +
 * createConfigurationContext, presenti da Android 4.2: nessuna dipendenza da AppCompat).
 */
object AppLocale {
    const val AUTO = ""
    const val ENGLISH = "en"
    const val ITALIAN = "it"
    const val CHINESE = "zh"

    /** [base] nella lingua scelta nell'app; con "automatica" (o un valore sconosciuto) [base] stesso. */
    fun wrap(base: Context): Context = when (val tag = Prefs.languageOf(base)) {
        ENGLISH, ITALIAN, CHINESE -> forLanguage(base, tag)
        else -> base
    }

    /** Context con le risorse nella lingua [tag]: "en" = risorse predefinite, "it" = values-it, "zh" = values-zh. */
    fun forLanguage(base: Context, tag: String): Context {
        // Configurazione "delta" applicata sopra quella corrente: solo la lingua (e la direzione del
        // testo che ne deriva). fontScale va azzerato perché fino ad Android 7.1 il costruttore lo
        // imposta a 1, che annullerebbe la dimensione del testo scelta nelle impostazioni di sistema.
        val delta = Configuration()
        delta.fontScale = 0f
        delta.setLocale(Locale(tag))
        return base.createConfigurationContext(delta)
    }
}
