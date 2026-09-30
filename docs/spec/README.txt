Sine & Cosine — specification bundle
====================================

SPEC.md           The specification. Single source of truth, 126 resolved
                  decisions. Edit this.

sine-cosine.html  The published page, generated from SPEC.md. Self-contained
                  except for the Google Fonts link (Archivo, IBM Plex Mono).
                  Note: this file has no <!doctype>/<html>/<head>/<body> —
                  the artifact host wraps it. To open it locally, paste it
                  inside a minimal HTML document, or use build.py's output
                  with your own wrapper.

Regenerating the page after editing SPEC.md:

    python3 build.py            # needs: pip install markdown

build.py          Markdown -> HTML. Turns `RESOLVED` / `OPEN` / `NOTE` etc.
                  into Graphit planes, wraps tables for horizontal scroll,
                  converts warning paragraphs into callouts, and builds the
                  contents index from the headings.
page.css          Graphit v0.7 tokens and the page's own components.
page.tpl          Page shell: header, index rail, floating shelf, contents
                  sheet, scroll-progress plane.

Design language: Graphit v0.7 (graphit.css / Theme.kt / graphit.sh), supplied
separately. The page deliberately uses the system it documents.
