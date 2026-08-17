# Plugin: remotemarvin

Empaqueta las skills de Claude Code para usar las capacidades de la app **RemoteMarvin**
(terminal remota + navegador headed + documentos, desde el celular, sobre Tailscale).

## Skills

- **remotemarvin** — guía/índice: resume las capacidades de la app y rutea a las demás.
- **share-doc** — publica un archivo generado (imagen/PDF/txt/csv) al visor de
  Documentos del celu (vía `scripts/marvin-share.sh`, bundleado en la skill), y sabe
  dónde caen los archivos que el usuario sube desde el teléfono
  (`~/RemoteMarvinDocs/subidos/`).
- **headed-browser** — corre un navegador *headed* visible por noVNC (`:99`/`:6080`) o en
  el monitor local.

Invocadas como `remotemarvin:remotemarvin`, `remotemarvin:share-doc`,
`remotemarvin:headed-browser`.

## Instalar (desde el repo, marketplace local)

```
/plugin marketplace add /home/jporta/proyectos/Remoteclaude
/plugin install remotemarvin@remotemarvin
```

Para iterar en desarrollo sin instalar:

```
claude --plugin-dir /home/jporta/proyectos/Remoteclaude/plugins/remotemarvin
```

## Estructura

```
plugins/remotemarvin/
├── .claude-plugin/plugin.json
└── skills/
    ├── remotemarvin/SKILL.md
    ├── share-doc/SKILL.md
    └── headed-browser/{SKILL.md, scripts/, examples/, evals/}
```
