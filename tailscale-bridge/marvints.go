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
	"sync/atomic"
	"time"

	"tailscale.com/ipn"
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
	// cancel del ctx del Up en vuelo. Stop() lo llama para DESTRABAR un Up colgado (key
	// vencida) en vez de cerrar el server de forma concurrente al goroutine que está dentro
	// de s.Up() — eso era una data race (Close vs start, DEV-4A). Con esto el goroutine de
	// Up es el ÚNICO que cierra su server; Stop() sólo cancela y, si el Up ya terminó (nodo
	// vivo, sin goroutine), cierra él. Se setea en Start bajo mu y se limpia en finish/Stop.
	upCancel context.CancelFunc
	// Estado del backend (ipn.State: "Running", "NeedsLogin", "Starting", "Stopped"…) mantenido
	// por vigilarEstado(), el observador del bus IPN. Es lo que la app lee para decidir si el
	// nodo está VIVO: antes usaba un latch propio (`started`) que sólo bajaba al re-enrolar, así
	// que un nodo que caía a NeedsLogin a mitad de sesión seguía "listo" para la app y mandaba
	// hasta la LAN por el netstack muerto (UX5-1/UF5-1, 5ª pasada). Lectura atómica y barata:
	// sin JNI bloqueante (Estado() consulta al LocalClient con hasta 5 s de espera).
	estadoBackend atomic.Value // string; "" = nunca arrancó -> "Stopped"
	// cancel del observador vigente; Stop() lo corta. Bajo mu.
	watchCancel context.CancelFunc
)

func fijarEstado(s string) { estadoBackend.Store(s) }

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
	fijarEstado("Starting")
	done := make(chan struct{})
	upDone, upErr = done, nil
	// Timeout: con una key válida pre-autorizada conecta en segundos; si es inválida no
	// queremos colgar para siempre. El cancel se publica bajo mu para que Stop() lo pueda
	// llamar y destrabar el Up sin cerrar el server concurrentemente (DEV-4A).
	ctx, cancel := context.WithTimeout(context.Background(), upTimeout)
	defer cancel()
	upCancel = cancel
	mu.Unlock()

	// Al salir, publicar el resultado y despertar a quien esté esperando. Sólo pisa el
	// estado global si SEGUIMOS siendo el intento vigente (upDone == done): un Up viejo
	// superseded por un Stop()+Start(nuevo) no debe pisar upErr/upDone del episodio vivo
	// (DEV-4B). El close(done) siempre corre: despierta a los que esperan ESTE intento.
	finish := func(err error) error {
		mu.Lock()
		if upDone == done {
			upErr = err
			upDone = nil
			upCancel = nil
		}
		mu.Unlock()
		close(done)
		return err
	}

	if _, err := s.Up(ctx); err != nil {
		// ANTES de derribar el nodo: si el backend quedó en NeedsLogin/expired (key
		// vencida o consumida), fotografiar ese estado para que Estado() lo siga
		// reportando tras el teardown (reinicio-tras-vencer). El sample usa un ctx corto
		// propio: el de Up ya está vencido. Si la foto no alcanza, el propio error de Up
		// puede ser la evidencia (ver fotoDeFallo).
		foto := fotoDeFallo(fotoSiVencido(s), err)
		if foto == "" {
			// Ni la foto ni el error se reconocen como "vencido": si Tailscale cambió el
			// wording del rechazo de credenciales, esto es lo que hay que mirar en los logs
			// para actualizar errorDeAuthRechazada (SRE-4p-2).
			// Sólo la CLASE del error, no el texto crudo: el control-plane podría ecoar la key o el
			// token rechazado y esto va a logcat, legible por adb/USB (SEC5-3).
			log.Printf("marvints: Up falló SIN clasificar como vencido (%s) — si es un rechazo de auth, ampliar errorDeAuthRechazada", resumenError(err))
		} else {
			log.Printf("marvints: Up falló (%s); vencido detectado, foto=%q", resumenError(err), foto)
		}
		mu.Lock()
		mine := srv == s
		if mine {
			srv = nil
			if foto != "" {
				ultimoEstado = foto
			}
		}
		mu.Unlock()
		if mine {
			if foto != "" {
				fijarEstado("NeedsLogin")
			} else {
				fijarEstado("Stopped")
			}
		}
		// El goroutine de Up es el ÚNICO que cierra su server: cierra SIEMPRE que el Up
		// falló (era el dueño de s mientras Up corría). Stop() nunca cierra un server con Up
		// en vuelo — sólo cancela y espera que lo cierre acá. Así no hay Close concurrente
		// (DEV-4A) ni doble liberación.
		s.Close()
		return finish(err)
	}
	// Up tuvo éxito. Si mientras tanto un Stop()/Start-nuevo nos superseded (srv != s), este
	// server quedó huérfano: Stop() no lo cerró (esperaba que lo cerremos acá), así que lo
	// cerramos. Si seguimos vigentes, el nodo queda VIVO y lo cerrará el próximo Stop().
	var wctx context.Context
	mu.Lock()
	mine := srv == s
	if mine {
		wctx = nuevoCtxObservador() // guarda el cancel en watchCancel (bajo mu); lo corta Stop()
	}
	mu.Unlock()
	if !mine {
		s.Close()
		return finish(nil)
	}
	// Up devuelve con el backend en Running. Desde acá el observador del bus IPN mantiene
	// estadoBackend al día: si la node key vence o la revocan a mitad de sesión, el backend cae
	// a NeedsLogin y la app lo ve al instante (sin esto sólo lo veía al reiniciar).
	fijarEstado("Running")
	go vigilarEstado(wctx, s)
	return finish(nil)
}

// nuevoCtxObservador crea el ctx del observador y deja su cancel en watchCancel. Llamar con mu
// tomado. Va en función aparte para que el cancel tenga un dueño explícito (Stop) y vet no lo
// tome por una fuga.
func nuevoCtxObservador() context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	watchCancel = cancel
	return ctx
}

// vigilarEstado sigue el bus IPN del server y publica cada cambio de ipn.State en
// estadoBackend. Termina cuando el ctx se cancela (Stop), el server deja de ser el vigente
// (re-enrol) o el bus se cierra.
func vigilarEstado(ctx context.Context, s *tsnet.Server) {
	lc, err := s.LocalClient()
	if err != nil {
		return
	}
	w, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		return
	}
	defer w.Close()
	for {
		n, err := w.Next()
		if err != nil {
			return
		}
		if n.State == nil {
			continue
		}
		mu.Lock()
		vigente := srv == s
		mu.Unlock()
		if !vigente {
			return
		}
		fijarEstado(n.State.String())
	}
}

// resumenError describe un error de Up por su CLASE (auth rechazada / timeout / otro), sin el
// mensaje crudo del control-plane. Es lo único que va al log.
func resumenError(err error) string {
	switch {
	case err == nil:
		return "sin error"
	case errorDeAuthRechazada(err):
		return "auth rechazada por el control-plane"
	case strings.Contains(strings.ToLower(err.Error()), "deadline") || strings.Contains(strings.ToLower(err.Error()), "timeout"):
		return "timeout"
	default:
		return fmt.Sprintf("otro (%T)", err)
	}
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
// plane (key inválida/vencida/consumida), y NO problemas de red (timeout, DNS, refused) —
// sobre esos no hay que mentir "vencido". La lista de frases es defensiva ante el wording
// del control plane, que es de un tercero y puede cambiar (SRE-4p-2): si un rechazo real deja
// de matchear, Start loguea "Up falló SIN clasificar" para que se agregue la frase nueva acá.
func errorDeAuthRechazada(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	frases := []string{
		"invalid key",     // "invalid key: API key … not valid" (observado en vivo)
		"key expired",     // node key vencida
		"key is expired",  //
		"key is invalid",  //
		"authkey",         // "authkey is invalid / expired / already used"
		"auth key",        //
		"no longer valid", //
		"needs login",     // el backend pide re-login
		"needslogin",      //
		"logged out",      //
		"expired",         // fallback amplio: en un fallo de Up, "expired" es de la key
	}
	for _, f := range frases {
		if strings.Contains(msg, f) {
			return true
		}
	}
	return false
}

// Running indica si hay un server creado (no si está conectado: ver EsRunning).
func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return srv != nil
}

// BackendState devuelve el último ipn.State visto por el observador del bus ("Running",
// "NeedsLogin", "Starting", "Stopped"…). Atómico y barato: la app lo puede leer en el hilo
// principal. Sin nodo o antes del primer Start: "Stopped".
func BackendState() string {
	v, _ := estadoBackend.Load().(string)
	if v == "" {
		return "Stopped"
	}
	return v
}

// EsRunning: el nodo está VIVO y conectado a la tailnet (BackendState == Running). Es lo que
// decide si los forwards embebidos sirven; con cualquier otro estado la app va directo.
func EsRunning() bool { return BackendState() == "Running" }

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

// IdentidadRed devuelve una descripción legible de a qué tailnet está vinculado el nodo, para
// que la app pueda mostrar "te vinculaste a la tailnet ⟨X⟩" tras re-enrolar (A4-1: hoy el
// re-enrol reconfigura la identidad de red sin ningún feedback de a qué red entraste). Prefiere
// el nombre del tailnet; si no, el DNSName del propio nodo. "" si no hay nodo o no se pudo consultar.
func IdentidadRed() string {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return ""
	}
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
	if st.CurrentTailnet != nil && st.CurrentTailnet.Name != "" {
		return st.CurrentTailnet.Name
	}
	if st.Self != nil {
		return st.Self.DNSName
	}
	return ""
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
	// Un Up en vuelo (upDone != nil) implica que el goroutine de Up todavía es dueño de `old`
	// y lo va a cerrar él tras retornar. Stop() NO debe cerrarlo en ese caso (sería Close
	// concurrente con s.Up() = data race, DEV-4A): sólo cancela para destrabarlo. Si el Up ya
	// terminó (nodo vivo, sin goroutine), lo cierra Stop().
	upEnVuelo := upDone != nil
	cancel := upCancel
	wc := watchCancel
	watchCancel = nil
	listeners := lns
	lns = nil
	// Stop es deliberado (re-enrolado/teardown): el sticky de "vencido" deja de valer.
	ultimoEstado = ""
	mu.Unlock()

	// El observador del bus se corta y el estado publicado pasa a Stopped (un Start nuevo lo
	// vuelve a poner en Starting/Running).
	if wc != nil {
		wc()
	}
	fijarEstado("Stopped")
	// Cerrar los listeners despierta a sus goroutines de accept, que terminan solas.
	for _, ln := range listeners {
		ln.Close()
	}
	// Destrabar un Up colgado; su goroutine cerrará el server (closer único).
	if cancel != nil {
		cancel()
	}
	// Sólo cerramos si NO hay Up en vuelo dueño de este server.
	if old != nil && !upEnVuelo {
		old.Close()
	}
}
