# tailscale-bridge

Nodo Tailscale embebido (tsnet) que la app usa para llegar a la tailnet sin depender
de la app de Tailscale. gomobile lo bindea como `marvints.aar`.

## Reconstruir el AAR

```bash
make aar                      # arm64 + x86_64
tailscale-bridge/build-aar.sh --arm-only   # sólo arm64 (la mitad de tamaño)
```

El script **descubre la toolchain solo** (Go, gomobile, NDK y SDK) en vez de depender de
rutas de una máquina puntual, corre `gofmt`/`go vet`/`go test` antes de compilar y deja el
checksum en `marvints.aar.sha256` para detectar drift entre el binario versionado y el
fuente.

## Por qué el AAR está versionado

Para que un clone limpio y el CI puedan compilar la app sin la toolchain de Go ni el NDK.
El costo es el tamaño (~29 MB con las dos ABIs). Si el repo se vuelve pesado, la
alternativa es moverlo a git-lfs o publicarlo como release.

## ABIs

- **arm64-v8a**: el teléfono. Es lo único que entra en el APK de release.
- **x86_64**: sólo para el emulador de los E2E (se incluye únicamente en la build de
  debug), así los tests corren nativos en vez de bajo traducción ARM.
