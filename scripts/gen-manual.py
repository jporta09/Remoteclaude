"""Genera el manual de RemoteMarvin en PDF, con la identidad de marca Marvin.
    uv run --with reportlab python scripts/gen-manual.py <salida.pdf>
"""

import sys

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate,
    Frame,
    NextPageTemplate,
    PageBreak,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)

OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/RemoteMarvin-Manual.pdf"
FONTS = "android/app/src/main/res/font"
LOGO = "android/app/src/main/res/drawable-nodpi/marvin_isologo.png"

# --- Paleta CTR -------------------------------------------------------------
GREEN = colors.HexColor("#71BF44")
PETROL = colors.HexColor("#0F232D")
AMBER = colors.HexColor("#FDB940")
LIGHT = colors.HexColor("#F2F2F2")
MUTED = colors.HexColor("#5E8B7E")
INK = colors.HexColor("#15242B")
SURFACE = colors.HexColor("#EAF0EC")

# --- Fuentes ----------------------------------------------------------------
pdfmetrics.registerFont(TTFont("Title", f"{FONTS}/osifont.ttf"))
pdfmetrics.registerFont(TTFont("Body", f"{FONTS}/ubuntu.ttf"))
pdfmetrics.registerFont(TTFont("Mono", f"{FONTS}/mononoki.ttf"))
pdfmetrics.registerFont(TTFont("MonoB", f"{FONTS}/mononoki_bold.ttf"))
# "Detalles y comentarios" del manual de marca: iba en Brandon Grotesque, que es comercial
# y no se puede embeber en un PDF que se distribuye. Jost es del mismo linaje geométrico
# (revival de Futura/Erbar, como Brandon) y sus proporciones calzan: x-height/cap 0.657
# contra 0.660. Bold para los titulares de esos bloques y Light para el texto, como manda
# la especificación.
pdfmetrics.registerFont(TTFont("Detail", "scripts/manual-fonts/Jost-Light.ttf"))
pdfmetrics.registerFont(TTFont("DetailB", "scripts/manual-fonts/Jost-Bold.ttf"))
# Las fuentes de marca no tienen flechas/checks/bullets; DejaVu sí.
pdfmetrics.registerFont(TTFont("Sym", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
# Y DejaVu tampoco tiene los emoji de los botones de la app. Subset de Noto Emoji
# (SIL OFL 1.1) con los cuatro glifos que usamos: 2 KB en vez de los 1,9 MB del original.
pdfmetrics.registerFont(TTFont("Emoji", "scripts/manual-fonts/NotoEmoji-subset.ttf"))

# --- Estilos ----------------------------------------------------------------
h1 = ParagraphStyle("h1", fontName="Title", fontSize=20, textColor=PETROL,
                    spaceBefore=18, spaceAfter=8, leading=23)
h2 = ParagraphStyle("h2", fontName="Title", fontSize=14, textColor=GREEN,
                    spaceBefore=12, spaceAfter=4, leading=17)
body = ParagraphStyle("body", fontName="Body", fontSize=10.5, textColor=INK,
                      leading=16, spaceAfter=6, alignment=TA_LEFT)
bullet = ParagraphStyle("bullet", parent=body, leftIndent=14, bulletIndent=2, spaceAfter=3)
code = ParagraphStyle("code", fontName="Mono", fontSize=9, textColor=PETROL,
                      backColor=SURFACE, leading=13, leftIndent=8, rightIndent=8,
                      spaceBefore=4, spaceAfter=8, borderPadding=(6, 6, 6, 6))
# Detalles y comentarios: viñetas, recuadros, texto flotante y epígrafes. Antes usaba la
# mono, que el manual de marca reserva para los SUBTÍTULOS: dos roles pisándose.
small = ParagraphStyle("small", fontName="Detail", fontSize=9, textColor=MUTED, leading=12)
smallB = ParagraphStyle("smallB", parent=small, fontName="DetailB", textColor=INK)
icon = ParagraphStyle("icon", fontName="Sym", fontSize=13, textColor=GREEN, leading=16)


_SYMS = {"→": "&#8594;", "✓": "&#10003;", "⟳": "&#10227;", "▸": "&#9656;",
         "•": "&#8226;", "⇧": "&#8679;"}
# Emoji de los botones de la app. Van en otra fuente que los símbolos de arriba porque
# DejaVu no los tiene y Noto Emoji no tiene los otros.
_EMOJI = {"🎤": "&#127908;", "🖥": "&#128421;", "📄": "&#128196;", "🔑": "&#128273;"}


def _sym(t):
    for ch, ent in _SYMS.items():
        t = t.replace(ch, f"<font name='Sym'>{ent}</font>")
    for ch, ent in _EMOJI.items():
        t = t.replace(ch, f"<font name='Emoji'>{ent}</font>")
    return t


def P(t, s=body):
    return Paragraph(_sym(t), s)


def B(t):
    return Paragraph(f"<font name='Sym' color='#71BF44'>&#9656;</font>&nbsp; {_sym(t)}", bullet)


def C(t):
    esc = t.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br/>")
    return Paragraph(esc, code)


# --- Portada y fondos -------------------------------------------------------
def cover(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(PETROL)
    canvas.rect(0, 0, A4[0], A4[1], stroke=0, fill=1)
    try:
        from reportlab.lib.utils import ImageReader
        img = ImageReader(LOGO)
        iw, ih = img.getSize()
        w = 7 * cm
        h = w * ih / iw
        canvas.drawImage(img, (A4[0] - w) / 2, A4[1] - 8 * cm - h, w, h,
                         mask="auto", preserveAspectRatio=True)
    except Exception as e:  # noqa: BLE001 - el logo es decorativo, no vale abortar el PDF
        print(f"[gen-manual] sin logo en la portada: {e}")
    canvas.setFillColor(GREEN)
    canvas.setFont("Title", 46)
    canvas.drawCentredString(A4[0] / 2, A4[1] - 13.5 * cm, "RemoteMarvin")
    canvas.setFillColor(LIGHT)
    canvas.setFont("Body", 14)
    canvas.drawCentredString(A4[0] / 2, A4[1] - 15.2 * cm,
                             "Terminal remota + navegador + documentos, desde el celular")
    canvas.setFillColor(AMBER)
    canvas.setFont("Mono", 10)
    canvas.drawCentredString(A4[0] / 2, 2.2 * cm, "Manual de uso  ·  v1")
    canvas.setFillColor(MUTED)
    canvas.drawCentredString(A4[0] / 2, 1.6 * cm, "Marvin Software Solutions")
    canvas.restoreState()


def content_bg(canvas, doc):
    canvas.saveState()
    canvas.setFillColor(colors.white)
    canvas.rect(0, 0, A4[0], A4[1], stroke=0, fill=1)
    # barra superior petróleo
    canvas.setFillColor(PETROL)
    canvas.rect(0, A4[1] - 1.1 * cm, A4[0], 1.1 * cm, stroke=0, fill=1)
    canvas.setFillColor(GREEN)
    canvas.setFont("Title", 11)
    canvas.drawString(2 * cm, A4[1] - 0.78 * cm, "RemoteMarvin")
    canvas.setFillColor(MUTED)
    canvas.setFont("Mono", 8)
    canvas.drawRightString(A4[0] - 2 * cm, A4[1] - 0.78 * cm, "Manual de uso")
    # pie
    canvas.setStrokeColor(SURFACE)
    canvas.line(2 * cm, 1.4 * cm, A4[0] - 2 * cm, 1.4 * cm)
    canvas.setFillColor(MUTED)
    canvas.setFont("Mono", 8)
    canvas.drawRightString(A4[0] - 2 * cm, 1.0 * cm, f"pág. {doc.page - 1}")
    canvas.restoreState()


doc = BaseDocTemplate(OUT, pagesize=A4,
                      leftMargin=2 * cm, rightMargin=2 * cm,
                      topMargin=1.7 * cm, bottomMargin=1.9 * cm)
cover_frame = Frame(0, 0, A4[0], A4[1], id="cover")
text_frame = Frame(2 * cm, 1.9 * cm, A4[0] - 4 * cm, A4[1] - 3.6 * cm, id="text")
doc.addPageTemplates([
    PageTemplate(id="cover", frames=[cover_frame], onPage=cover),
    PageTemplate(id="content", frames=[text_frame], onPage=content_bg),
])

S = []
S.append(NextPageTemplate("content"))
S.append(PageBreak())

# --- 1. Qué es --------------------------------------------------------------
S.append(P("¿Qué es RemoteMarvin?", h1))
S.append(P(
    "Una app Android para <b>controlar tu PC desde el celular</b>: terminal con "
    "sesiones persistentes, un visor del navegador de la PC, y un visor de documentos. "
    "Todo viaja por <b>Tailscale</b> (red privada que sobrevive cortes y cambios de red), "
    "con el nodo de VPN <b>embebido en la propia app</b> — no necesitás la app de "
    "Tailscale aparte."))
S.append(P("En una frase", h2))
S.append(P("Abrís la app, tocás tu PC, y tenés una terminal real del host (no de un "
           "contenedor) como si estuvieras sentado en la máquina — más un navegador y "
           "tus documentos, a un toque."))

# --- 2. Arquitectura --------------------------------------------------------
S.append(P("Cómo está armado", h1))
S.append(B("<b>SSH directo a tu PC</b>: la app entra al "
           "<font name='Mono' size='9'>sshd</font> del host como <b>tu usuario</b>, solo "
           "con clave. No hay contenedor de por medio ni nada corriendo como root: la "
           "terminal es tu shell de siempre, con tus proyectos y tu "
           "<font name='Mono' size='9'>claude</font>."))
S.append(B("<b>tmux</b> del lado del host: es lo que hace que las sesiones sobrevivan a que "
           "bloquees el celu, se corte la red o cierres la app."))
S.append(B("<b>Tailscale embebido</b>: la app trae su propio nodo (userspace, vía "
           "<font name='Mono' size='9'>tsnet</font>) y enruta su SSH y sus túneles por ahí. "
           "No necesitás instalar la app de Tailscale."))
S.append(B("<b>Contenedor de display</b>: una pantalla virtual aislada donde corre el "
           "navegador <i>headed</i> de la PC, que ves por <b>noVNC</b> en el celu."))

# --- 3. Setup ---------------------------------------------------------------
S.append(P("Puesta en marcha (en la PC)", h1))
S.append(P("Una sola vez, en el repo:"))
S.append(C("bash scripts/setup-host.sh   # sshd solo-clave, tmux y daemons\n"
           "cp .env.example .env         # completá TS_AUTHKEY\n"
           "docker compose up -d --build"))
S.append(P("Para vincular el celu por QR necesitás un <b>OAuth client</b> de Tailscale "
           "(no vence) cargado en el <font name='Mono' size='9'>.env</font> "
           "(<font name='Mono' size='9'>TS_OAUTH_CLIENT_ID/SECRET</font>, tag "
           "<font name='Mono' size='9'>tag:remotemarvin</font>). Ver "
           "<font name='Mono' size='9'>.env.example</font>."))

# --- 4. Vincular ------------------------------------------------------------
S.append(P("Conectar el celular (vincular por QR)", h1))
S.append(P("El nodo Tailscale de la app se enrola escaneando un QR — sin tocar la "
           "consola web ni tipear claves:"))
S.append(B("En la PC: <font name='Mono' size='9'>./scripts/ts-link-qr.sh</font> "
           "→ imprime un QR (clave de un solo uso, vence en 10 min)."))
S.append(B("En la app: tocá la <b>línea de estado de Tailscale</b> → <b>Escanear QR</b> → "
           "apuntá la cámara. El estado pasa a <b>conectada ✓</b>."))
S.append(B("Queda guardado: reconecta solo en cada arranque, no re-escaneás más."))

# --- 5. Terminal ------------------------------------------------------------
S.append(P("La terminal", h1))
S.append(B("<b>Sesiones persistentes</b> (tmux): si se corta la red o bloqueás el celu, "
           "al volver seguís donde estabas. La app reconecta sola."))
S.append(B("<b>Multi-pestaña</b>: varias sesiones a la vez, con “+”. Cada host recuerda "
           "sus pestañas, y con “⟳” reenganchás las que quedaron vivas."))
S.append(B("<b>Teclado extra</b>: Ctrl / Alt / Shift, flechas, Tab y ⇧Tab — las teclas "
           "que un teclado de celu no tiene. Ctrl, Alt y Shift quedan <i>pegados</i>: los "
           "tocás y modifican la tecla siguiente."))
S.append(B("<b>Portapapeles</b>: con “Sel” arrastrás el dedo para marcar texto y al soltar "
           "queda en el portapapeles del teléfono."))
S.append(P("Barra de la terminal", h2))
S.append(P("De izquierda a derecha:", small))
row = Table([
    [P("+", icon), "nueva pestaña"],
    [P("⟳", icon), "reenganchar sesiones tmux sueltas"],
    [P("🖥", icon), "abrir el visor del navegador (noVNC)"],
    [P("📄", icon), "abrir el visor de documentos"],
    [P("🔑", icon), "ver la clave pública de la app"],
], colWidths=[2.2 * cm, 10.2 * cm])
row.setStyle(TableStyle([
    ("FONTNAME", (1, 0), (1, -1), "Body"),
    ("FONTSIZE", (0, 0), (-1, -1), 10),
    ("TEXTCOLOR", (1, 0), (1, -1), INK),
    ("BACKGROUND", (0, 0), (-1, -1), SURFACE),
    ("ROWBACKGROUNDS", (0, 0), (-1, -1), [SURFACE, colors.white]),
    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
    ("TOPPADDING", (0, 0), (-1, -1), 5),
    ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ("LEFTPADDING", (0, 0), (-1, -1), 8),
]))
S.append(row)
S.append(Spacer(1, 6))

# --- 6. Dictado -------------------------------------------------------------
S.append(P("Dictado por voz", h1))
S.append(P("Mantené apretado <b>🎤 Dictar</b> y hablá: al soltar, el texto aparece en la "
           "terminal como si lo hubieras tipeado. <b>Sin Enter</b>, así lo revisás antes de "
           "mandarlo."))
S.append(B("Si la PC tiene GPU, vas viendo el texto <b>en vivo</b> mientras hablás."))
S.append(B("El audio se transcribe <b>en tu PC</b> (faster-whisper), no en un servicio de "
           "terceros, y viaja por tu propia conexión."))
S.append(B("El motor se apaga solo tras un rato sin uso para liberar la memoria de la "
           "placa; se enciende de nuevo al dictar."))

# --- 7. Hosts ---------------------------------------------------------------
S.append(P("Hosts y autorización", h1))
S.append(P("La pantalla de inicio lista tus PCs. “+ Agregar host” da de alta uno "
           "(nombre, host/IP, puerto, usuario). Mantené apretado para editar o borrar."))
S.append(P("Autorizar el teléfono", h2))
S.append(P("La app genera su propia clave SSH en el <b>Android Keystore</b> (la privada "
           "nunca sale del teléfono). Para habilitarla, tocá el ícono de la <b>llave</b>, "
           "copiá el texto y pegalo en el host:"))
S.append(C("~/.ssh/authorized_keys"))
S.append(P("La identidad del host", h2))
S.append(P("La primera vez que conectás, la app <b>memoriza la clave del host</b>. Si más "
           "adelante cambia, la conexión se <b>rechaza</b> y te muestra las dos huellas "
           "para que decidas: si reinstalaste el server es esperable, y si no, alguien "
           "puede estar interceptando. Solo la terminal pregunta — el visor, los documentos "
           "y el dictado fallan sin ofrecer confiar."))

# --- 8. Navegador -----------------------------------------------------------
S.append(P("Visor de navegador (noVNC)", h1))
S.append(P("El botón del <b>monitor</b> abre el navegador <i>headed</i> que corre en la PC, "
           "renderizado en vivo en el celu. Sirve para flujos que no pueden ser "
           "<i>headless</i> (anti-bot) o cuando querés <i>ver</i> el navegador. Tiene "
           "modos Ajustar/Escritorio y <b>zoom nítido</b> (pinch) sobre la imagen real."))
S.append(P("No está publicado en la red: viaja tunelizado por tu propia conexión SSH.",
           small))

# --- 9. Documentos ----------------------------------------------------------
S.append(P("Visor de documentos", h1))
S.append(P("El botón de la <b>hoja</b> muestra los documentos que se comparten desde la PC "
           "(<font name='Mono' size='9'>~/RemoteMarvinDocs</font>). Se comparten con:"))
S.append(C("marvin-share informe.pdf grafico.png datos.csv"))
S.append(P("que viene con el plugin de Claude. Funciona también desde un server remoto al "
           "que hayas entrado por SSH <i>desde la app</i>: el documento igual aterriza en la "
           "PC que estás mirando."))
S.append(P("El visor es <b>nativo</b>:"))
S.append(B("<b>Imágenes</b> (png/jpg/webp…): con pinch-zoom y arrastre."))
S.append(B("<b>PDF</b>: renderizado página por página."))
S.append(B("<b>Texto</b> (txt/csv/md/json/…): monospace, scrolleable."))
S.append(P("<i>Este mismo manual se generó y se compartió así.</i>", small))

# --- 10. Marca --------------------------------------------------------------
S.append(P("Identidad", h1))
S.append(P("RemoteMarvin viste la identidad de <b>Marvin Software Solutions</b>: paleta "
           "CTR (verde, petróleo, ámbar) inspirada en monitores CRT, tipografías de "
           "rotulación técnica y Mononoki, e isologo de corchetes y triángulos. El nombre "
           "guiña a Marvin, el androide de la Guía del Autoestopista Galáctico."))

doc.build(S)
print(OUT)
