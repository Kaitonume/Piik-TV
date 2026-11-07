import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.mbs.piiktv"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.mbs.piiktv"
        minSdk = 21
        targetSdk = 34
        versionCode = 10
        versionName = "1.7.0"
    }

    // Un APK per architettura più uno universale con tutte e quattro, tutti con lo stesso versionCode:
    // qualsiasi APK aggiorna qualsiasi altro. La TV TCL (Realtek RTD6748) ha userland solo a 32 bit:
    // APK armeabi-v7a, oppure l'universale (Android installa da sé le librerie della sua architettura).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Chiave di firma dedicata: gli aggiornamenti futuri si installano sopra. Il file di proprietà
    // (storeFile, storePassword, keyAlias, keyPassword) si cerca, nell'ordine, in:
    //  1) il percorso nella variabile d'ambiente PIIKTV_KEYSTORE_PROPERTIES;
    //  2) ../keystore/keystore.properties (cartella accanto a quella del progetto, fuori dal repository);
    //  3) keystore/keystore.properties nel progetto (posizione storica).
    // storeFile è relativo alla cartella del file di proprietà (per compatibilità anche alla radice
    // del progetto). Se non c'è nessun file si firma con la chiave di debug.
    val ksEnv = System.getenv("PIIKTV_KEYSTORE_PROPERTIES")?.takeIf { it.isNotBlank() }
    if (ksEnv != null && !rootProject.file(ksEnv).isFile) {
        logger.warn("PIIKTV_KEYSTORE_PROPERTIES: file non trovato ($ksEnv), cerco il keystore altrove")
    }
    val ksPropsFile: File? = listOfNotNull(
        ksEnv?.let { rootProject.file(it) },
        rootProject.file("../keystore/keystore.properties"),
        rootProject.file("keystore/keystore.properties"),
    ).firstOrNull { it.isFile }
    val ksProps = Properties().apply {
        ksPropsFile?.inputStream()?.use { load(it) }
    }
    val ksStoreFile: File? = ksProps.getProperty("storeFile")?.let { path ->
        val besideProps = File(ksPropsFile!!.parentFile, path)
        when {
            File(path).isAbsolute -> File(path)
            besideProps.exists() -> besideProps
            rootProject.file(path).exists() -> rootProject.file(path)
            else -> besideProps
        }
    }
    signingConfigs {
        create("release") {
            if (ksProps.isNotEmpty()) {
                storeFile = ksStoreFile
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    // La lingua si sceglie anche nell'app: in un eventuale App Bundle tutte le lingue restano nella
    // base (niente split per lingua), altrimenti mancherebbero i testi della lingua scelta.
    bundle {
        language {
            enableSplit = false
        }
    }
}

// Nomi degli APK: PiikTV-<versione>-<abi>.apk e PiikTV-<versione>-universal.apk (debug: suffisso -debug).
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier ?: "universal"
            (output as VariantOutputImpl).outputFileName.set(
                output.versionName.map { "PiikTV-$it-$abi$suffix.apk" }
            )
        }
    }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
