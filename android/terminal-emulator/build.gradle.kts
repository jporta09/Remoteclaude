// Motor de emulación VT/xterm de Termux (GPLv3), vendorizado.
// Modificado para Remoteclaude: sin el spawner JNI/PTY (ver TerminalSession).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
}
