# Entorno de desarrollo remoto autocontenido: navegador visible (noVNC) +
# terminal estable (sshd + mosh + tmux) + Playwright, todo dentro del contenedor.
# Lo único que vive en el host es Docker. La red la aporta el contenedor de Tailscale.
FROM mcr.microsoft.com/playwright:v1.49.0-jammy

# - Entorno gráfico:  Xvfb (display virtual) + x11vnc + noVNC/websockify + fluxbox
# - Acceso remoto:    openssh-server + mosh (la terminal que sobrevive al bloqueo) + tmux
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        x11vnc \
        fluxbox \
        novnc \
        websockify \
        openssh-server \
        mosh \
        tmux \
        procps \
        net-tools \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sf /usr/share/novnc/vnc.html /usr/share/novnc/index.html

# sshd: solo clave pública, sin password.
RUN mkdir -p /run/sshd /root/.ssh \
    && printf 'PermitRootLogin prohibit-password\nPasswordAuthentication no\nPubkeyAuthentication yes\n' \
        > /etc/ssh/sshd_config.d/remoteclaude.conf

ENV DISPLAY=:99
ENV SCREEN_GEOMETRY=1360x768x24
ENV NOVNC_PORT=6080

WORKDIR /work

COPY scripts/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["sleep", "infinity"]
