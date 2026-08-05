# sshd + tmux desechable para los tests E2E.
# Aislado a propósito: los tests crean y matan sesiones tmux, y en el host real ahí
# viven las sesiones de trabajo del usuario.
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        openssh-server tmux ca-certificates procps \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -s /bin/bash tester && echo 'tester:e2e' | chpasswd
RUN mkdir -p /run/sshd /home/tester/.ssh /home/tester/RemoteMarvinDocs \
    && chown -R tester:tester /home/tester

# Password SOLO para el usuario de test: es el canal de control del harness (autorizar
# la clave del Keystore de la app). La app entra por clave, como en producción.
RUN printf 'PasswordAuthentication no\nPubkeyAuthentication yes\n\nMatch User tester\n    PasswordAuthentication yes\n' \
        > /etc/ssh/sshd_config.d/e2e.conf

# tmux.conf: el mismo que se distribuye, para probar lo que el usuario realmente corre
COPY marvin.tmux.conf /home/tester/.tmux.conf

# Documentos sembrados (incluye un nombre con comilla, que debe rechazarse al abrir)
RUN printf 'contenido de prueba\n' > "/home/tester/RemoteMarvinDocs/notas.txt" \
    && printf 'a,b\n1,2\n' > "/home/tester/RemoteMarvinDocs/datos.csv" \
    && printf 'x' > "/home/tester/RemoteMarvinDocs/raro'nombre.txt" \
    && chown -R tester:tester /home/tester/RemoteMarvinDocs

# Stub de marvin-stt: valida que llegue un WAV y responde texto fijo, sin modelo ni GPU
RUN mkdir -p /home/tester/.local/bin \
    && printf '#!/bin/bash\nhead -c4 | grep -q RIFF || { echo "error: no es un WAV" >&2; exit 3; }\ncat >/dev/null\necho "hola marvin"\n' \
        > /home/tester/.local/bin/marvin-stt \
    && chmod +x /home/tester/.local/bin/marvin-stt \
    && chown -R tester:tester /home/tester/.local

# Corta la conexión de forma determinista (test de reconexión)
RUN printf '#!/bin/bash\n(sleep 1; pkill -u tester sshd) &\n' > /usr/local/bin/e2e-drop-ssh \
    && chmod +x /usr/local/bin/e2e-drop-ssh

# Las host keys las genera el postinst de Debian AL CONSTRUIR, o sea que quedarían
# idénticas en las dos instancias. Se borran acá y se generan al arrancar cada
# contenedor, para que fixture y fixture-rotated tengan claves distintas (es lo que
# hace posible el test de "la clave del host cambió").
RUN rm -f /etc/ssh/ssh_host_*
RUN printf '#!/bin/sh\nset -e\n[ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A >/dev/null\nexec "$@"\n' \
        > /usr/local/bin/entrypoint.sh && chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 22
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["/usr/sbin/sshd", "-D", "-e"]
