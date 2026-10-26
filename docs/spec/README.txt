Sine & Cosine — specification bundle
====================================

SPEC.md           The specification. Single source of truth, 126 resolved
                  decisions. Edit this.

sine-cosine.html  The published page, generated from SPEC.md. Self-contained
                  except for the Google Fonts link (Instrument Sans, Martian Mono).
                  Note: this file has no <!doctype>/<html>/<head>/<body> —
                  the artifact host wraps it. To open it locally, paste it
                  inside a minimal HTML document, or use build.py's output
                  with your own wrapper.

Regenerating the page after editing SPEC.md:

    python3 build.py            # needs: pip install markdown

build.py          Markdown -> HTML. Turns `RESOLVED` / `OPEN` / `NOTE` etc.
                  into Phase tags, wraps tables for horizontal scroll,
                  converts warning paragraphs into callouts, and builds the
                  contents index from the headings.
page.css          Phase tokens and the page's own components.
page.tpl          Page shell: header, index rail, floating shelf, contents
                  sheet, scroll-progress plane.

Design language: Phase (SPEC.md §6). Sine's implementation is
sine/app/src/main/kotlin/app/sine/ui/phase; the dashboard's is
cosine/internal/dashboard/static/phase.css. The page deliberately uses the
system it documents.
