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
# Borra el bloque delimitado por sentinelas de un archivo, conservando el resto (lo que hay
# arriba Y abajo del bloque). Si el archivo no existe o no tiene el bloque, no hace nada. Lo
# usan tanto bloque_sentinelas (para reescribir) como teardown-host.sh (para desinstalar).
quitar_bloque_sentinelas() {
    local archivo="$1" begin="$2" end="$3"
    [ -f "$archivo" ] || return 0
    grep -qF "$begin" "$archivo" || return 0
    # Si está el inicio pero NO el fin (p.ej. alguien editó el archivo a mano y borró la sentinela
    # de cierre), el awk de abajo dejaría `dentro=1` hasta EOF y borraría desde `begin` hasta el
    # final del archivo. No tocamos nada en ese caso: mejor dejar una sentinela huérfana que
    # comerse el resto de un ~/.ssh/config o ~/.tmux.conf.
    grep -qF "$end" "$archivo" || {
        printf 'aviso: %s tiene la sentinela de inicio pero no la de fin; no lo modifico para no borrar de más\n' "$archivo" >&2
        return 0
    }
    # awk con banderas y no `sed /a/,/b/d` porque las sentinelas llevan caracteres que sed
    # interpreta.
    local tmp
    tmp="$(mktemp)"
    awk -v b="$begin" -v e="$end" '
        index($0, b) { dentro = 1 }
        !dentro { print }
        index($0, e) { dentro = 0 }
    ' "$archivo" > "$tmp"
    cat "$tmp" > "$archivo"
    rm -f "$tmp"
}

bloque_sentinelas() {
    local archivo="$1" begin="$2" end="$3"
    local contenido
    contenido="$(cat)"

    touch "$archivo"
    quitar_bloque_sentinelas "$archivo" "$begin" "$end"
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

# Deshace escribir_unidad: para el servicio si corre, lo deshabilita (por si `mode always` lo
# dejó enabled), borra el archivo y recarga. Idempotente y a prueba de "no existe" — el teardown
# nunca debe fallar por una unit que ya no está. El daemon-reload va aunque no hubiera archivo:
# barato y deja el estado consistente.
quitar_unidad() {
    local nombre="$1"
    local destino="${SYSTEMD_USER_DIR:-$HOME/.config/systemd/user}"
    systemctl --user disable --now "$nombre" >/dev/null 2>&1 || true
    rm -f "$destino/$nombre"
    systemctl --user daemon-reload >/dev/null 2>&1 || true
}

# ¿Qué métodos de autenticación ofrece el sshd de este host?
#
# Con PreferredAuthentications=none el servidor responde con la lista de métodos que acepta
# sin que intentemos autenticarnos con ninguno. Es la forma de verificar esto SIN root:
# `sshd -T` exige privilegios.
sshd_metodos() {
    local salida
    # El `|| true` es imprescindible: un sshd SANO rechaza la auth y ssh sale con 255. Sin esto,
    # bajo `set -o pipefail` (como corre setup-host.sh) el rc de la función era 255 aunque la
    # respuesta fuera perfecta, y quien mirara el rc en vez de la SALIDA concluía "no responde".
    # El resultado de esta función es lo que imprime, nunca su código de salida.
    salida="$(LC_ALL=C ssh -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
        -o PreferredAuthentications=none -p "${1:-22}" nadie@127.0.0.1 true 2>&1 || true)"
    # El `|| true` final cubre el otro extremo: cuando NO hay respuesta, grep no matchea y sale 1,
    # que pipefail también propagaría. La función SIEMPRE termina bien; hablar es su salida.
    printf '%s\n' "$salida" |
        grep -oE "Permission denied \(([^)]*)\)" | head -1 | sed 's/.*(\(.*\)).*/\1/' || true
}

# 0 = acepta contraseñas (RIESGO) · 1 = sólo clave · 2 = no se pudo verificar.
#
# Importa porque TODO el modelo de RemoteMarvin asume solo-clave: el pinning de la clave del
# host, la autorización por authorized_keys y el túnel del visor. Con contraseñas habilitadas
# el servidor queda expuesto a fuerza bruta y esas defensas dejan de significar lo que dicen.
sshd_acepta_password() {
    local metodos
    metodos="$(sshd_metodos "${1:-22}")"
    [ -n "$metodos" ] || return 2
    case ",$metodos," in
        *,password,*|*,keyboard-interactive,*) return 0 ;;
    esac
    return 1
}

# Nombre de la unit de sshd en esta distro: `ssh` (Debian/Ubuntu) o `sshd` (Fedora/Arch/openSUSE,
# y alias en Debian). setup-host hardcodeaba `ssh` mientras teardown ya contemplaba las dos: en las
# familias dnf/pacman/zypper el `enable --now ssh` fallaba y sshd no quedaba levantado (DEVOPS-5p-1).
# Sin systemd o sin unit listada cae al nombre histórico.
unit_sshd() {
    local u load id
    # `systemctl show -p Id` resuelve el ALIAS al nombre canónico. Hace falta: en Debian/Ubuntu
    # existen las DOS (ssh.service real y sshd.service como alias), y `enable` sobre un alias
    # falla con "Refusing to operate on alias name or linked unit file" — lo que rompía el setup
    # en Ubuntu 24.04 (visto en vivo 2026-09-04). LoadState descarta las que no existen.
    for u in ssh sshd; do
        load="$(systemctl show -p LoadState --value "$u.service" 2>/dev/null || true)"
        [ "$load" = "loaded" ] || continue
        id="$(systemctl show -p Id --value "$u.service" 2>/dev/null || true)"
        case "$id" in
            *.service) echo "${id%.service}"; return ;;
        esac
    done
    echo ssh
}

# Marker de "host configurado": la app lo verifica al CONECTAR y bloquea hosts sin setup.
# Va en función (y no inline en setup-host.sh) para poder testearlo: escribirlo al final
# del setup es el contrato — si el setup falló antes (set -e), el marker no queda.
escribir_marker_setup() {
    local repo="${1:-.}"
    install -d "$HOME/.config/marvin"
    printf 'setup-host %s %s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        "$(git -C "$repo" rev-parse --short HEAD 2>/dev/null || echo sin-git)" \
        > "$HOME/.config/marvin/setup-ok"
}
