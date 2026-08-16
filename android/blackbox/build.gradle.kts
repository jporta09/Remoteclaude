// Validación en CAJA NEGRA del APK de release.
//
// Por qué existe como módulo aparte y no como un test más de `:app`: la instrumentación
// que vive en `:app` se carga DENTRO del proceso de la app y enlaza contra sus clases, y
// R8 minifica sin enterarse de que los tests existen. Por eso `make e2e-release` necesita
// `proguard-rules-e2e.pro`, y por eso el APK que valida NO es el que se publica — la
// brecha que este módulo cierra.
//
// Acá no hay ninguna dependencia con `:app`: los tests manejan la app desde afuera con
// UI Automator, como lo haría una persona. El APK bajo prueba se instala con `adb` y su
// DEX es byte a byte el publicado (lo comprueba `scripts/e2e-blackbox.sh`).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.remoteclaude.blackbox"
    compileSdk = 34

    defaultConfig {
        // applicationId propio: esto NO es la app, es un contenedor vacío para que el
        // runner de instrumentación tenga dónde vivir.
        applicationId = "com.remoteclaude.blackbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.google.truth:truth:1.4.2")
    // Canal de control contra el fixture: autorizar la clave que la app muestra en pantalla
    // y preguntarle al host qué pasó de verdad. Es la MISMA librería que usa la app, pero
    // acá vive sólo en el APK de test: no toca el APK bajo prueba.
    androidTestImplementation("org.connectbot:sshlib:2.2.23")
}
