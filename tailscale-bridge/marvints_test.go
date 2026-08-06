package marvints

import (
	"encoding/hex"
	"net"
	"strings"
	"sync"
	"testing"
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
