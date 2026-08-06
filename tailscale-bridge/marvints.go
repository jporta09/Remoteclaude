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
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

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
)

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
		mu.Unlock()
		return nil
	}
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
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if _, err := s.Up(ctx); err != nil {
		mu.Lock()
		mine := srv == s
		if mine {
			srv = nil
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

// Running indica si el nodo está levantado.
func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return srv != nil
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
	mu.Unlock()

	// Cerrar los listeners despierta a sus goroutines de accept, que terminan solas.
	for _, ln := range listeners {
		ln.Close()
	}
	if old != nil {
		old.Close()
	}
}
