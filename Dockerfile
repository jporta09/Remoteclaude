# Entorno de desarrollo remoto con navegador real visible vía noVNC.
# Base: imagen oficial de Playwright (ya trae Chromium/Firefox/WebKit + dependencias).
FROM mcr.microsoft.com/playwright:v1.49.0-jammy

# Display virtual (Xvfb) + servidor VNC (x11vnc) + visor web (noVNC/websockify)
# + window manager liviano (fluxbox) para que el navegador headed tenga dónde dibujarse.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        x11vnc \
        fluxbox \
        novnc \
        websockify \
        tmux \
        procps \
        net-tools \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sf /usr/share/novnc/vnc.html /usr/share/novnc/index.html

ENV DISPLAY=:99
ENV SCREEN_GEOMETRY=1360x768x24
ENV NOVNC_PORT=6080

WORKDIR /work

COPY scripts/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 6080

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
# Mantiene el contenedor vivo; vos entrás con `docker exec -it ... bash`.
CMD ["sleep", "infinity"]
