pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // motor de Termux (M1+)
        flatDir { dirs("app/libs") }                // marvints.aar (Tailscale embebido)
    }
}

rootProject.name = "Remoteclaude"
include(":app")
include(":terminal-emulator")   // motor Termux vendorizado (GPLv3)
include(":terminal-view")       // view Termux vendorizado (GPLv3)
