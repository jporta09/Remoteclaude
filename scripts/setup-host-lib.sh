#!/usr/bin/env bash
# Las piezas de setup-host.sh que tienen lógica de verdad, separadas para poder probarlas
# sin tocar el host (test/host/test_setup_host.py). El resto del script son apt-get y
# systemctl, que no se pueden ejercitar en un test.

# Escribe (o reescribe) un bloque delimitado por sentinelas dentro de un archivo del usuario,
# conservando el resto. El contenido viene por stdin.
#
#   bloque_sentinelas ~/.tmux.conf "# >>> X >>>" "# <<< X <<<" <<< "set -g mouse on"
#
# Es idempotente a propósito: correr el setup dos veces tiene que dejar UN bloque, con la
# versión nueva. La alternativa que había antes —append con un grep como guarda— dejaba la
# versión vieja para siempre y nunca se enteraba nadie.
bloque_sentinelas() {
    local archivo="$1" begin="$2" end="$3"
    local contenido
    contenido="$(cat)"

    touch "$archivo"
    if grep -qF "$begin" "$archivo"; then
        # Borrar el bloque anterior. Se usa un awk con banderas y no `sed /a/,/b/d` porque
        # las sentinelas llevan caracteres que sed interpreta.
        local tmp
        tmp="$(mktemp)"
        awk -v b="$begin" -v e="$end" '
            index($0, b) { dentro = 1 }
            !dentro { print }
            index($0, e) { dentro = 0 }
        ' "$archivo" > "$tmp"
        cat "$tmp" > "$archivo"
        rm -f "$tmp"
    fi
    {
        printf '%s\n' "$begin"
        printf '%s\n' "$contenido"
        printf '%s\n' "$end"
    } >> "$archivo"
}

# Escribe una unit de usuario (contenido por stdin) y la deja EFECTIVA.
#
# El daemon-reload solo no alcanza: si el servicio estaba corriendo, seguía con el código
# viejo y el síntoma era "actualicé el repo y no cambió nada". Sólo reinicia lo que YA estaba
# activo — no prende nada por su cuenta, porque estos daemons son a demanda a propósito.
escribir_unidad() {
    local nombre="$1"
    local destino="${SYSTEMD_USER_DIR:-$HOME/.config/systemd/user}"
    install -d "$destino"
    cat > "$destino/$nombre"
    systemctl --user daemon-reload
    if systemctl --user is-active --quiet "$nombre"; then
        systemctl --user restart "$nombre"
        echo "    reiniciado (estaba corriendo con la versión anterior)"
    fi
}
