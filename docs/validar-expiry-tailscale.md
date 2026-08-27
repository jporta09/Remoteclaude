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

---

## RESULTADO (2026-08-24): validación mid-sesión APROBADA ✅

Los tres criterios se cumplieron en vivo (S23 real, tailnet real):

1. ✅ El aviso apareció en la terminal, una sola vez, en vez de reconexión muda.
2. ✅ El evento ERROR quedó en el Diagnóstico: `tailscale: el acceso de Tailscale venció (node
   key expirada) — hay que reescanear el QR` (19:25).
3. ✅ Re-escanear el QR restauró la conexión (nodo nuevo `expired=false online=true`, terminal
   reconectada sola).

**Hallazgos del test (importantes para el modelo mental):**

- **Los nodos con tag NO expiran por default.** Tailscale deshabilita el key expiry para
  dispositivos tagueados (`tag:remotemarvin`) — la consola los muestra "Expiry disabled". El
  escenario "vence a los ~180 días" sólo ocurre si se habilita expiry a mano en el nodo (o si el
  admin expira/revoca). El manual se corrigió acorde.
- **La consola NO ofrece "Expire key now"** para estos nodos (creados por OAuth client): el menú
  de Machine settings no lo trae. Se expira por API: `POST /api/v2/device/{id}/expire` con un API
  access token de la cuenta (el OAuth client del .env NO alcanza: scope sólo de auth keys, da 403).
  El node ID sale del estado de Tailscale del host (peer del celu).
- **La expulsión no es inmediata**: con una sesión WireGuard viva, el nodo expirado siguió
  funcionando ~18 minutos (hasta el siguiente re-handshake con el control plane). Recién ahí la
  reconexión falla y el aviso dispara. En uso real (cambio de red al moverse) el re-handshake es
  inmediato, así que el aviso aparece enseguida; el caso lento es "quieto en la misma red".
- **El QR ANSI de la terminal puede no engancharse** con la cámara (contraste/fuente): el
  re-enrolado falló hasta usar el QR como imagen. Fix shipped: `ts-link-qr.sh --png`.

**Sigue pendiente** (ya estaba en "Límites conocidos"): reinicio-tras-vencer (el nodo no levanta y
`Estado()` da "Detenido" — hay que mantenerlo en NeedsLogin para poder detectarlo) y la UX de
re-enrolar de un toque desde el aviso.

---

## Reinicio-tras-vencer (v1.30.0): cómo validarlo

Con el estado sticky del bridge (v1.30.0), el caso "cerrar y reabrir la app ya vencida" quedó
cubierto. Para validarlo en vivo:

1. Expirá el nodo del celu por API (la consola no trae el botón para nodos OAuth+tag):
   generá un API access token en Settings → Keys. **OJO (validado 2026-08-25): los nodos con
   tag traen `keyExpiryDisabled` y ahí `/expire` devuelve 200 pero es un NO-OP** — primero
   habilitá el expiry y recién después expirá:
   `POST /api/v2/device/<id>/key {"keyExpiryDisabled": false}` →
   `POST /api/v2/device/<id>/expire`
   (el node ID sale del estado de Tailscale del host). Revocá el token al terminar.
2. **Cerrá la app del todo** (force-stop o deslizarla de recientes) y reabrila.
3. Esperado en la pantalla de hosts: la línea de la VPN pasa a **"acceso vencido — tocá y
   reescaneá el QR"** en rojo en ~10s (validado: 9s — el control rechaza la re-registración
   con `invalid key` al toque, no espera el timeout de 60s; antes de v1.30.0 quedaba
   "conectando…"/"error" sin causa). En la terminal: el banner de vencido + la barra con
   **↺ Reescanear QR** (título "‹ Host" en rojo, sin sufijo — el texto largo se aplastaba).
4. Re-enrolar de un toque: tocá ↺ → scanner → `./scripts/ts-link-qr.sh --png` en la PC →
   escanear → reconecta sola (nodo nuevo en la tailnet; los vencidos viejos se borran de la
   consola cuando quieras).

Matices que van a aparecer al validar (todos vistos en vivo, 2026-08-25):

- **Si matás y reabrís la app MUY rápido tras el expire** (antes de que el netmap con el
  vencimiento le llegue), puede levantar "conectada ✓" con el estado cacheado — es el mismo
  período de gracia de ~15-20 min de la sesión WireGuard viva; el control la echa solo después.
- **Si la auth key guardada todavía es válida** (escaneada hace poco: son de un solo uso y
  10 min), tsnet se re-registra solo y la app SE CURA SIN AVISO (self-heal, aparece un nodo
  nuevo en la tailnet). El "acceso vencido" sólo aparece cuando de verdad hace falta reescanear
  — que es exactamente el comportamiento deseado.
