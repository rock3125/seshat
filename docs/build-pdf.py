#!/usr/bin/env python3
"""
Render docs/seshat-architecture.html to PDF.

The HTML is an Artifact *fragment* — no doctype, html, head or body, because
the publisher supplies those. This wraps it into a standalone document, appends
a print layer, and drives headless Chromium.

    python3 build-pdf.py [--size A4|A3] [--out PATH]

Chromium rather than WeasyPrint or wkhtmltopdf: the page uses CSS grid, custom
properties and inline SVG, and only a current browser engine gets all three
right. The SVG stays vector, so every diagram label remains selectable and
searchable text in the PDF.
"""

import argparse
import pathlib
import shutil
import subprocess
import sys

# NOTE: this string is injected into a <style> element. It must not contain a
# closing style tag, and — learned the hard way — nothing here should be
# extracted by searching for tag names later; regenerate from this file instead.
PRINT_CSS = r"""
/* ══════════════════════════════════════════════════════════════════════
   Print layer. Appended AFTER the document's own stylesheet so that
   equal-specificity rules win on source order rather than needing
   !important.
   ══════════════════════════════════════════════════════════════════════ */

@page { size: __SIZE__ portrait; margin: 14mm 12mm 15mm; }

html, body { background: var(--ground); }

@media print {

  /* Without this Chrome drops every surface fill and the diagrams arrive as
     unfilled outlines on white. */
  *, *::before, *::after {
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }

  /* The figure containers scroll on screen. In print, overflow is clipped
     rather than scrolled, so a wide diagram would silently lose its right
     edge — unclip them and let each SVG scale to the text column instead.
     These selectors match the originals' specificity exactly (0,2,1), so
     source order decides. */
  .scroll { overflow: visible; }
  .scroll.w-md svg,
  .scroll.w-lg svg,
  .scroll.w-tall svg { min-width: 0; max-width: 100%; margin: 0; }

  /* The print column is narrower than the 880px breakpoint, which would
     collapse the register grid and drop the marginal rubric numerals into
     the flow. Restore the intended two-column layout at print's measure. */
  .register { grid-template-columns: 46px minmax(0, 1fr); gap: 0 20px; }
  .rnum { font-size: 1.75rem; padding-top: 3px; margin-bottom: 0; }
  .body, .notes { grid-column: 2; }
  .mast-top { flex-direction: row; gap: 22px; }

  /* Sticky is meaningless on paper and would pin the bar to page one. The
     anchors survive as real internal links in the PDF. */
  .index { position: static; }

  /* Keep a figure with its legend and caption; never split a table. */
  figure, .tbl, .legend, figcaption { break-inside: avoid; page-break-inside: avoid; }
  figure figcaption { break-before: avoid; }
  .notes dt, .notes dd { break-inside: avoid; }
  .notes dt { break-after: avoid; }

  /* Each level of magnification opens its own page; the masthead becomes a
     cover. A heading never sits alone at the foot of a page. */
  .register { break-before: page; page-break-before: always; }
  .rhead, .rhead h2, .body > h3 { break-after: avoid; page-break-after: avoid; }

  p { orphans: 3; widows: 3; }

  a { text-decoration: none; }

  .colophon { break-inside: avoid; }
}
"""

TEMPLATE = """<!doctype html>
<html lang="en" data-theme="light">
<head>
<meta charset="utf-8">
<title>Seshat — Architecture Diagram</title>
</head>
<body>
{fragment}
<style>{css}</style>
</body>
</html>
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="docs/seshat-architecture.html")
    ap.add_argument("--out", default="docs/seshat-architecture.pdf")
    ap.add_argument("--size", default="A4", choices=["A4", "A3"])
    ap.add_argument("--work", default=".")
    args = ap.parse_args()

    chromium = next(
        (p for p in ("chromium-browser", "chromium", "google-chrome-stable",
                     "google-chrome") if shutil.which(p)),
        None,
    )
    if chromium is None:
        print("no chromium/chrome on PATH", file=sys.stderr)
        return 1

    src = pathlib.Path(args.src).resolve()
    out = pathlib.Path(args.out).resolve()
    work = pathlib.Path(args.work).resolve() / f"print-{args.size.lower()}.html"

    fragment = src.read_text(encoding="utf-8")
    if "<!doctype" in fragment[:200].lower():
        print(f"{src} already looks like a full document; wrapping anyway",
              file=sys.stderr)

    work.write_text(
        TEMPLATE.format(fragment=fragment,
                        css=PRINT_CSS.replace("__SIZE__", args.size)),
        encoding="utf-8",
    )

    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        chromium, "--headless=new", "--disable-gpu", "--no-sandbox",
        "--no-first-run", "--hide-scrollbars", "--force-color-profile=srgb",
        "--run-all-compositor-stages-before-draw",
        "--virtual-time-budget=20000", "--no-pdf-header-footer",
        f"--print-to-pdf={out}", work.as_uri(),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if res.returncode != 0 or not out.exists():
        print(res.stdout + res.stderr, file=sys.stderr)
        return res.returncode or 1

    print(f"{out}  ({out.stat().st_size:,} bytes, {args.size})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
