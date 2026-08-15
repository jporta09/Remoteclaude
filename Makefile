# Atajos de verificación. El detalle de los E2E está en test/e2e/README.md.
.PHONY: unit host lint e2e e2e-device e2e-release release-check aar all

unit:            ## tests unitarios JVM de la app (sin dispositivo)
	cd android && ./gradlew :app:testDebugUnitTest

host:            ## tests de los daemons del host
	# websocket-client y pycryptodome son para test_vnc_auth: habla RFB de verdad contra el
	# visor. Si el contenedor no está arriba esos tests se saltean solos.
	uv run --with pytest --with websocket-client --with pycryptodome pytest test/host/ -q

lint:            ## lint de Android + shellcheck de los scripts + ruff de los daemons
	cd android && ./gradlew :app:lintDebug
	uv run --with ruff ruff check scripts/*.py test/host/*.py test/e2e/fixture/*.py
	uv run --with shellcheck-py shellcheck --severity=warning \
		scripts/*.sh scripts/marvin-stt scripts/marvin-display-allowed \
		scripts/marvin-allow-display \
		plugins/remotemarvin/scripts/*.sh plugins/remotemarvin/skills/*/scripts/*.sh

e2e:             ## suite instrumentada en un AVD liviano (forma recomendada)
	scripts/e2e.sh

e2e-device:      ## idem contra un dispositivo ya conectado (no repetible)
	scripts/e2e.sh --device

e2e-release:     ## la misma suite contra el APK minificado (valida las reglas de R8)
	E2E_RELEASE=1 scripts/e2e.sh

release-check:   ## build de release + verificación de las reglas de R8 (sin dispositivo)
	cd android && ./gradlew :app:assembleRelease

aar:             ## reconstruye marvints.aar (Tailscale embebido) con gomobile
	tailscale-bridge/build-aar.sh

all: unit host lint release-check
