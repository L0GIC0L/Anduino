#!/usr/bin/env python3
"""Rewrites regexes in VS Code's C++ TextMate grammars that joni rejects.

The editor (sora-editor + Eclipse tm4e) runs grammars on joni, a Java port of an
older Oniguruma. joni refuses look-behinds whose alternatives have different
lengths inside a nested group, e.g. ``(?<=(?:^|[^\\\\])\\n)``, and tm4e then
silently replaces the whole pattern with ``^$``. For "end" patterns that means a
rule never ends: one ``// comment`` turned every following line into a comment.

Each rewrite below is semantically equivalent to the original. Usage:
    fix-grammar-for-joni.py <grammar.json> [...]   (files are rewritten in place)
"""
import json
import re
import sys

# Literal (pattern-text) replacements.
LITERAL = [
    # "end of line, unless continued with a backslash"
    (r"(?<=(?:^|[^\\])\n)", r"(?<=\n)(?<!\\\n)"),
    # member access after ".", ".*", "->" or "->*"
    (r"(?<=\.\*?|->\*??)", r"(?<=\.|\.\*|->|->\*)"),
    (r"(?<=(?:\W|^)return)", r"(?<=\breturn)"),
    (r"(?<=(?:\W|^)for ?\()", r"(?<=\bfor\(|\bfor \()"),
    (r"(?<=\S|^)", r"(?:(?<=\S)|^)"),
    (r"(?<=>|\*/)", r"(?:(?<=>)|(?<=\*/))"),
    # Oniguruma reads the reversed interval {1,0} as a possessive {0,1}
    (r"\s+{1,0}", r"\s*+"),
]

# (?<!(?:\W|^)(?:a|b|c)) -> (?<!\ba|\bb|\bc): every alternative starts with a word
# character, so "preceded by a non-word char or the line start" is exactly \b.
NOT_AFTER_WORDS = re.compile(r"\(\?<!\(\?:\\W\|\^\)\(\?:([A-Za-z0-9_|]+)\)\)")


def fix_pattern(p: str) -> str:
    for old, new in LITERAL:
        p = p.replace(old, new)
    return NOT_AFTER_WORDS.sub(lambda m: "(?<!" + "|".join("\\b" + w for w in m.group(1).split("|")) + ")", p)


def walk(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("match", "begin", "end", "while") and isinstance(v, str):
                node[k] = fix_pattern(v)
            else:
                walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)


for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as f:
        grammar = json.load(f)
    walk(grammar)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(grammar, f, ensure_ascii=False, separators=(",", ":"))
    print(f"patched {path}")
