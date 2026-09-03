# Agentes-perfil del programa de evaluación

Las **definiciones** de los 9 agentes-perfil (los 8 originales + `disenador-grafico`, opcional,
sumado el 2026-09-03; más el remediador) que evalúan RemoteMarvin
según `docs/programa-evaluacion-personas.md`. La **fuente de verdad es este directorio**;
para usarlos hay que instalarlos en el perfil de Claude Code desde el que se trabaja:

```bash
cp docs/agentes/*.md ~/.claude-personal/agents/   # o $CLAUDE_CONFIG_DIR/agents/
```

Dos cosas que a propósito NO viven en el repo:

- **Las memorias** (`~/.claude-personal/agents/memoria/*.md`): son estado de runtime de cada
  agente (hallazgos acumulados, brief original, perspectiva). Cada instalación construye la
  suya; las defs las referencian por ruta local y los agentes arrancan con memoria vacía si
  no existe.
- **El historial de pasadas**: vive en `docs/revision-integral.md` (ese sí en el repo).

Si editás una def, editala ACÁ y re-copiala al perfil (la copia instalada es un artefacto,
como un binario). El playbook §"Nota de registro" explica cuándo toma efecto un cambio.
