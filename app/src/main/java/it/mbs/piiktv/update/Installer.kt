package it.mbs.piiktv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import it.mbs.piiktv.R
import java.io.File

/**
 * Installa l'APK scaricato con PackageInstaller: Android mostra la sua conferma («Vuoi
 * installare questo aggiornamento?») e, dopo l'OK col telecomando, aggiorna PIIK TV. Funziona
 * perché tutti gli APK sono firmati con la stessa chiave.
 */
object Installer {
    /** Android 8+: PIIK TV deve avere il permesso «installa app sconosciute». */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Apre l'impostazione del permesso; false se la TV non ha quella schermata. */
    fun openPermission(ctx: Context): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 26) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
        for (intent in intents) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("PiikTV.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Dopo l'aggiornamento Android chiude l'app: la si riapre (se il sistema lo permette). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            ctx.startActivity(Intent(ctx, it.mbs.piiktv.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

/** Esito dell'installazione: chiede la conferma all'utente o segnala l'errore. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // l'app viene sostituita e riavviata da Android
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // annullato con Indietro
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"
                Toast.makeText(ctx, ctx.getString(R.string.update_failed, reason), Toast.LENGTH_LONG).show()
            }
        }
    }
}
