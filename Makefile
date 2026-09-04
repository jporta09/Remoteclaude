# Atajos de verificación. El detalle de los E2E está en test/e2e/README.md.
.PHONY: unit host go lint e2e e2e-device e2e-release e2e-caja-negra release-check aar all teardown-host

# Sin esto, `make unit` y `make lint` fallaban o no según el JAVA_HOME que tuviera el shell
# de turno (el java por defecto de esta máquina es un 21 sin jlink, que AGP necesita). Que
# un target de verificación dependa del ambiente es lo contrario de lo que se le pide.
JAVA_HOME := $(shell scripts/jdk17.sh)
export JAVA_HOME

unit:            ## tests unitarios JVM de la app (sin dispositivo)
	cd android && ./gradlew :app:testDebugUnitTest

host:            ## tests de los daemons del host
	# websocket-client y pycryptodome son para test_vnc_auth: habla RFB de verdad contra el
	# visor. Si el contenedor no está arriba esos tests se saltean solos.
	uv run --with pytest --with websocket-client --with pycryptodome --with pillow pytest test/host/ -q

go:              ## bridge de Tailscale: formato, vet y tests con detector de carreras
	# Go está instalado pero fuera del PATH, así que `command -v go` no lo ve. Faltando
	# este target, `make all` no cubría el bridge y yo daba por hecho que sus tests sólo
	# corrían en CI: eran los únicos sin forma de verificarlos antes de pushear.
	cd tailscale-bridge && PATH="$$($(CURDIR)/scripts/go-bin.sh):$$PATH" sh -c '\
		test -z "$$(gofmt -l .)" || { gofmt -d .; exit 1; } && \
		go vet ./... && \
		go test -race ./...'

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

e2e-caja-negra:  ## valida el APK PUBLICADO desde afuera (sin costuras de test en el APK)
	scripts/e2e.sh --caja-negra

release-check:   ## build de release + verificación de las reglas de R8 (sin dispositivo)
	cd android && ./gradlew :app:assembleRelease

aar:             ## reconstruye marvints.aar (Tailscale embebido) con gomobile
	tailscale-bridge/build-aar.sh

teardown-host:   ## desinstala del host lo que puso setup-host.sh (units, sshd, docker, config)
	bash scripts/teardown-host.sh $(ARGS)

all: unit host go lint release-check
