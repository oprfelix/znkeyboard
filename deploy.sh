#!/usr/bin/env bash
# Publishes the website to both places at once: GitHub Pages (push gh-pages) and
# znkeyboard.2amz.com (Cloudflare Worker with the same files). Commit first.
set -euo pipefail
cd "$(dirname "$0")"
if [ -n "$(git status --porcelain)" ]; then echo "Commit your changes first." >&2; exit 1; fi
git push origin gh-pages
(cd cloudflare && npx wrangler deploy)
echo "Deployed to https://oprfelix.github.io/znkeyboard/ and https://zn.2amz.com/"
