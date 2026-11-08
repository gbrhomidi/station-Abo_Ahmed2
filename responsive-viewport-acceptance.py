#!/usr/bin/env python3
"""Real Chromium responsive-layout acceptance at common compact Android CSS widths."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import unquote

PROJECT_ROOT = Path(__file__).resolve().parent
ASSETS = PROJECT_ROOT / "app" / "src" / "main" / "assets"
SCREENS = (
    ASSETS / "main.html",
    ASSETS / "screens" / "dashboard.html",
    ASSETS / "screens" / "customer-reports.html",
    ASSETS / "screens" / "customer-debts.html",
    ASSETS / "screens" / "debt-reminders.html",
    ASSETS / "screens" / "tasks.html",
    ASSETS / "screens" / "reports-reconciliation.html",
)
VIEWPORT_WIDTHS = (320, 360, 390, 412)
VIEWPORT_HEIGHT = 900


def find_browser() -> str:
    configured = os.environ.get("CHROME_BIN")
    candidates = [configured] if configured else []
    candidates.extend(("google-chrome-stable", "google-chrome", "chromium", "chromium-browser"))
    for candidate in candidates:
        if candidate and (Path(candidate).is_file() or shutil.which(candidate)):
            return str(Path(candidate).resolve()) if Path(candidate).is_file() else str(shutil.which(candidate))
    raise RuntimeError("Chromium/Chrome is required for real viewport acceptance; no browser executable was found.")


def prepare_html(source_path: Path, temporary_path: Path, width: int) -> None:
    html = source_path.read_text(encoding="utf-8", errors="replace")
    viewport_tags = re.findall(r"<meta\b[^>]*>", html, flags=re.I)
    viewport_tag = next((
        tag for tag in viewport_tags
        if re.search(r"""\bname\s*=\s*["']viewport["']""", tag, re.I)
        and re.search(r"""\bcontent\s*=\s*["'][^"']*\bwidth\s*=\s*device-width""", tag, re.I)
    ), None)
    if viewport_tag is None:
        raise RuntimeError(f"{source_path.name} does not declare a device-width viewport")
    # Headless desktop Chromium otherwise defaults to a 500 CSS-pixel layout viewport.
    # Change only the temporary test copy after asserting that the shipped screen uses
    # width=device-width, so media-query/CSS geometry is measured at the requested width.
    fixed_viewport = f'<meta name="viewport" content="width={width}, initial-scale=1.0, maximum-scale=1.0">'
    html = html.replace(viewport_tag, fixed_viewport, 1)

    # The acceptance is for CSS/DOM geometry, not Android bridge availability or network APIs.
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
            # Some legacy HTML refers to optional fonts/icons that are not shipped in
            # this branch. They do not determine layout geometry; retain the page's
            # declared fallback font instead of failing the entire viewport run.
            if local.suffix.lower() in (".css",):
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
  const result = {
    innerWidth: window.innerWidth,
    clientWidth: root.clientWidth,
    documentScrollWidth: root.scrollWidth,
    bodyScrollWidth: body.scrollWidth,
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


def measure(browser: str, html_path: Path, width: int) -> dict:
    command = [
        browser,
        "--headless",
        "--no-sandbox",
        "--disable-gpu",
        "--disable-dev-shm-usage",
        "--disable-extensions",
        "--hide-scrollbars",
        "--force-device-scale-factor=1",
        f"--window-size={width},{VIEWPORT_HEIGHT}",
        "--virtual-time-budget=1200",
        "--dump-dom",
        html_path.as_uri(),
    ]
    result = subprocess.run(
        command,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=35,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"Chromium exited {result.returncode} at {width}px for {html_path.name}: "
            f"{result.stderr[-1600:]}"
        )
    match = re.search(
        r'<pre[^>]*id=["\']__viewport_acceptance__["\'][^>]*>(.*?)</pre>',
        result.stdout,
        re.S | re.I,
    )
    if not match:
        raise RuntimeError(
            f"Chromium did not return viewport measurements for {html_path.name} at {width}px. "
            f"Output tail: {result.stdout[-1200:]}"
        )
    return json.loads(match.group(1))


def main() -> int:
    browser = find_browser()
    missing = [path for path in SCREENS if not path.is_file()]
    if missing:
        raise FileNotFoundError("Responsive acceptance screen(s) missing: " + ", ".join(str(p) for p in missing))

    failures = []
    total = 0
    with tempfile.TemporaryDirectory(prefix="station-responsive-") as directory:
        temp_root = Path(directory)
        for screen in SCREENS:
            for width in VIEWPORT_WIDTHS:
                total += 1
                prepared = temp_root / f"{screen.stem}-{width}px.html"
                prepare_html(screen, prepared, width)
                result = measure(browser, prepared, width)
                client = int(result.get("clientWidth", 0))
                document_width = int(result.get("documentScrollWidth", 0))
                body_width = int(result.get("bodyScrollWidth", 0))
                inner = int(result.get("innerWidth", 0))
                passed = (
                    inner == width
                    and client == width
                    and document_width <= width + 1
                    and body_width <= width + 1
                )
                label = "PASS" if passed else "FAIL"
                print(
                    f"{label} {screen.relative_to(ASSETS)} @ {width}px "
                    f"(inner={result.get('innerWidth')}, client={client}, document={document_width}, body={body_width})"
                )
                if not passed:
                    failures.append({
                        "screen": str(screen.relative_to(ASSETS)),
                        "viewport": width,
                        "measurement": result,
                    })
    print(f"RESPONSIVE VIEWPORT ACCEPTANCE: {total - len(failures)}/{total} passed; {len(failures)} failed.")
    if failures:
        for item in failures:
            print("OVERFLOW DETAILS " + json.dumps(item, ensure_ascii=False))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
