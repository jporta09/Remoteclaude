# Reglas EXTRA que se aplican SÓLO al build de validación de R8 (-PmarvinTestRelease),
# nunca al APK que se publica.
#
# Instrumentar una app minificada tiene un problema propio: el APK de tests no reempaqueta
# las dependencias de la app, así que si R8 elimina una clase que el runner necesita, el
# proceso muere al arrancar. Es el caso de androidx.tracing.Trace, que llega por core-ktx y
# la app no usa: borrarla es CORRECTO en producción (ahí no hay runner) y sólo molesta acá.
#
# Mantener esto separado es a propósito: el artefacto probado difiere del publicado
# únicamente en estas keeps, y queda a la vista cuáles son.
-keep class androidx.tracing.** { *; }
-dontwarn androidx.tracing.**

# El stdlib de Kotlin entero. El APK de tests NO trae su propia copia: usa la de la app, y
# R8 la recorta a exactamente lo que la app usa. Los tests usan más (lambdas con el
# constructor de aridad de kotlin.jvm.internal.Lambda, `lazy {}` -> kotlin.LazyKt, etc.), así
# que ir agregando clase por clase es jugar al topo sin fin.
#
# Lo que se resigna acá es validar el recorte del STDLIB, que no es donde R8 rompe productos.
# Lo que importa sigue validándose: el binding de gomobile, trilead, el motor de terminal y
# nuestro propio código, que se siguen ofuscando y optimizando igual que en el APK publicado.
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Sin el pase de optimización. R8 inlinea métodos que sólo tienen call sites dentro de la app
# (KeyStoreSsh entero desaparece: MainActivity lo llama dos veces y queda embebido) y les
# recorta parámetros; los tests, que llaman esos mismos métodos desde afuera, no los
# encuentran. No hay forma de enumerarlos: cambia con cada refactor.
#
# Elegido a propósito por sobre "-keep class com.remoteclaude.app.** { *; }": así nuestro
# código SE SIGUE ofuscando y recortando, que es donde R8 rompe de verdad (nombres que algo
# busca en runtime). Lo único que el APK publicado hace de más es inlinear.
-dontoptimize

# Los ganchos que existen SÓLO para los tests (screenText, currentSessionForTest, hasPinned):
# la app no los llama, así que el shrinker los elimina con toda razón en el APK publicado.
# Se conservan por la anotación y no por una lista a mano, para que no haya que tocar esto
# cada vez que aparece un gancho nuevo.
-keepclassmembers class com.remoteclaude.app.** {
    @androidx.annotation.VisibleForTesting <methods>;
}
