#!/usr/bin/env python3
"""Chromium UI smoke test for the current Spider Native HTML shell.

Requires Python Playwright and a Chromium executable. This exercises the browser prototype
and local UI flow only; it does not prove Android WebView/PTY/device behavior.
"""
from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
HTML = ROOT / "app-web/ii-pauk.html"


def main() -> int:
    html = HTML.read_text(encoding="utf-8")
    errors: list[str] = []
    browser_binary = shutil.which("chromium") or shutil.which("chromium-browser") or shutil.which("google-chrome")
    with sync_playwright() as p:
        launch = {"headless": True, "args": ["--no-sandbox", "--disable-dev-shm-usage"]}
        if browser_binary:
            launch["executable_path"] = browser_binary
        browser = p.chromium.launch(**launch)
        try:
            page = browser.new_page(viewport={"width": 390, "height": 844})
            page.set_default_timeout(8000)
            page.on("pageerror", lambda exc: errors.append(str(exc)))
            page.set_content(html, wait_until="domcontentloaded")
            page.wait_for_timeout(250)
            page.evaluate("window.prompt = () => 'Renamed Lab'; window.confirm = () => true;")
            assert "Spider Suite v0.39.3" in page.title()
            assert page.locator('[name="ii-pauk-version"]').get_attribute("content") == "0.39.3"
            # The helper lives inside the app closure; test the exact source in an isolated function
            # instead of adding a public debug hook to production UI code.
            helper_match = re.search(r"function safeBrowserUrl\(input\)\{.*?\nfunction browserTitle", html, re.S)
            assert helper_match, "safeBrowserUrl source not found"
            helper_source = helper_match.group(0).rsplit("\nfunction browserTitle", 1)[0]
            url_policy = page.evaluate(f"""() => {{
                {helper_source}
                return {{
                    credentials: safeBrowserUrl('https://user:pass@example.com'),
                    dangerousScheme: safeBrowserUrl('javascript:alert(1)'),
                    controlCharacter: safeBrowserUrl('https://example.com' + String.fromCharCode(10) + 'header'),
                    query: safeBrowserUrl('weather in New York')
                }};
            }}""")
            assert url_policy["credentials"] is None
            assert url_policy["dangerousScheme"] is None
            assert url_policy["controlCharacter"] is None
            assert url_policy["query"].startswith("https://duckduckgo.com/?q=")

            # Runtime/workspace screens are explicitly metadata-only in this milestone.
            page.locator("#menuOpen").click()
            page.locator('[data-page="runtime"]').click()
            page.wait_for_selector("#runtimeWorkspaceForm", state="visible")
            assert "не являются папками linux" in page.locator("#appModal").inner_text().lower()

            def add_workspace(name: str) -> None:
                page.locator("#runtimeWorkspaceName").fill(name)
                page.locator('#runtimeWorkspaceForm button[type="submit"]').click()

            add_workspace("Python Lab")
            assert page.get_by_text("Python Lab", exact=True).count() == 1
            page.locator("#runtimeWorkspaceName").fill("python lab")
            page.locator('#runtimeWorkspaceForm button[type="submit"]').click()
            assert "уже существует" in page.locator("#appModal").inner_text().lower()

            add_workspace("Node Lab")
            rename_id = page.locator("[data-runtime-rename]").first.get_attribute("data-runtime-rename")
            assert rename_id
            page.locator(f'[data-runtime-rename="{rename_id}"]').click()
            assert page.get_by_text("Renamed Lab", exact=True).count() == 1
            page.locator("[data-runtime-switch]").first.click()
            assert page.get_by_text("АКТИВНО", exact=True).count() == 1
            before_delete = page.locator("[data-runtime-delete]").count()
            page.locator("[data-runtime-delete]").first.click()
            assert page.locator("[data-runtime-delete]").count() == before_delete - 1
            for i in range(2, 24):
                submit = page.locator('#runtimeWorkspaceForm button[type="submit"]')
                if submit.is_disabled():
                    break
                add_workspace(f"Lab {i}")
            assert page.locator('#runtimeWorkspaceForm button[type="submit"]').is_disabled()

            # HTML browser: address/query, tabs and bookmarks in the isolated prototype.
            page.locator("#runtimeClose").click()
            page.locator("#menuOpen").click()
            page.locator('[data-page="browser"]').click()
            page.wait_for_selector("#browserAddressForm", state="visible")
            page.locator("#browserAddress").fill("example.org")
            page.locator("#browserAddressForm button[type=submit]").click()
            page.wait_for_selector("#browserAddressForm", state="visible")
            assert page.locator("#browserAddress").input_value().startswith("https://example.org")
            page.locator("#browserBookmark").click()
            assert page.locator("[data-bookmark-url]").count() >= 1
            page.locator("#browserNewTab").click()
            assert page.locator("[data-browser-select]").count() >= 2
            page.locator("#browserDone").click()

            # HTML-only preview is transparent that native Media3 playback requires Android.
            page.locator("#menuOpen").click()
            page.locator('[data-page="media"]').click()
            page.wait_for_selector("#mediaClose", state="visible")
            media_text = page.locator("#appModal").inner_text().lower()
            assert "media3" in media_text and "html-предпросмотре" in media_text
            page.locator("#mediaClose").click()

            # The app console is not a shell and must retain that distinction.
            page.locator("#menuOpen").click()
            page.locator('[data-page="terminal"]').click()
            page.wait_for_selector("#terminalForm", state="attached")
            text = page.locator("#appModal").inner_text()
            assert "Открыт HTML-прототип" in text
            assert "настоящая pty встроена только в android-приложение" in text.lower()
            assert "не shell" in text.lower()
            assert "не являются системной оболочкой" not in text.lower()
            page.locator("#spiderConsoleDetails > summary").click()
            page.locator("#terminalInput").fill("help")
            page.locator("#terminalForm button[type=submit]").click()
            assert "Безопасные команды Spider Terminal" in page.locator("#terminalOutput").inner_text()
            page.locator("#terminalDone").click()

            # v0.38 code studio HTML preview, native-save wiring and explicit APK gate.
            page.locator("#menuOpen").click()
            page.locator('[data-page="code"]').click()
            page.wait_for_selector("#spiderCodeEditor", state="visible")
            page.locator("#spiderCodePreview").click()
            assert "Демонстрация запущена" in page.locator("#spiderCodeStatus").inner_text()
            assert page.frame_locator("#spiderCodePreviewFrame").locator("h1").inner_text() == "Моя HTML-демонстрация"
            with page.expect_download() as download_info:
                page.locator("#spiderCodeSave").click()
            html_download = download_info.value
            assert html_download.suggested_filename == "spider-demo.html"
            page.locator("#spiderCodeApk").click()
            assert "APK-сборка пока не подключена" in page.locator("#spiderCodeStatus").inner_text()
            page.locator("#spiderCodeClose").click()

            # Widget UI states the Android overlay permission requirement honestly.
            page.locator("#menuOpen").click()
            page.locator('[data-page="widget"]').click()
            assert "разрешение" in page.locator("#appModal").inner_text().lower()
            page.locator("#widgetClose").click()

            assert not errors, f"Uncaught page errors: {errors}"
            print(json.dumps({
                "page_load": "PASS",
                "runtime_create_duplicate_rename_switch_delete_and_limit": "PASS",
                "browser_address_bookmark_new_tab": "PASS",
                "media_preview_is_honest_about_native_requirement": "PASS",
                "terminal_honest_non_shell_console": "PASS",
                "code_studio_preview_and_html_download": "PASS",
                "apk_build_pending_state": "PASS",
                "widget_permission_ui": "PASS",
                "uncaught_page_errors": errors,
                "native_webview_pty_and_device_integration": "NOT TESTED (requires Android build/device)"
            }, ensure_ascii=False, indent=2))
            return 0
        finally:
            browser.close()


if __name__ == "__main__":
    raise SystemExit(main())
