// Package marvints embebe un nodo Tailscale (tsnet) dentro de la app RemoteMarvin y
// expone forwards TCP locales hacia la tailnet. gomobile lo bindea como clase Java
// `Marvints`. La app:
//  1. Start(authKey, stateDir, hostname)  -> levanta el nodo y espera a estar conectado.
//  2. Forward(2222, "remoteclaude", 22)   -> 127.0.0.1:2222 tuneliza a remoteclaude:22.
//  3. Forward(6080, "remoteclaude", 6080) -> idem para noVNC.
//
// Luego el SSH y el WebView se conectan a localhost; Tailscale hace el resto (NAT
// traversal, roaming, MagicDNS) sin depender de la app de Tailscale aparte.
package marvints

import (
	"context"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu  sync.Mutex
	srv *tsnet.Server
	// Listeners abiertos por Forward. Hay que cerrarlos en Stop: si no, su goroutine de
	// accept sigue viva contra un server ya cerrado y encima retiene el puerto local, así
	// que después de re-escanear el QR los túneles quedaban aceptando conexiones que no
	// podían dialear a ningún lado.
	lns []net.Listener
	// Start en curso: un segundo Start no debe devolver "ok" mientras el nodo todavía
	// está levantando (la app daría por buenos forwards que aún no pueden dialear).
	upDone chan struct{}
	upErr  error
	// Foto del estado tomada justo antes de derribar un nodo cuyo Up venció ESTANDO en
	// NeedsLogin/expired (reinicio-tras-vencer, fila 550): sin esto, tras el teardown
	// Estado() daba "Detenido" para siempre y la app no podía distinguir "key vencida —
	// reescaneá el QR" de un nodo apagado común. Sólo se guarda la foto si era de
	// vencido (un timeout por mala red queda en "Starting" y NO se guarda: no mentimos).
	// Se limpia en Stop() y al arrancar un Start nuevo.
	ultimoEstado string
)

// Ventana de espera de Up. Variable (y no constante) sólo para que los tests no tengan
// que esperar el minuto entero de un Up condenado a fallar.
var upTimeout = 60 * time.Second

// Android (API 30+) bloquea net.Interfaces() de Go (lee la tabla de rutas por netlink y
// da "permission denied"), lo que rompe tsnet.Up. La solución oficial de Tailscale es
// registrar un enumerador propio (RegisterInterfaceGetter); lo alimentamos desde Java
// (NetworkInterface, que sí funciona en Android) vía SetInterfaces.
var (
	ifMu   sync.Mutex
	ifList []netmon.Interface
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifMu.Lock()
		defer ifMu.Unlock()
		return ifList, nil
	})
}

// SetInterfaces recibe las interfaces enumeradas desde Java. Una por línea:
//
//	name;index;mtu;flags;hwaddrHex;cidr1,cidr2,...
//
// flags usa los bits de net.Flags (Up=1, Broadcast=2, Loopback=4, P2P=8, Multicast=16,
// Running=32). Llamar SIEMPRE antes de Start (y al cambiar de red).
func SetInterfaces(data string) {
	var out []netmon.Interface
	for _, line := range strings.Split(data, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		f := strings.Split(line, ";")
		if len(f) < 6 {
			continue
		}
		idx, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		flags, _ := strconv.Atoi(f[3])
		hw, _ := hex.DecodeString(f[4])
		ni := &net.Interface{
			Index:        idx,
			MTU:          mtu,
			Name:         f[0],
			HardwareAddr: net.HardwareAddr(hw),
			Flags:        net.Flags(flags),
		}
		var addrs []net.Addr
		if f[5] != "" {
			for _, c := range strings.Split(f[5], ",") {
				ip, ipnet, err := net.ParseCIDR(strings.TrimSpace(c))
				if err != nil || ipnet == nil {
					continue
				}
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: ipnet.Mask})
			}
		}
		out = append(out, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	ifMu.Lock()
	ifList = out
	ifMu.Unlock()
}

// Start levanta el nodo Tailscale y bloquea hasta que está conectado (o error).
// Idempotente: si ya está levantado, no hace nada.
func Start(authKey, stateDir, hostname string) error {
	mu.Lock()
	if srv != nil {
		// Si hay un Start en vuelo, esperar su resultado en vez de mentir un nil.
		if ch := upDone; ch != nil {
			mu.Unlock()
			<-ch
			mu.Lock()
			err := upErr
			mu.Unlock()
			return err
		}
		// Start ya terminó: devolver su resultado real (leído bajo el lock). Con nil a
		// secas, un Up fallido quedaba tapado y el caller daba por levantado un nodo que
		// no lo está. (Hoy un Up fallido derriba el nodo, así que acá upErr es nil en la
		// práctica — esto es defensa por si ese invariante cambia.)
		err := upErr
		mu.Unlock()
		return err
	}
	// Intento nuevo: la foto sticky del episodio anterior deja de valer.
	ultimoEstado = ""
	// En Android no hay dir por defecto escribible para los logs de tsnet (HOME,
	// /var/lib, /tmp fallan) y logpolicy.LogsDir PANICKEA. TS_LOGS_DIR se chequea
	// primero: lo apuntamos al stateDir de la app (escribible).
	if stateDir != "" {
		os.Setenv("TS_LOGS_DIR", stateDir)
	}
	s := &tsnet.Server{
		AuthKey:  authKey,
		Dir:      stateDir,
		Hostname: hostname,
	}
	// Asignar antes de Up: si la key está vencida/consumida, Up bloquea esperando login;
	// así Stop() puede cerrar el nodo y liberar el stateDir (un re-escaneo arranca limpio).
	srv = s
	done := make(chan struct{})
	upDone, upErr = done, nil
	mu.Unlock()

	// Al salir, publicar el resultado y despertar a quien esté esperando.
	finish := func(err error) error {
		mu.Lock()
		upErr = err
		upDone = nil
		mu.Unlock()
		close(done)
		return err
	}

	// Timeout: con una key válida pre-autorizada conecta en segundos; si es inválida no
	// queremos colgar para siempre, devolvemos error y limpiamos.
	ctx, cancel := context.WithTimeout(context.Background(), upTimeout)
	defer cancel()
	if _, err := s.Up(ctx); err != nil {
		// ANTES de derribar el nodo: si el backend quedó en NeedsLogin/expired (key
		// vencida o consumida), fotografiar ese estado para que Estado() lo siga
		// reportando tras el teardown (reinicio-tras-vencer). El sample usa un ctx corto
		// propio: el de Up ya está vencido. Si la foto no alcanza, el propio error de Up
		// puede ser la evidencia (ver fotoDeFallo).
		foto := fotoDeFallo(fotoSiVencido(s), err)
		log.Printf("marvints: Up falló (%v); foto=%q", err, foto)
		mu.Lock()
		mine := srv == s
		if mine {
			srv = nil
			if foto != "" {
				ultimoEstado = foto
			}
		}
		mu.Unlock()
		// Sólo cerramos si seguimos siendo el server vigente: si Stop() se adelantó, ya lo
		// cerró él y volver a cerrarlo es una doble liberación.
		if mine {
			s.Close()
		}
		return finish(err)
	}
	return finish(nil)
}

// fotoSiVencido consulta el estado del server y devuelve la línea de Estado() SOLO si el
// backend está en NeedsLogin o con la key expirada; "" en cualquier otro caso (mala red,
// Starting, error de consulta). Así el sticky nunca miente "vencido" por un timeout de red.
func fotoSiVencido(s *tsnet.Server) string {
	lc, err := s.LocalClient()
	if err != nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil || st == nil {
		return ""
	}
	expired := st.Self != nil && st.Self.Expired
	if st.BackendState != "NeedsLogin" && !expired {
		return ""
	}
	linea := formatearEstado(st)
	return linea
}

// fotoDeFallo decide qué sticky guardar cuando Up falló: la foto del estado si dio
// vencido, y si no, el propio error de Up cuando es un rechazo de credenciales del
// control plane. Ese segundo camino existe porque se observó EN VIVO (S23, 2026-08-25)
// que el arranque con la node key ya vencida no muere por timeout: tsnet intenta
// re-registrarse con la auth key guardada, el control la rechaza en segundos
// ("invalid key: API key … not valid") y en ese instante el backend todavía no pasó a
// NeedsLogin — la foto sale vacía. Un rechazo de credenciales significa exactamente
// "hay que reescanear el QR", así que se sintetiza el vencido igual.
func fotoDeFallo(foto string, err error) string {
	if foto != "" {
		return foto
	}
	if errorDeAuthRechazada(err) {
		return "NeedsLogin;1;0"
	}
	return ""
}

// errorDeAuthRechazada reconoce los errores de Up que son un rechazo de auth del control
// plane (key inválida/vencida), y NO problemas de red (timeout, DNS, refused) — sobre esos
// no hay que mentir "vencido".
func errorDeAuthRechazada(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "invalid key") || strings.Contains(msg, "key expired")
}

// Running indica si el nodo está levantado.
func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return srv != nil
}

// Estado consulta el estado del nodo por el LocalClient, para que la app pueda distinguir
// "la red está mal" de "el acceso de Tailscale VENCIÓ y hay que re-escanear el QR". Esto último
// pasa a los ~180 días, cuando la node key expira: el backend cae a NeedsLogin y todo deja de
// dialear sin explicación (fila 550 de la revisión).
//
// Formato (una línea, para bindear fácil por gomobile):
//
//	<backendState>;<expired 0|1>;<keyExpiryEpoch|0>
//
// backendState es el string de ipn.State: "Running", "NeedsLogin", "Stopped", "Starting"…
// Con el nodo apagado devuelve "Detenido;0;0"; si no se pudo consultar, "Desconocido;0;0".
// La app decide "hay que re-enrolar" con NeedsLogin o expired=1 (ver estadoVencido en Kotlin).
func Estado() string {
	mu.Lock()
	s := srv
	sticky := ultimoEstado
	mu.Unlock()
	if s == nil {
		// Nodo apagado: si el último episodio terminó VENCIDO (reinicio-tras-vencer),
		// reportarlo — la app necesita mostrar "reescaneá el QR", no un "Detenido" mudo.
		if sticky != "" {
			return sticky
		}
		return "Detenido;0;0"
	}
	lc, err := s.LocalClient()
	if err != nil {
		return "Desconocido;0;0"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// StatusWithoutPeers: sólo se necesita Self + BackendState, no enumerar la tailnet entera.
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil || st == nil {
		return "Desconocido;0;0"
	}
	return formatearEstado(st)
}

// formatearEstado arma la línea "<backendState>;<expired 0|1>;<keyExpiryEpoch|0>".
func formatearEstado(st *ipnstate.Status) string {
	expired := "0"
	var exp int64
	if st.Self != nil {
		if st.Self.Expired {
			expired = "1"
		}
		if st.Self.KeyExpiry != nil {
			exp = st.Self.KeyExpiry.Unix()
		}
	}
	return fmt.Sprintf("%s;%s;%d", st.BackendState, expired, exp)
}

// Forward abre un listener TCP local en 127.0.0.1:localPort que tuneliza cada conexión
// a remoteHost:remotePort por la tailnet. No bloquea.
func Forward(localPort int, remoteHost string, remotePort int) error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return fmt.Errorf("tailscale no iniciado")
	}
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", localPort))
	if err != nil {
		return err
	}
	mu.Lock()
	lns = append(lns, ln)
	mu.Unlock()

	remote := fmt.Sprintf("%s:%d", remoteHost, remotePort)
	go func() {
		defer ln.Close()
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			// Si el nodo cambió (re-escaneo del QR), este listener es de un server
			// muerto: se corta acá en vez de aceptar conexiones que no van a ningún lado.
			mu.Lock()
			current := srv
			mu.Unlock()
			if current != s {
				c.Close()
				return
			}
			go pipe(s, c, remote)
		}
	}()
	return nil
}

func pipe(s *tsnet.Server, local net.Conn, remote string) {
	defer local.Close()
	// Con timeout: sin él, durante una caída de red cada reintento del cliente dejaba una
	// goroutine y una conexión colgadas indefinidamente dentro de Dial.
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	rc, err := s.Dial(ctx, "tcp", remote)
	if err != nil {
		return
	}
	defer rc.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(rc, local); done <- struct{}{} }()
	go func() { io.Copy(local, rc); done <- struct{}{} }()
	<-done
}

// Stop apaga el nodo y cierra los forwards abiertos.
func Stop() {
	mu.Lock()
	old := srv
	srv = nil
	listeners := lns
	lns = nil
	// Stop es deliberado (re-enrolado/teardown): el sticky de "vencido" deja de valer.
	ultimoEstado = ""
	mu.Unlock()

	// Cerrar los listeners despierta a sus goroutines de accept, que terminan solas.
	for _, ln := range listeners {
		ln.Close()
	}
	if old != nil {
		old.Close()
	}
}
