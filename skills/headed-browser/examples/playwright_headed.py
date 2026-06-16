"""Ejemplo mínimo: Playwright HEADED dibujando en la pantalla virtual compartida.

Correr con el display seteado (el helper lo hace por vos):
    run-visible.sh uv run python playwright_headed.py
o a mano:
    DISPLAY=localhost:99 python playwright_headed.py

La clave es headless=False: DISPLAY solo da la pantalla; el browser tiene que
lanzarse en modo headed para que sea visible (y para evadir anti-bot tipo DataDome).
"""

from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=False)  # <- headed: visible en noVNC
    page = browser.new_page()
    page.goto("https://example.com")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(30_000)  # tiempo para mirarlo desde el celular
    browser.close()
