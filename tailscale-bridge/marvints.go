// Package marvints embebe un nodo Tailscale (tsnet) dentro de la app RemoteMarvin y
// expone forwards TCP locales hacia la tailnet. gomobile lo bindea como clase Java
// `Marvints`. La app:
//   1. Start(authKey, stateDir, hostname)  -> levanta el nodo y espera a estar conectado.
//   2. Forward(2222, "remoteclaude", 22)   -> 127.0.0.1:2222 tuneliza a remoteclaude:22.
//   3. Forward(6080, "remoteclaude", 6080) -> idem para noVNC.
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
	mu.Unlock()

	// Timeout: con una key válida pre-autorizada conecta en segundos; si es inválida no
	// queremos colgar para siempre, devolvemos error y limpiamos.
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if _, err := s.Up(ctx); err != nil {
		mu.Lock()
		if srv == s {
			srv = nil
		}
		mu.Unlock()
		s.Close()
		return err
	}
	return nil
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
	remote := fmt.Sprintf("%s:%d", remoteHost, remotePort)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go pipe(s, c, remote)
		}
	}()
	return nil
}

func pipe(s *tsnet.Server, local net.Conn, remote string) {
	defer local.Close()
	rc, err := s.Dial(context.Background(), "tcp", remote)
	if err != nil {
		return
	}
	defer rc.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(rc, local); done <- struct{}{} }()
	go func() { io.Copy(local, rc); done <- struct{}{} }()
	<-done
}

// Stop apaga el nodo.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		srv.Close()
		srv = nil
	}
}
