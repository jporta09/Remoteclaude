# Reglas de R8 para RemoteMarvin.
#
# El riesgo real acá es el puente con Go: `libgojni.so` busca las clases y métodos del
# binding POR NOMBRE vía JNI, así que si R8 los renombra la app compila igual y explota
# recién en runtime al iniciar Tailscale. Por eso se conservan enteros.

# --- Puente gomobile (tsnet) ---------------------------------------------------------
-keep class go.** { *; }
-keep class marvints.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Motor de terminal vendorizado (Termux) ------------------------------------------
# Los clientes se implementan como objetos anónimos y el motor los llama por interfaz;
# conservar las interfaces evita sorpresas si R8 decide reescribir jerarquías.
-keep interface com.termux.terminal.TerminalSessionClient { *; }
-keep interface com.termux.view.TerminalViewClient { *; }

# --- SSH (trilead/connectbot) --------------------------------------------------------
# No trae reglas propias y elige algoritmos por nombre de clase en varios puntos.
-keep class com.trilead.ssh2.** { *; }
-dontwarn com.trilead.ssh2.**

# --- Ruido de dependencias -----------------------------------------------------------
# okhttp y zxing ya traen sus consumer-rules; esto sólo calla avisos de clases opcionales
# que no usamos en Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Mantener los nombres de archivo/línea en los stack traces: sin esto, un crash del
# usuario llega ilegible y no tenemos mapping subido a ningún lado.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
