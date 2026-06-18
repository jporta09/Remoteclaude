# tailscale-bridge

Wrapper Go sobre [`tsnet`](https://pkg.go.dev/tailscale.com/tsnet) que embebe un nodo
Tailscale en userspace dentro de la app RemoteMarvin y expone forwards TCP locales hacia
la tailnet (SSH + noVNC se conectan a `127.0.0.1`, sin depender de la app de Tailscale).

Se bindea con **gomobile** a un `.aar` (`marvints.aar`), que se copia a `android/app/libs/`.

## Reconstruir el AAR

Requisitos: Go 1.23+, Android NDK, gomobile.

```bash
export GOROOT=/home/jporta/toolchain/go GOPATH=/home/jporta/go
export PATH=$GOROOT/bin:$GOPATH/bin:$PATH
export ANDROID_HOME=/home/jporta/.buildozer/android/platform/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.3.11579264

gomobile bind -target=android/arm64 -androidapi 26 -o marvints.aar .
cp marvints.aar ../android/app/libs/
```

> Sólo se compila `android/arm64` (el celular de prueba). Para distribuir a más
> dispositivos: `-target=android/arm64,android/arm,android/amd64` y sacar el
> `abiFilters` de `app/build.gradle.kts`.
