# Remoteclaude App (Android) — Diseño completo

Cliente Android nativo del setup Remoteclaude: **terminales nativas** (que ejecutan
en el host vía el gateway) + **visualizador** del navegador headed (noVNC), pensado
para programar y mirar scraping/tests desde el celular, sobreviviendo al bloqueo.

## 1. Objetivo y alcance

- Terminal nativa de calidad (no webview): tabs estilo navegador, scroll fluido,
  teclas extra, zoom de fuente.
- **Varias terminales** a la vez (cada tab = una sesión).
- **Sobrevive al bloqueo**: SSH + `tmux` (persistencia server-side) + auto-reconexión.
- **Visualizador integrado**: ver el navegador headed del host (noVNC) sin salir de
  la app.
- Conexión por la tailnet al **gateway** (que hace `nsenter` al host). La app no
  sabe de Docker; solo habla SSH y abre una URL de noVNC.
- Open-source (GPLv3 OK por usar el motor de Termux).

Fuera de alcance del MVP: bundlear mosh nativo (ver README/charla: difícil en
Android), control remoto multi-host avanzado, sync de settings en la nube.

## 2. Arquitectura

```
┌── Remoteclaude App (Kotlin / Android) ─────────────────────────┐
│                                                                 │
│  UI (Compose o Views)                                           │
│   ├─ TerminalsScreen  : tab bar + TerminalView + extra-keys     │
│   ├─ VisualizerScreen : WebView -> noVNC                         │
│   └─ ConnectionScreen : host/usuario/puerto + llave SSH         │
│                                                                 │
│  Dominio                                                        │
│   ├─ SessionManager   : crea/cierra/lista sesiones (tabs)       │
│   ├─ ReconnectEngine  : detecta caída, reconecta, reattach tmux │
│   └─ ConfigStore      : perfil de conexión + llave (Keystore)   │
│                                                                 │
│  Capas embebidas                                                │
│   ├─ Motor terminal : Termux terminal-view + terminal-emulator  │  (GPLv3)
│   │                   (emulación VT, render, input, scrollback)  │
│   ├─ Transporte SSH : sshj (JVM puro, sin binario nativo)        │  (Apache-2.0)
│   └─ Visualizador   : WebView del sistema -> noVNC del display   │
└─────────────────────────────────────────────────────────────────┘
            │ SSH (22, por Tailscale)        │ HTTP (6080, por Tailscale)
            ▼                                ▼
   gateway(contenedor) --nsenter--> HOST     display(contenedor): noVNC
            │                                        ▲
            └─ shell del host -> `tmux` <────────────┘ navegador headed dibuja en :99
```

**Idea clave del motor**: el `TerminalView` de Termux renderiza **una** terminal y
maneja toda la parte difícil (secuencias VT, scrollback, selección, IME, teclado).
Nosotros le damos de comer los bytes que llegan del canal SSH y le mandamos las
teclas. Todo lo demás (tabs, gestos, reconexión) es **nuestra** capa.

## 3. Stack técnico

| Pieza | Elección | Licencia | Por qué |
|---|---|---|---|
| Lenguaje/UI | Kotlin + Jetpack (Compose para chrome de la app; el TerminalView es una View clásica embebida con `AndroidView`) | Apache-2.0 | nativo, moderno |
| Motor terminal | `com.termux:terminal-view` + `terminal-emulator` (vía JitPack/source) | GPLv3 | mejor emulador Android |
| Transporte | **sshj** (fallback: connectbot `sshlib`) | Apache-2.0 | SSH en JVM puro, sin NDK |
| Visualizador | `WebView` del sistema → noVNC | — | reusa el display container ya andando |
| Config/secretos | DataStore + **Android Keystore** (clave privada envuelta) | — | guardar perfil y llave seguro |
| Red | Tailscale app (del usuario) | — | la app asume estar en la tailnet |

- **minSdk 26** (Android 8), **target 34**. Sin código nativo propio (sshj y el motor
  de Termux son JVM; el visualizador es WebView).

## 4. Funcionalidades

### 4.1 Terminal
- Cada tab abre un canal SSH al gateway (`usuario@host:puerto`) → cae en la shell del
  host (gateway hace `nsenter`) → ejecuta `tmux new -A -s <tab>` para persistir.
- Render con `TerminalView`; `TerminalSession` alimentado por el stream del canal SSH
  (in/out/err ↔ pty del lado server).
- **Teclas extra** (fila): Esc, Ctrl, Alt, Tab, flechas, `|`, `/`, `-`, `~`, Ctrl-C…
  (clave para vim/nano/claude).
- **Scroll**: scrollback nativo del TerminalView; botón "ir al fondo"; selección/copia.
- **Zoom de fuente**: pinch para agrandar/achicar.

### 4.2 Multi-terminal (tabs, estilo navegador)
- Tab bar arriba: tabs + botón `+` (nueva) + botón visualizador `▣`.
- Cada tab = una `TerminalSession` independiente (un `tmux new -A -s tabN`).
- Cerrar tab: cierra el canal SSH (la sesión tmux queda viva en el host para reatachar).
- (v2) modo control de tmux (`tmux -CC`) para multiplexar varias en una sola conexión.

### 4.3 Auto-reconexión (lo que reemplaza a mosh)
- `ReconnectEngine` escucha cambios de red (ConnectivityManager) y errores del canal.
- Al bloquear/cortar: marca la sesión como "reconectando" (banner sutil).
- Al volver: reabre SSH y `tmux attach -t tabN` → todo intacto en <~1s.
- Backoff exponencial; keepalive SSH para detectar caídas rápido.

### 4.4 Visualizador (noVNC)
- Pantalla/tab dedicada: `WebView` cargando
  `http://<host>:6080/vnc.html?autoconnect=1&resize=scale`.
- Controles: recargar, ajustar (scale/remote), pantalla completa.
- Pasaje de toques: noVNC ya maneja mouse/teclado; el WebView reenvía gestos.
- (v2) cliente VNC nativo (mejor rendimiento/gestos) conectando a x11vnc directo.

### 4.5 Conexión y llaves
- **Perfil**: host (nombre MagicDNS o IP), usuario del gateway (`root`), puerto (22),
  URL del visualizador (auto: `http://<host>:6080`).
- **Llave SSH**: la app **genera un par ed25519** en el dispositivo; muestra la
  **pública** para que el usuario la pegue en `ssh/authorized_keys` del gateway
  (botón copiar). Privada guardada con Android Keystore.
- (Opcional) importar una clave existente.

## 5. Pantallas (wireframes)

**Main (terminales):**
```
┌───────────────────────────┐
│ ●dev  ○logs  ○scrap  +  ▣ │   ▣ = abrir visualizador
├───────────────────────────┤
│ jporta@haviland:~$ ...     │
│   (TerminalView de Termux) │
│                            │
├───────────────────────────┤
│ Esc Ctrl Alt Tab ↑↓←→ |/- │   fila de teclas extra
└───────────────────────────┘
```

**Visualizador:**
```
┌───────────────────────────┐
│ ‹ Volver   Navegador    ⟳ │
├───────────────────────────┤
│  [ WebView -> noVNC ]      │
│  navegador headed del host │
│                            │
└───────────────────────────┘
```

**Conexión (primer arranque):**
```
┌───────────────────────────┐
│ Conexión                   │
│ Host:    remoteclaude      │
│ Usuario: root              │
│ Puerto:  22                │
│ ── Clave SSH ──            │
│ [Generar par ed25519]      │
│ Pública: ssh-ed25519 AAA…  │
│ [Copiar]  → pegar en       │
│   ssh/authorized_keys      │
│ Visualizador: …:6080       │
│ [Conectar]                 │
└───────────────────────────┘
```

## 6. Cómo se conecta al gateway

1. App abre SSH a `root@<host>:22` con la clave del dispositivo.
2. El gateway (login shell = `host-shell`) hace `nsenter` → `su - <HOST_USER>` → shell
   real del host.
3. La app envía `tmux new -A -s <tab>` → sesión persistente.
4. Bytes del canal ↔ `TerminalView`. Reconexión = reabrir SSH + `tmux attach`.

Nada nuevo del lado server: usa el gateway y el display que ya existen.

## 7. Seguridad

- Clave privada en **Android Keystore** (o `EncryptedSharedPreferences`).
- Todo viaja por **Tailscale** (WireGuard); noVNC va sin TLS pero cifrado por la tailnet.
- Host key del gateway: la app fija/verifica la huella (TOFU) y avisa si cambia
  (ya la persistimos server-side, así que no debería cambiar).

## 8. Roadmap por milestones (build incremental)

- **M0** Esqueleto que compila a APK (Activity vacía) — valida el pipeline de build.
- **M1** `TerminalView` embebido renderizando una sesión local de eco (sin red).
- **M2** Transporte SSH (sshj): un tab que conecta al gateway y abre `tmux`.
- **M3** Multi-tab (varias sesiones) + botón `+` + cerrar.
- **M4** Fila de teclas extra + scroll + zoom de fuente.
- **M5** Auto-reconexión (ConnectivityManager + reattach tmux).
- **M6** Visualizador (WebView → noVNC) como pantalla/tab.
- **M7** Pantalla de conexión + generación/guardado de llave + perfil.
- **M8** Pulido (íconos, temas, persistencia de tabs) + APK firmado.

## 9. Build

- Reusar el SDK que ya está en el host (de buildozer):
  `ANDROID_HOME=~/.buildozer/android/platform/android-sdk` (tiene `android-34`,
  build-tools, `sdkmanager`). Completar componentes faltantes con `sdkmanager` y
  aceptar licencias.
- Gradle por **wrapper** (`./gradlew assembleDebug`) → APK debug.
- Instalar al celular: por Tailscale/adb o copiando el APK. Firma release en M8.

## 10. Decisiones abiertas

- Motor Termux: ¿vía JitPack (más simple) o vendorizando el source (más control)?
- SSH: arrancamos con **sshj**; si da fricción en Android, caemos a connectbot `sshlib`.
- Visualizador: WebView (MVP) vs VNC nativo (v2).
- UI: Compose para el chrome + `AndroidView` para el `TerminalView` (híbrido).
