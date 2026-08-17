---
name: share-doc
description: Publish a file you just generated to the RemoteMarvin phone app's document viewer, so the user can open it from their phone. Use right after creating or saving an output the user would want to LOOK at — an image/chart/diagram/plot (png, jpg, webp, svg-as-png), a PDF, or a text-ish doc (txt, csv, md, log, json). Especially when the user is on the Remoteclaude/RemoteMarvin remote-dev setup (working from their phone over SSH) and asks you to "generá / hacé / dibujá / armá un (gráfico, informe, reporte, csv, pdf, imagen)" or to "mostrámelo / pasámelo / que lo pueda ver". Also use when the user says they UPLOADED a file from the phone ("te subí una foto/captura/archivo desde el celu") — those land in ~/RemoteMarvinDocs/subidos/ on the host. Skip for files that are only intermediate build artifacts, code the user edits in their editor, or anything not meant to be viewed as a finished document.
---

# Share a document to the RemoteMarvin app

In the Remoteclaude setup the user works from their phone. When you generate something
meant to be **looked at** (an image, a PDF, a CSV/txt report), copy it into the shared
docs folder so it shows up in the app's **Documentos** viewer (the sheet-of-paper brand
icon in the terminal's tab bar). The app lists `~/RemoteMarvinDocs/` on the host and
renders images (pinch-zoom), PDFs (page by page) and text natively, and shows your
shares under its **"Compartidos por Claude"** section (the user can sort the list and
delete entries from the phone).

## How

Run the helper (viene bundleado en esta skill — no hay que instalarlo ni ponerlo en el
PATH) con el/los archivo(s) que produjiste:

```bash
scripts/marvin-share.sh grafico.png informe.pdf datos.csv
```

Los archivos terminan en el `~/RemoteMarvinDocs/` del **host de la app** (el que lee el
celu) y el helper lo resuelve solo:

- **En el host** → copia local a `~/RemoteMarvinDocs/` (override con `REMOTEMARVIN_DOCS`).
- **En un server al que SSH-easte DESDE la app** → los sube por el **reverse tunnel
  (`:6090`)** al render-daemon del host, que los deja en SU `~/RemoteMarvinDocs/`. Así no
  quedan varados en el server (que la app no mira). No hay que hacer nada extra: si llegaste
  por SSH desde la app, el túnel ya está; sólo corré el helper desde donde estés.

Después avisá que está disponible: *"Lo dejé en Documentos de la app."*

**Tope del visor: 8 MB por archivo.** Más grande se comparte y lista igual, pero el
visor no lo abre (se mira por la terminal). Si el usuario lo va a querer VER en el celu,
achicalo o partilo antes de compartir (comprimir la imagen, partir el PDF, recortar el
log).

## When to do it (without being asked)

If the user is clearly working through the phone and you just produced a finished,
viewable artifact, share it proactively and mention it — don't make them ask. One call
per batch of related files is fine.

## Supported in the viewer

- **Images**: png, jpg/jpeg, webp, gif, bmp (render an SVG to PNG first).
- **PDF**: any PDF (rendered with Android's PdfRenderer).
- **Text**: txt, csv, tsv, md, log, json, yaml, xml, html, and common source files.

Other types still copy and list, but open as "tipo no soportado" — convert to one of the
above (e.g. SVG→PNG, docx→PDF) before sharing if the user needs to view it.

## The other direction: files the user uploads FROM the phone

The app can also upload files (photos, screenshots, anything from the phone's picker)
to the host. They land in **`~/RemoteMarvinDocs/subidos/`** — separate from your shares.
When the user says "te subí una foto / un archivo / una captura desde el celu", look
there (most recent first):

```bash
ls -t ~/RemoteMarvinDocs/subidos/
```

Names arrive normalized (spaces → `_`, only `A-Za-z0-9._-`), max 25 MB per file.
