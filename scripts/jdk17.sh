#!/usr/bin/env bash
# Imprime un JAVA_HOME servible para AGP, o falla con un mensaje que se entienda.
#
# AGP transforma core-for-system-modules.jar con `jlink`, y el java por defecto de esta
# máquina es un 21 que no lo trae. Sin fijarlo, los targets de Gradle andan o no según el
# shell desde el que los llames, y el error que sale ("Failed to transform
# core-for-system-modules.jar") no menciona el JDK por ningún lado.
#
# Si ya hay un JAVA_HOME válido se respeta: la idea es destrabar, no imponer.
set -euo pipefail

if [ -x "${JAVA_HOME:-/nonexistent}/bin/jlink" ]; then
    echo "$JAVA_HOME"; exit 0
fi

for j in /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/openjdk-17 /usr/lib/jvm/*17*; do
    [ -x "$j/bin/jlink" ] && { echo "$j"; exit 0; }
done

echo "!! falta un JDK 17 con jlink (probá: apt install openjdk-17-jdk)" >&2
exit 1
