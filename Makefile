# Atajos de verificación. El detalle de los E2E está en test/e2e/README.md.
.PHONY: unit host lint e2e e2e-device aar all

unit:            ## tests unitarios JVM de la app (sin dispositivo)
	cd android && ./gradlew :app:testDebugUnitTest

host:            ## tests de los daemons del host
	uv run --with pytest pytest test/host/ -q

lint:            ## lint de Android + shellcheck de los scripts
	cd android && ./gradlew :app:lintDebug
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

aar:             ## reconstruye marvints.aar (Tailscale embebido) con gomobile
	tailscale-bridge/build-aar.sh

all: unit host lint
