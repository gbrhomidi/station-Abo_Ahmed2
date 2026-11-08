#!/usr/bin/env python3
"""Real Chromium mobile-emulation viewport acceptance for debt and reporting screens."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from urllib.parse import unquote, urlsplit
from urllib.request import urlopen

PROJECT_ROOT = Path(__file__).resolve().parent
ASSETS = PROJECT_ROOT / "app" / "src" / "main" / "assets"
SCREENS = (
    ASSETS / "main.html",
    ASSETS / "screens" / "dashboard.html",
    ASSETS / "screens" / "customer-reports.html",
    ASSETS / "screens" / "customer-debts.html",
    ASSETS / "screens" / "fuel-sales.html",
    ASSETS / "screens" / "pos.html",
    ASSETS / "screens" / "debt-reminders.html",
    ASSETS / "screens" / "tasks.html",
    ASSETS / "screens" / "reports-reconciliation.html",
)
VIEWPORT_WIDTHS = (320, 360, 390, 412, 768, 1024)
VIEWPORT_HEIGHT = 900


def find_browser() -> str:
    configured = os.environ.get("CHROME_BIN")
    candidates = [configured] if configured else []
    candidates.extend(("google-chrome-stable", "google-chrome", "chromium", "chromium-browser"))
    for candidate in candidates:
        if candidate and (Path(candidate).is_file() or shutil.which(candidate)):
            return str(Path(candidate).resolve()) if Path(candidate).is_file() else str(shutil.which(candidate))
    raise RuntimeError("Chromium/Chrome is required for real viewport acceptance; no browser executable was found.")


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.bind(("127.0.0.1", 0))
        return int(server.getsockname()[1])


def prepare_html(source_path: Path, temporary_path: Path) -> None:
    html = source_path.read_text(encoding="utf-8", errors="replace")
    viewport_tags = re.findall(r"<meta\b[^>]*>", html, flags=re.I)
    viewport_tag = next((
        tag for tag in viewport_tags
        if re.search(r"""\bname\s*=\s*["']viewport["']""", tag, re.I)
        and re.search(r"""\bcontent\s*=\s*["'][^"']*\bwidth\s*=\s*device-width""", tag, re.I)
    ), None)
    if viewport_tag is None:
        raise RuntimeError(f"{source_path.name} does not declare a device-width viewport")

    # This test measures DOM/CSS layout only. Remove bridge/network scripts and remote styles.
    html = re.sub(r"(?is)<script\b[^>]*>.*?</script\s*>", "", html)
    html = re.sub(
        r"(?is)<link\b[^>]*href=[\"']https?://[^\"']+[\"'][^>]*>",
        "",
        html,
    )

    def local_asset(match: re.Match[str]) -> str:
        relative = unquote(match.group(1).split("#", 1)[0].split("?", 1)[0])
        local = (ASSETS / relative).resolve()
        if not local.is_file():
            # Absent legacy font/icon files do not affect layout measurement; CSS is mandatory.
            if local.suffix.lower() == ".css":
                raise FileNotFoundError(f"Referenced Android stylesheet is missing: {relative} (from {source_path.name})")
            return "about:blank"
        return local.as_uri()

    html = re.sub(r"file:///android_asset/([A-Za-z0-9_./-]+)", local_asset, html)

    marker = r"""
<script>
(() => {
  const root = document.documentElement;
  const body = document.body;
  const width = root.clientWidth || window.innerWidth;
  const pageName = location.pathname.toLowerCase();
  const isFuelSalesPage = pageName.includes('fuel-sales-viewport');
  const isPosPage = pageName.includes('pos-viewport');

  // The production fuel-sales form is assembled dynamically by JavaScript. Its native
  // script is intentionally removed in this CSS-only acceptance harness, so recreate
  // the exact form-group/select/date structure and expose the real modal container.
  if (isFuelSalesPage) {
    const form = document.getElementById('recordForm');
    const modal = document.getElementById('formModal');
    if (form && modal) {
      form.innerHTML =
        '<div class="form-group payment-terms-field" data-payment-terms-acceptance="true">' +
          '<label class="form-label">شروط الدفع</label>' +
          '<select class="form-input" id="paymentTermsAcceptanceSelect"><option>بعد 7 أيام</option></select>' +
        '</div>' +
        '<div class="form-group payment-terms-field" data-payment-terms-acceptance="true">' +
          '<label class="form-label">تاريخ الاستحقاق المتوقع</label>' +
          '<input class="form-input readonly" id="paymentTermsDueDatePreview" type="date" value="2026-11-15" readonly>' +
        '</div>';
      modal.classList.add('show');
    }
  }

  // POS stores these paired controls in the customer-search modal used by credit sales.
  if (isPosPage) {
    const modal = document.getElementById('customerSearchModal');
    const row = document.getElementById('paymentTermsRow');
    if (modal && row) {
      modal.classList.add('show');
      row.style.display = '';
    }
  }

  const offenders = [];
  for (const element of Array.from(body.querySelectorAll('*'))) {
    const style = getComputedStyle(element);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) continue;
    const rect = element.getBoundingClientRect();
    if (rect.width > 0 && (rect.left < -1 || rect.right > width + 1)) {
      let scrollableAncestor = false;
      for (let parent = element.parentElement; parent && parent !== body; parent = parent.parentElement) {
        const overflowX = getComputedStyle(parent).overflowX;
        if (overflowX === 'auto' || overflowX === 'scroll' || overflowX === 'hidden') {
          scrollableAncestor = true;
          break;
        }
      }
      if (!scrollableAncestor && offenders.length < 12) {
        offenders.push({
          tag: element.tagName,
          id: element.id || '',
          className: typeof element.className === 'string' ? element.className.slice(0, 80) : '',
          left: Math.round(rect.left * 10) / 10,
          right: Math.round(rect.right * 10) / 10,
          width: Math.round(rect.width * 10) / 10
        });
      }
    }
  }
  const paymentTermsIds = isFuelSalesPage
    ? ['paymentTermsAcceptanceSelect', 'paymentTermsDueDatePreview']
    : isPosPage ? ['paymentTermsId', 'paymentTermsDueDate'] : [];
  const paymentTermsRects = paymentTermsIds.map(id => {
    const field = document.getElementById(id);
    if (!field) return { id, visible: false, missing: true };
    const style = getComputedStyle(field);
    const group = field.closest('.form-group, .field-group') || field;
    const rect = group.getBoundingClientRect();
    return {
      id,
      visible: style.display !== 'none' && style.visibility !== 'hidden' &&
        rect.width > 0 && rect.height > 0,
      left: Math.round(rect.left * 10) / 10,
      right: Math.round(rect.right * 10) / 10,
      top: Math.round(rect.top * 10) / 10,
      bottom: Math.round(rect.bottom * 10) / 10
    };
  });
  let paymentTermsOverlap = false;
  if (paymentTermsRects.length === 2 && paymentTermsRects.every(item => item.visible)) {
    const [first, second] = paymentTermsRects;
    paymentTermsOverlap = first.left < second.right - 1 && first.right > second.left + 1 &&
      first.top < second.bottom - 1 && first.bottom > second.top + 1;
  }
  const paymentTermsOutside = paymentTermsRects.some(item =>
    item.visible && (item.left < -1 || item.right > width + 1)
  );
  const paymentTermsPassed = paymentTermsIds.length === 0 ||
    (paymentTermsRects.length === 2 &&
      paymentTermsRects.every(item => item.visible && !item.missing) &&
      !paymentTermsOverlap && !paymentTermsOutside);
  const result = {
    innerWidth: window.innerWidth,
    clientWidth: root.clientWidth,
    documentScrollWidth: root.scrollWidth,
    bodyScrollWidth: body.scrollWidth,
    paymentTermsRects,
    paymentTermsOverlap,
    paymentTermsOutside,
    paymentTermsPassed,
    offenders
  };
  const output = document.createElement('pre');
  output.id = '__viewport_acceptance__';
  output.textContent = JSON.stringify(result);
  body.appendChild(output);
})();
</script>
"""
    if re.search(r"</body\s*>", html, re.I):
        html = re.sub(r"</body\s*>", marker + "</body>", html, count=1, flags=re.I)
    else:
        html += marker
    temporary_path.write_text(html, encoding="utf-8")


class DevToolsWebSocket:
    """Minimal WebSocket transport for the Chrome DevTools Protocol (stdlib only)."""

    def __init__(self, url: str) -> None:
        parsed = urlsplit(url)
        if parsed.scheme != "ws" or not parsed.hostname or not parsed.port:
            raise RuntimeError(f"Unexpected DevTools WebSocket URL: {url}")
        self.socket = socket.create_connection((parsed.hostname, parsed.port), timeout=10)
        self.socket.settimeout(10)
        self.buffer = b""
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        path = parsed.path + (("?" + parsed.query) if parsed.query else "")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {parsed.netloc}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Origin: http://localhost\r\n\r\n"
        )
        self.socket.sendall(request.encode("ascii"))
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.socket.recv(4096)
            if not chunk:
                raise RuntimeError("DevTools WebSocket closed during handshake")
            response += chunk
        header, self.buffer = response.split(b"\r\n\r\n", 1)
        expected = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
        )
        if b" 101 " not in header.split(b"\r\n", 1)[0] or expected not in header:
            raise RuntimeError("Chrome rejected the DevTools WebSocket handshake")

    def _read_exact(self, length: int) -> bytes:
        while len(self.buffer) < length:
            chunk = self.socket.recv(max(4096, length - len(self.buffer)))
            if not chunk:
                raise RuntimeError("DevTools WebSocket closed unexpectedly")
            self.buffer += chunk
        data, self.buffer = self.buffer[:length], self.buffer[length:]
        return data

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        mask = os.urandom(4)
        length = len(payload)
        header = bytearray([0x80 | opcode])
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header.extend(length.to_bytes(2, "big"))
        else:
            header.append(0x80 | 127)
            header.extend(length.to_bytes(8, "big"))
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        self.socket.sendall(bytes(header) + mask + masked)

    def _receive_text(self) -> str:
        chunks = []
        while True:
            first, second = self._read_exact(2)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = int.from_bytes(self._read_exact(2), "big")
            elif length == 127:
                length = int.from_bytes(self._read_exact(8), "big")
            masked = bool(second & 0x80)
            mask = self._read_exact(4) if masked else b""
            payload = self._read_exact(length)
            if masked:
                payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
            if opcode == 0x8:
                raise RuntimeError("Chrome closed the DevTools WebSocket")
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode in (0x1, 0x0):
                chunks.append(payload)
                if first & 0x80:
                    return b"".join(chunks).decode("utf-8")
            elif opcode == 0xA:
                continue

    def call(self, method: str, params: dict | None = None, timeout: float = 10.0) -> dict:
        self.next_id = getattr(self, "next_id", 0) + 1
        request_id = self.next_id
        message = json.dumps({"id": request_id, "method": method, "params": params or {}}).encode("utf-8")
        self._send_frame(0x1, message)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            message = json.loads(self._receive_text())
            if message.get("id") != request_id:
                continue
            if "error" in message:
                raise RuntimeError(f"DevTools {method} failed: {message['error']}")
            return message.get("result", {})
        raise TimeoutError(f"Timed out waiting for DevTools command: {method}")

    def close(self) -> None:
        try:
            self._send_frame(0x8, b"")
        except Exception:
            pass
        try:
            self.socket.close()
        except Exception:
            pass


def wait_for_page_target(port: int, process: subprocess.Popen) -> dict:
    endpoint = f"http://127.0.0.1:{port}/json/list"
    deadline = time.monotonic() + 25
    last_error = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Chromium exited during startup with code {process.returncode}")
        try:
            with urlopen(endpoint, timeout=2) as response:
                targets = json.loads(response.read().decode("utf-8"))
            page = next((target for target in targets if target.get("type") == "page" and target.get("webSocketDebuggerUrl")), None)
            if page:
                return page
        except Exception as error:
            last_error = error
        time.sleep(0.2)
    raise TimeoutError(f"Chrome DevTools endpoint did not become ready: {last_error}")


def evaluate_measurement(devtools: DevToolsWebSocket, width: int) -> dict:
    expression = "document.getElementById('__viewport_acceptance__')?.textContent || ''"
    deadline = time.monotonic() + 10
    last_value = ""
    while time.monotonic() < deadline:
        response = devtools.call("Runtime.evaluate", {
            "expression": expression,
            "returnByValue": True,
            "awaitPromise": True,
        })
        last_value = response.get("result", {}).get("value", "")
        if last_value:
            result = json.loads(last_value)
            # clientWidth is the CSS layout viewport used for media-query/layout geometry.
            # Chromium's mobile visual viewport may report a few extra CSS pixels in innerWidth.
            if int(result.get("clientWidth", 0)) != width:
                raise RuntimeError(
                    f"Device emulation requested {width}px but CSS layout viewport "
                    f"is {result.get('clientWidth')}px (visual innerWidth={result.get('innerWidth')})"
                )
            return result
        time.sleep(0.1)
    raise TimeoutError(f"Viewport marker did not render at {width}px: {last_value!r}")


def main() -> int:
    browser = find_browser()
    missing = [path for path in SCREENS if not path.is_file()]
    if missing:
        raise FileNotFoundError("Responsive acceptance screen(s) missing: " + ", ".join(str(path) for path in missing))

    failures = []
    total = 0
    with tempfile.TemporaryDirectory(prefix="station-responsive-") as directory:
        temp_root = Path(directory)
        port = find_free_port()
        profile = temp_root / "chrome-profile"
        process = subprocess.Popen(
            [
                browser,
                "--headless",
                "--no-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--disable-extensions",
                "--disable-background-networking",
                "--no-first-run",
                "--no-default-browser-check",
                "--allow-file-access-from-files",
                "--remote-allow-origins=*",
                f"--remote-debugging-port={port}",
                f"--user-data-dir={profile}",
                "about:blank",
            ],
            cwd=PROJECT_ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        devtools = None
        try:
            target = wait_for_page_target(port, process)
            devtools = DevToolsWebSocket(target["webSocketDebuggerUrl"])
            devtools.call("Page.enable")
            devtools.call("Runtime.enable")
            for screen in SCREENS:
                prepared = temp_root / f"{screen.stem}-viewport.html"
                prepare_html(screen, prepared)
                for width in VIEWPORT_WIDTHS:
                    total += 1
                    devtools.call("Emulation.setDeviceMetricsOverride", {
                        "width": width,
                        "height": VIEWPORT_HEIGHT,
                        "deviceScaleFactor": 1,
                        "mobile": True,
                    })
                    devtools.call("Page.navigate", {"url": prepared.as_uri()})
                    result = evaluate_measurement(devtools, width)
                    client = int(result.get("clientWidth", 0))
                    document_width = int(result.get("documentScrollWidth", 0))
                    body_width = int(result.get("bodyScrollWidth", 0))
                    visual_width = int(result.get("innerWidth", width))
                    passed = (
                        document_width <= visual_width + 1
                        and body_width <= width + 1
                        and not result.get("offenders")
                        and result.get("paymentTermsPassed", True)
                    )
                    label = "PASS" if passed else "FAIL"
                    print(
                        f"{label} {screen.relative_to(ASSETS)} @ {width}px "
                        f"(visual={visual_width}, layout={client}, "
                        f"document={document_width}, body={body_width}, "
                        f"term_fields={len(result.get('paymentTermsRects', []))}, "
                        f"term_overlap={result.get('paymentTermsOverlap', False)}, "
                        f"offenders={len(result.get('offenders', []))})"
                    )
                    if not passed:
                        failures.append({
                            "screen": str(screen.relative_to(ASSETS)),
                            "viewport": width,
                            "measurement": result,
                        })
        finally:
            if devtools:
                devtools.close()
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)

    print(f"RESPONSIVE VIEWPORT ACCEPTANCE: {total - len(failures)}/{total} passed; {len(failures)} failed.")
    if failures:
        for item in failures:
            print("OVERFLOW DETAILS " + json.dumps(item, ensure_ascii=False))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
