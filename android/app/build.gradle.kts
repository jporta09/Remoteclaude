import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credenciales de firma de release (keystore.properties, gitignored). Si el archivo
// no existe, la firma de release queda sin configurar (assembleDebug igual anda).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.remoteclaude.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.remoteclaude.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
        // marvints.aar (Tailscale embebido) trae sólo el .so de arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(project(":terminal-view"))   // motor Termux vendorizado
    implementation("org.connectbot:sshlib:2.2.23")   // cliente SSH para Android (Apache-2.0)
    implementation(":marvints@aar")   // Tailscale embebido (tsnet vía gomobile)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // scanner QR (vincular Tailscale)
}
