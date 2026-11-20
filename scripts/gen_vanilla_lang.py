
# -*- coding: utf-8 -*-
"""Generates the vanilla language tables from the BBS L10n tables.

Server-sent Text.translatable messages are resolved by Minecraft's own Language,
which only reads assets/<ns>/lang/*.json. The addon's strings live in
assets/bbs_together/assets/strings/*.json (BBS L10n), so those messages would show
raw keys unless a vanilla copy exists. This script writes that copy for every
language file next to its BBS source.
"""
import io, json, os

NS = os.path.join("src", "main", "resources", "assets", "bbs_together")
SRC = os.path.join(NS, "assets", "strings")
DST = os.path.join(NS, "lang")

os.makedirs(DST, exist_ok=True)
written = 0
for name in sorted(os.listdir(SRC)):
    if not name.endswith(".json"):
        continue
    with io.open(os.path.join(SRC, name), encoding="utf-8") as f:
        data = json.load(f)
    # A flat key -> string map is all the vanilla table supports.
    flat = {k: v for k, v in data.items() if isinstance(v, str)}
    with io.open(os.path.join(DST, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(flat, f, ensure_ascii=False, indent=4, sort_keys=True)
        f.write("\n")
    written += 1
print("wrote", written, "vanilla language files to", DST)
