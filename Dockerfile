# Gateway de conexión remota AGNÓSTICO. El contenedor SOLO provee la conexión
# (sshd + mosh). La shell se ejecuta en el HOST vía nsenter: ve el filesystem,
# PATH, proyectos, claude y configs del host de forma nativa. No instala nada en
# el host ni depende de su stack de desarrollo; solo asume "Linux con Docker".
#
# La distro base acá da igual: NO ejecutamos binarios del host adentro, saltamos
# al host. Por eso una base mínima alcanza.
FROM debian:bookworm-slim

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openssh-server \
        mosh \
        tmux \
        util-linux \
        locales \
        procps \
        ca-certificates \
        sudo \
    && printf 'en_US.UTF-8 UTF-8\nes_ES.UTF-8 UTF-8\n' >> /etc/locale.gen && locale-gen \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/sshd /etc/ssh/keys /etc/remoteclaude

# mosh-server (que corre en el contenedor) exige un locale UTF-8.
ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8

COPY scripts/entrypoint.sh /usr/local/bin/entrypoint.sh
COPY scripts/host-shell.sh /usr/local/bin/host-shell
COPY scripts/rc-enroll-key.sh /usr/local/bin/rc-enroll-key
COPY scripts/rc-enroll-forced.sh /usr/local/bin/rc-enroll-forced
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/host-shell \
        /usr/local/bin/rc-enroll-key /usr/local/bin/rc-enroll-forced

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["sleep", "infinity"]
