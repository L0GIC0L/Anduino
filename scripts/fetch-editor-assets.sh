#!/usr/bin/env bash
# Downloads the TextMate grammars, language configuration and colour themes the
# code editor uses into app/src/main/assets/textmate. All sources are MIT licensed
# (tm-grammars / tm-themes are packaged copies of the VS Code originals).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/textmate"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

npm_fetch() { # <package> -> extracts into $WORK/<package>
  local tarball
  tarball="$(curl -fsSL "https://registry.npmjs.org/$1/latest" | python3 -c 'import json,sys; print(json.load(sys.stdin)["dist"]["tarball"])')"
  mkdir -p "$WORK/$1"
  curl -fsSL "$tarball" | tar xz -C "$WORK/$1"
}

npm_fetch tm-grammars
npm_fetch tm-themes

cp "$WORK/tm-grammars/package/grammars/cpp.json" "$OUT/cpp.tmLanguage.json"
cp "$WORK/tm-grammars/package/grammars/cpp-macro.json" "$OUT/cpp-macro.tmLanguage.json"
cp "$WORK/tm-themes/package/themes/dark-plus.json" "$OUT/dark-plus.json"
cp "$WORK/tm-themes/package/themes/light-plus.json" "$OUT/light-plus.json"
# The editor's regex engine (joni) rejects a few look-behinds; rewrite them equivalently.
python3 "$ROOT/scripts/fix-grammar-for-joni.py" "$OUT/cpp.tmLanguage.json" "$OUT/cpp-macro.tmLanguage.json"
curl -fsSL "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/cpp/language-configuration.json" \
  -o "$OUT/cpp-language-configuration.json"

cat > "$OUT/languages.json" <<'EOF'
{
  "languages": [
    {
      "grammar": "textmate/cpp-macro.tmLanguage.json",
      "name": "cpp-macro",
      "scopeName": "source.cpp.embedded.macro"
    },
    {
      "grammar": "textmate/cpp.tmLanguage.json",
      "name": "cpp",
      "scopeName": "source.cpp",
      "languageConfiguration": "textmate/cpp-language-configuration.json"
    }
  ]
}
EOF
ls -la "$OUT"
