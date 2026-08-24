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
        // 39/40 fueron los vc de v1.28.0/v1.29.0, publicados brevemente y revertidos: se
        // saltean para que cualquier celu que los haya instalado actualice igual. Y el NOMBRE
        // también saltea 1.28.0: Obtainium compara nombres, y contra el "1.28.0" efímero que
        // quedó instalado no ofrecía actualizar (el versionCode no lo mira).
        versionCode = 45
        versionName = "1.29.0"
        testInstrumentationRunner = "com.remoteclaude.app.MarvinTestRunner"
        // Producción: sólo arm64 (es lo que corre en el teléfono). El AAR además trae
        // x86_64, que se incluye únicamente en debug para que el emulador corra NATIVO
        // en vez de bajo traducción ARM — ver test/e2e/README.md.
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
        debug {
            // x86_64 además de arm64: el emulador de los E2E es x86_64 y así no depende
            // de la traducción ARM (más rápido y más fiel).
            ndk { abiFilters += "x86_64" }
            // Se firma con la MISMA clave que release: así el APK de debug (y el de
            // instrumentación) entran como actualización sobre la app instalada, sin
            // desinstalar. Desinstalar borraría la clave del Keystore y la auth key de
            // Tailscale del usuario.
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // R8 activado: además de achicar, ofusca. Las reglas de proguard-rules.pro
            // conservan el binding de gomobile (JNI lo busca por nombre) y trilead.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.hasProperty("marvinTestRelease")) {
                // Sólo al validar R8 con la suite: keeps que necesita el runner de
                // instrumentación y que el APK publicado NO lleva (ver el archivo).
                proguardFile("proguard-rules-e2e.pro")
            }
            // La ABI del emulador va SEPARADA de las keeps. Estaban juntas, y esa
            // coincidencia era justamente la brecha: no había forma de pedir "el APK
            // publicado, pero instalable en el emulador". Con -PmarvinEmuAbi se agrega
            // x86_64 sin tocar una sola regla de R8, así el DEX sale idéntico al que se
            // publica — y `verificarDexIdenticoAlPublicado` lo comprueba en vez de
            // pedir que se le crea.
            if (project.hasProperty("marvinTestRelease") || project.hasProperty("marvinEmuAbi")) {
                ndk { abiFilters += "x86_64" }
            }
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        // Que un warning nuevo no pase desapercibido: el CI lo corre en cada push.
        abortOnError = true
        warningsAsErrors = false
    }

    // R8 sólo se puede validar EJECUTANDO el artefacto minificado: si una regla keep falta,
    // compila igual y explota en runtime. Con -PmarvinTestRelease la suite instrumentada
    // corre contra la variante release (ver E2E_RELEASE=1 en scripts/e2e.sh).
    testBuildType = if (project.hasProperty("marvinTestRelease")) "release" else "debug"

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // El motor de terminal vendorizado loguea con android.util.Log, que en un test JVM
        // no existe y explota. Con esto se lo puede ejercitar sin dispositivo, que es donde
        // conviene: es código puro que procesa bytes y decide qué queda en pantalla.
        unitTests.isReturnDefaultValues = true
    }
}

// Portero de las reglas de R8, sobre el mapping del APK que se publica.
//
// mapping.txt es el testigo de lo que R8 hizo REALMENTE, no de lo que las reglas pretendían.
// Si alguien edita proguard-rules.pro y el binding de gomobile deja de conservarse, la app
// compila igual y explota al iniciar Tailscale — en el teléfono, sin ningún test que lo
// agarre. Esto corre sin emulador, en cada build de release, y falla en el acto.
//
// Va como tarea del build y no como test suelto para que no se pueda saltear: si se produjo
// un APK, se verificó.
val verifyReleaseKeepRules = tasks.register("verifyReleaseKeepRules") {
    description = "Verifica sobre mapping.txt que R8 conservó lo que se busca por nombre en runtime"
    dependsOn("minifyReleaseWithR8")
    // Con -PmarvinTestRelease el APK lleva a propósito las keeps de validación, que este
    // portero justamente rechaza. Ese artefacto no se publica.
    onlyIf { !project.hasProperty("marvinTestRelease") }

    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    inputs.files(mappingFile)

    doLast {
        // Las cuatro invariantes, sobre el CONTENIDO del mapping. Se verifica el resultado y
        // no qué regla lo produjo: hoy el binding lo protegen tanto proguard-rules.pro como
        // el proguard.txt que el propio AAR de gomobile trae adentro, y mañana puede ser otra.
        fun revisar(map: List<String>): List<String> {
            val fallas = mutableListOf<String>()

            // 1. Lo que libgojni.so busca por nombre vía JNI tiene que seguir existiendo y
            //    sin renombrar. Es la falla que nada detecta hasta que la app arranca.
            for (c in listOf("marvints.Marvints", "go.Seq", "go.Universe")) {
                if (map.none { it == "$c -> $c:" }) {
                    fallas += "$c no sobrevivió intacto: el puente con Tailscale va a fallar al arrancar"
                }
            }

            // 2. trilead elige algoritmos por nombre de clase y no trae reglas propias.
            val trilead = map.count { it.startsWith("com.trilead.ssh2.") && it.endsWith(":") &&
                it.substringAfter(" -> ").startsWith("com.trilead.ssh2.") }
            if (trilead < 100) fallas += "sólo $trilead clases de trilead conservadas (se esperaban >100): SSH en riesgo"

            // 3. Chequeo inverso, para que lo de arriba no pase por trivialidad: si alguien
            //    "arregla" un problema apagando la minificación o keepeando todo, las
            //    invariantes de JNI pasarían igual y esto cantaría victoria sin proteger nada.
            val ofuscadas = map.count { l ->
                l.startsWith("com.remoteclaude.app.") && l.endsWith(":") &&
                    !l.substringAfter(" -> ").startsWith("com.remoteclaude.app")
            }
            if (ofuscadas < 10) fallas += "sólo $ofuscadas clases propias ofuscadas: R8 no está haciendo su trabajo"

            // 4. Que no se hayan colado las keeps de validación (proguard-rules-e2e.pro), que
            //    duplican el dex y sólo tienen sentido al instrumentar.
            if (map.any { it == "kotlin.LazyKt -> kotlin.LazyKt:" }) {
                fallas += "el stdlib de Kotlin quedó sin recortar: se filtraron las reglas de e2e al APK publicable"
            }
            return fallas
        }

        // Autoprueba, de los dos lados. Un portero que nunca disparó no está probado, y acá
        // el riesgo es concreto: el binding hoy lo protegen a la vez proguard-rules.pro y el
        // proguard.txt que trae el AAR, así que no hay forma de romperlo a mano para
        // comprobar que esto discrimina. Se verifica que NO reporte nada sobre un mapping
        // sano y que SÍ señale a marvints cuando está renombrado — específicamente a
        // marvints: pedir "algún hallazgo" alcanzaría con que se queje de otra cosa, y el
        // "✓" de abajo volvería a no significar nada.
        val sano = buildList {
            add("go.Seq -> go.Seq:"); add("go.Universe -> go.Universe:")
            add("marvints.Marvints -> marvints.Marvints:")
            repeat(120) { add("com.trilead.ssh2.C$it -> com.trilead.ssh2.C$it:") }
            repeat(20) { add("com.remoteclaude.app.C$it -> z.a$it:") }
        }
        check(revisar(sano).isEmpty()) { "el portero reporta fallas sobre un mapping sano: ${revisar(sano)}" }
        val roto = sano.map { if (it.startsWith("marvints.Marvints")) "marvints.Marvints -> a.b:" else it }
        check(revisar(roto).any { "marvints" in it }) {
            "el portero NO detecta el binding de JNI renombrado; el resto de sus chequeos no cubre eso"
        }

        val f = mappingFile.get().asFile
        if (!f.exists()) throw GradleException("no se generó $f: ¿se apagó isMinifyEnabled?")
        val map = f.readLines()
        val fallas = revisar(map)
        val trilead = map.count { it.startsWith("com.trilead.ssh2.") && it.endsWith(":") &&
            it.substringAfter(" -> ").startsWith("com.trilead.ssh2.") }
        val ofuscadas = map.count { l ->
            l.startsWith("com.remoteclaude.app.") && l.endsWith(":") &&
                !l.substringAfter(" -> ").startsWith("com.remoteclaude.app")
        }

        if (fallas.isNotEmpty()) {
            throw GradleException(
                "Las reglas de R8 no protegen lo que deberían:\n" +
                    fallas.joinToString("\n") { "  - $it" } +
                    "\n\nRevisá app/proguard-rules.pro. Validación completa: make e2e-release",
            )
        }
        logger.lifecycle(
            "✓ reglas de R8 verificadas: binding JNI intacto, $trilead clases de trilead, " +
                "$ofuscadas clases propias ofuscadas",
        )
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(verifyReleaseKeepRules) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(project(":terminal-view"))   // motor Termux vendorizado
    implementation("org.connectbot:sshlib:2.2.23")   // cliente SSH para Android (Apache-2.0)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebSocket del dictado en vivo
    implementation(":marvints@aar")   // Tailscale embebido (tsnet vía gomobile)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // scanner QR (vincular Tailscale)

    // Tests unitarios JVM (sin device). org.json real: el del android.jar es un stub que
    // lanza "Method not mocked", y varios parseos nuestros lo usan.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.google.truth:truth:1.4.2")
}
