package marvints

import (
	"encoding/hex"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
)

// --- SetInterfaces: es el parseo que alimenta a tsnet en Android -------------------

func TestSetInterfacesParsea(t *testing.T) {
	SetInterfaces("wlan0;3;1500;51;aabbccddeeff;192.168.1.5/24,10.0.0.2/8\n")
	ifMu.Lock()
	defer ifMu.Unlock()
	if len(ifList) != 1 {
		t.Fatalf("esperaba 1 interfaz, hay %d", len(ifList))
	}
	got := ifList[0]
	if got.Name != "wlan0" || got.Index != 3 || got.MTU != 1500 {
		t.Errorf("campos mal parseados: %+v", got.Interface)
	}
	if got.Flags != net.Flags(51) {
		t.Errorf("flags: %v", got.Flags)
	}
	if hex.EncodeToString(got.HardwareAddr) != "aabbccddeeff" {
		t.Errorf("hwaddr: %x", got.HardwareAddr)
	}
	if len(got.AltAddrs) != 2 {
		t.Errorf("esperaba 2 direcciones, hay %d", len(got.AltAddrs))
	}
}

func TestSetInterfacesDescartaLineasInvalidas(t *testing.T) {
	// líneas cortas, vacías o con basura no deben tumbar el parseo ni colarse
	SetInterfaces("\n  \nsolo;tres;campos\nlo0;1;65536;53;;127.0.0.1/8\nbasura\n")
	ifMu.Lock()
	defer ifMu.Unlock()
	if len(ifList) != 1 || ifList[0].Name != "lo0" {
		t.Fatalf("esperaba sólo lo0, quedó: %+v", ifList)
	}
}

func TestSetInterfacesSinDireccionesNoRompe(t *testing.T) {
	SetInterfaces("eth0;2;1500;1;;")
	ifMu.Lock()
	defer ifMu.Unlock()
	if len(ifList) != 1 || len(ifList[0].AltAddrs) != 0 {
		t.Fatalf("inesperado: %+v", ifList)
	}
}

func TestSetInterfacesEsConcurrenteSegura(t *testing.T) {
	// El getter registrado en init() lo llama netmon desde su propia goroutine mientras
	// la app puede estar re-alimentando la lista al cambiar de red.
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(2)
		go func() { defer wg.Done(); SetInterfaces("wlan0;3;1500;51;;10.0.0.1/8") }()
		go func() {
			defer wg.Done()
			ifMu.Lock()
			_ = len(ifList)
			ifMu.Unlock()
		}()
	}
	wg.Wait()
}

// --- Ciclo de vida: lo que rompía al re-escanear el QR -----------------------------

func TestForwardSinNodoDaError(t *testing.T) {
	mu.Lock()
	srv = nil
	mu.Unlock()
	if err := Forward(0, "remoteclaude", 22); err == nil {
		t.Fatal("Forward sin nodo debería fallar")
	} else if !strings.Contains(err.Error(), "no iniciado") {
		t.Errorf("mensaje poco claro: %v", err)
	}
}

func TestStopCierraLosListeners(t *testing.T) {
	// Se simula un forward vivo: Stop tiene que cerrarlo y soltar el puerto. Antes los
	// listeners no se registraban, así que sobrevivían al Stop y retenían el puerto
	// mientras su goroutine seguía intentando dialear contra un server ya cerrado.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	mu.Lock()
	lns = append(lns, ln)
	mu.Unlock()

	Stop()

	if _, err := ln.Accept(); err == nil {
		t.Fatal("el listener seguía aceptando después de Stop")
	}
	// y el puerto tiene que poder reusarse
	ln2, err := net.Listen("tcp", addr)
	if err != nil {
		t.Fatalf("el puerto quedó retenido tras Stop: %v", err)
	}
	ln2.Close()

	mu.Lock()
	defer mu.Unlock()
	if len(lns) != 0 {
		t.Errorf("la lista de listeners no se limpió: %d", len(lns))
	}
	if srv != nil {
		t.Error("srv debería quedar en nil tras Stop")
	}
}

func TestStopEsIdempotente(t *testing.T) {
	Stop()
	Stop() // no debe entrar en pánico ni cerrar dos veces
	if Running() {
		t.Error("Running() debería ser false tras Stop")
	}
}

// --- Estado: detección de "acceso vencido" (spike de la fila 550) -------------------

func TestEstadoSinNodoEsDetenido(t *testing.T) {
	mu.Lock()
	srv = nil
	mu.Unlock()
	got := Estado()
	if got != "Detenido;0;0" {
		t.Fatalf("con el nodo apagado esperaba \"Detenido;0;0\", dio %q", got)
	}
	// El contrato es de 3 campos separados por ';': la app lo parsea así.
	if n := strings.Count(got, ";"); n != 2 {
		t.Fatalf("el formato debe tener 3 campos (2 ';'), tiene %d", n)
	}
}

// --- Estado sticky de "vencido" (reinicio-tras-vencer, fila 550) --------------------

func TestEstadoStickyDeVencidoSobreviveAlTeardown(t *testing.T) {
	mu.Lock()
	srv = nil
	ultimoEstado = "NeedsLogin;1;1787000000"
	mu.Unlock()
	if got := Estado(); got != "NeedsLogin;1;1787000000" {
		t.Fatalf("con sticky de vencido y nodo apagado esperaba la foto, dio %q", got)
	}
	mu.Lock()
	ultimoEstado = ""
	mu.Unlock()
}

func TestStopLimpiaElSticky(t *testing.T) {
	mu.Lock()
	srv = nil
	ultimoEstado = "NeedsLogin;1;1787000000"
	mu.Unlock()
	Stop()
	if got := Estado(); got != "Detenido;0;0" {
		t.Fatalf("Stop debe limpiar el sticky (re-enrolado arranca limpio); Estado dio %q", got)
	}
}

func TestUnStartNuevoLimpiaElSticky(t *testing.T) {
	mu.Lock()
	srv = nil
	ultimoEstado = "NeedsLogin;1;1787000000"
	mu.Unlock()
	// Start con key vacía va a fallar (timeout acortado para no esperar el minuto real),
	// pero ANTES limpia el sticky del episodio anterior: lo que importa acá es ese
	// efecto, no el resultado del Up.
	viejo := upTimeout
	upTimeout = 2 * time.Second
	defer func() { upTimeout = viejo }()
	_ = Start("", t.TempDir(), "test-sticky")
	Stop() // por si llegó a levantar algo
	mu.Lock()
	limpio := ultimoEstado == "" ||
		// si el propio Start volvió a fotografiar un NeedsLogin real, también vale:
		// lo prohibido es que sobreviva la foto VIEJA de 1787000000
		!strings.Contains(ultimoEstado, "1787000000")
	mu.Unlock()
	if !limpio {
		t.Fatal("un Start nuevo debe descartar la foto sticky del episodio anterior")
	}
}

func TestFormatearEstado(t *testing.T) {
	got := formatearEstado(&ipnstate.Status{BackendState: "Running"})
	if got != "Running;0;0" {
		t.Fatalf("sin Self esperaba \"Running;0;0\", dio %q", got)
	}
}

func TestErrorDeAuthRechazada(t *testing.T) {
	casos := []struct {
		err  error
		want bool
	}{
		// El error real observado en vivo (S23, 2026-08-25) al reabrir con la key vencida.
		{fmt.Errorf("tsnet.Up: backend: invalid key: API key kxxYWHgmQ921CNTRL not valid"), true},
		{fmt.Errorf("register request: node key expired"), true},
		// Wordings plausibles del control plane que ampliamos en SRE-4p-2.
		{fmt.Errorf("authkey is no longer valid"), true},
		{fmt.Errorf("auth key is invalid"), true},
		{fmt.Errorf("the node key is expired"), true},
		{fmt.Errorf("backend state: NeedsLogin"), true},
		{fmt.Errorf("this node was logged out"), true},
		// Problemas de red: NO son vencido.
		{fmt.Errorf("tsnet.Up: context deadline exceeded"), false},
		{fmt.Errorf("dial tcp: connection refused"), false},
		{fmt.Errorf("lookup controlplane.tailscale.com: no such host"), false},
		{nil, false},
	}
	for _, c := range casos {
		if got := errorDeAuthRechazada(c.err); got != c.want {
			t.Errorf("errorDeAuthRechazada(%v) = %v, esperaba %v", c.err, got, c.want)
		}
	}
}

func TestFotoDeFallo(t *testing.T) {
	// La foto del estado, si existe, manda (venga el error que venga).
	if got := fotoDeFallo("NeedsLogin;1;99", fmt.Errorf("da igual")); got != "NeedsLogin;1;99" {
		t.Fatalf("con foto esperaba la foto, dio %q", got)
	}
	// Sin foto pero con rechazo de auth: se sintetiza el vencido.
	if got := fotoDeFallo("", fmt.Errorf("backend: invalid key: API key k1 not valid")); got != "NeedsLogin;1;0" {
		t.Fatalf("con rechazo de auth esperaba \"NeedsLogin;1;0\", dio %q", got)
	}
	// Sin foto y con error de red: nada — no se miente vencido.
	if got := fotoDeFallo("", fmt.Errorf("context deadline exceeded")); got != "" {
		t.Fatalf("con error de red esperaba \"\", dio %q", got)
	}
}

// --- Concurrencia del re-enrol (configure = Stop + Start-nuevo) ----------------------

// DEV-4A: Stop() durante un Up en vuelo NO debe cerrar el server concurrentemente al
// goroutine que está dentro de s.Up() (era una data race Close-vs-start). Con el fix,
// Stop() sólo cancela el ctx y el goroutine de Up cierra su propio server. Bajo -race,
// una regresión de esto dispara el detector.
func TestStopDuranteUpNoEsDataRace(t *testing.T) {
	mu.Lock()
	srv = nil
	ultimoEstado = ""
	mu.Unlock()
	viejo := upTimeout
	upTimeout = 15 * time.Second // largo: el Up queda colgado en NeedsLogin hasta que Stop cancele
	defer func() { upTimeout = viejo }()

	ret := make(chan error, 1)
	go func() { ret <- Start("", t.TempDir(), "test-race-stop") }()
	time.Sleep(700 * time.Millisecond) // dejar que el Up esté realmente en vuelo
	Stop()                             // cancela; el goroutine de Up debe cerrar su server

	select {
	case <-ret:
	case <-time.After(10 * time.Second):
		t.Fatal("Start no retornó tras Stop(): el cancel no destrabó el Up en vuelo")
	}
	// Tras el Stop, un re-enrolado (Start nuevo) arranca desde cero sin quedar pegado.
	if got := Estado(); got != "Detenido;0;0" && !strings.Contains(got, "NeedsLogin") {
		t.Fatalf("tras Stop esperaba Detenido o NeedsLogin, dio %q", got)
	}
}

// DEV-4B: un Up VIEJO que retorna tarde (superseded por Stop()+Start-nuevo) no debe pisar
// upErr/upDone del episodio vivo. Se simula el patrón del re-enrol repetido y se verifica que
// el estado global quede consistente (sin panic por doble close, sin quedar pegado).
func TestReenrolRepetidoNoRompe(t *testing.T) {
	mu.Lock()
	srv = nil
	ultimoEstado = ""
	mu.Unlock()
	viejo := upTimeout
	upTimeout = 3 * time.Second
	defer func() { upTimeout = viejo }()

	for i := 0; i < 3; i++ {
		ret := make(chan error, 1)
		go func() { ret <- Start("", t.TempDir(), "test-reenrol") }()
		time.Sleep(300 * time.Millisecond)
		Stop() // supersede el Up en vuelo
		select {
		case <-ret:
		case <-time.After(6 * time.Second):
			t.Fatalf("ronda %d: Start no retornó tras Stop()", i)
		}
	}
	// El estado global quedó limpio y consultable, sin deadlock.
	if got := Estado(); got == "" {
		t.Fatal("Estado() vacío tras re-enroles repetidos")
	}
}

// --- BackendState: el estado del backend que lee la app (5ª pasada, UX5-1/UF5-1) --------------

func TestBackendStateSinNodoEsStopped(t *testing.T) {
	mu.Lock()
	srv = nil
	mu.Unlock()
	Stop()
	if got := BackendState(); got != "Stopped" {
		t.Fatalf("sin nodo esperaba \"Stopped\", dio %q", got)
	}
	if EsRunning() {
		t.Fatal("sin nodo EsRunning() debe ser false")
	}
}

func TestFalloDeUpNuncaDejaRunning(t *testing.T) {
	// Start con key vacía falla (timeout corto). Lo que importa: el estado publicado NO puede
	// quedar en Running ni Starting (la app decidiría rutas por un nodo que no está), y Stop()
	// lo deja en Stopped.
	mu.Lock()
	srv = nil
	ultimoEstado = ""
	mu.Unlock()
	viejo := upTimeout
	upTimeout = 2 * time.Second
	defer func() { upTimeout = viejo }()
	_ = Start("", t.TempDir(), "test-backendstate")
	if got := BackendState(); got == "Running" || got == "Starting" {
		t.Fatalf("tras un Up fallido el estado no puede ser %q", got)
	}
	Stop()
	if got := BackendState(); got != "Stopped" {
		t.Fatalf("tras Stop esperaba \"Stopped\", dio %q", got)
	}
}

func TestResumenErrorNoFiltraElMensaje(t *testing.T) {
	err := fmt.Errorf("invalid key: API key tskey-auth-SECRETO not valid")
	got := resumenError(err)
	if strings.Contains(got, "SECRETO") || strings.Contains(got, "tskey") {
		t.Fatalf("el resumen no puede contener el mensaje crudo: %q", got)
	}
	if got != "auth rechazada por el control-plane" {
		t.Fatalf("esperaba la clase 'auth rechazada', dio %q", got)
	}
}
