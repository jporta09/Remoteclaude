# Validar la detección de "acceso de Tailscale vencido" (fila 550 / F10)

Esta es la validación en vivo que quedó pendiente de F10: confirmar que, cuando la **node key
del nodo embebido (el del teléfono)** vence, la app lo **detecta y avisa** en vez de reconectar
en silencio. No se puede reproducir sin un tailnet real; por eso se fuerza el vencimiento a mano
con **"Expire key now"** en la consola de admin.

> Qué se está probando: el caso **mid-sesión** (la app está corriendo y conectada, y de golpe
> el acceso vence). Es el caso común de "venía andando y se cayó todo". El caso
> *reinicio-tras-vencer* (cerrar y reabrir la app ya vencida) **todavía no está cubierto** — ver
> "Límites conocidos" al final.

## Antes de empezar

- El teléfono tiene que estar en **modo Tailscale embebido** (te enrolaste por QR), no en modo
  directo `host:port`. Si no, no hay nodo embebido que consultar y la detección no aplica.
- La app tiene que estar **conectada y andando** (abrí una sesión en la terminal y confirmá que
  responde).
- Tené a mano la PC para re-enrolar al final (`./scripts/ts-link-qr.sh`).

## Pasos

1. **Identificá el nodo del teléfono** en la consola:
   <https://login.tailscale.com/admin/machines>
   Es el nodo cuyo nombre es el *hostname* de la app — por defecto `remotemarvin-<hex>` (pref
   `ts_hostname`). **No** es `remoteclaude` (ese es el nodo del **host**, el contenedor docker) ni
   ninguna de tus otras máquinas.

2. En ese nodo → menú **"⋯"** → **"Expire key now"** → confirmá.
   Eso simula el vencimiento a los ~180 días: el control plane marca la key como expirada y el
   backend del nodo embebido cae a `NeedsLogin`.

3. **Volvé a la app** (dejala en primer plano, en la terminal). En uno o dos ciclos de
   reconexión (segundos) tenés que ver, **una sola vez**, el mensaje:

   ```
   [el acceso de Tailscale venció — reescaneá el QR desde la línea de estado de Tailscale
    para volver a habilitar la conexión]
   ```

4. **Confirmá el evento en el diagnóstico**: mantené apretada la **barra de host** (arriba) para
   abrir la pantalla de diagnóstico. Tiene que aparecer, arriba de todo, un evento **ERROR**:

   ```
   tailscale: el acceso de Tailscale venció (node key expirada) — hay que reescanear el QR
   ```

5. **Recuperación (re-enrolar)**: en la PC corré `./scripts/ts-link-qr.sh` (imprime un QR nuevo).
   En la app: **línea de estado de Tailscale → Escanear QR** → apuntá la cámara. El estado vuelve
   a **conectada ✓** y la terminal reconecta sola.

## Criterio de aprobado

- [ ] Aparece el mensaje del paso 3 (NO se queda reconectando en silencio).
- [ ] El evento ERROR del paso 4 está en el diagnóstico.
- [ ] Re-escanear el QR (paso 5) restaura la conexión.

Si los tres se cumplen, la detección **mid-sesión** queda validada y la fila 550 pasa de
`◑ PARCIAL` a cubierta para ese caso.

## Si NO aparece el mensaje

- Verificá que estás en **modo embebido** (te enrolaste por QR), no directo.
- El mensaje sale desde el **loop de reconexión**: tiene que haber al menos un par de intentos
  fallidos primero (esperá unos segundos con la app en la terminal).
- Chequeá que expiraste el nodo **del teléfono** y no el del host (`remoteclaude`). Expirar el del
  host da una caída común, sin el mensaje de "acceso vencido" (es esperado).

## Límites conocidos (lo que sigue pendiente tras esta validación)

- **Reinicio-tras-vencer**: si cerrás y reabrís la app ya vencida, el nodo no llega a levantar
  (timeout de `Up` en `marvints.go`) y se derriba, así que `Estado()` devuelve `"Detenido"` y la
  detección no dispara. Cubrirlo pide mantener el nodo en `NeedsLogin` en vez de derribarlo — se
  codea **después** de esta validación, para no hacerlo a ciegas.
- **UX de re-enrolar de un toque**: hoy se reescanea el QR a mano (ya existe). Un botón directo
  desde el aviso es una mejora futura.

## Referencias en el código

- `tailscale-bridge/marvints.go` → `Estado()` (lee `LocalClient().StatusWithoutPeers()`).
- `android/.../TailscaleBridge.kt` → `estado()` / `accesoVencido()` / `accesoVencidoDeEstado()`.
- `android/.../SshTerminalSession.kt` → aviso una vez por episodio en el loop de reconexión.
- Backlog: `docs/revision-integral.md`, fila 550 (`◑ PARCIAL (F10)`).
