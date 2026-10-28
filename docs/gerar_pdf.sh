#!/usr/bin/env bash
# Gera docs/slides.pdf a partir de docs/slides.html usando o Chrome/Chromium em modo headless.
set -euo pipefail
cd "$(dirname "$0")"
NAV=$(command -v google-chrome || command -v chromium || command -v chromium-browser)
"$NAV" --headless=new --no-sandbox --disable-gpu --no-pdf-header-footer \
  --print-to-pdf=slides.pdf "file://$PWD/slides.html" 2>/dev/null
ls -l slides.pdf
