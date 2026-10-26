"""Writes the guest page's texts as strings.json for the published page (SPEC v4; D-180), from the
app's string resources and the keys in WebStrings.kt (the same map the app serves at /api/strings).
Usage: web_strings.py <project-root> <out.json>"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root, out = Path(sys.argv[1]), sys.argv[2]
app = root / "app" / "src" / "main"


def android_text(raw):
    """Undoes Android's string escapes (the XML entities are already resolved)."""
    text = raw.strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), text)


strings, plurals = {}, {}
for f in sorted((app / "res" / "values").glob("*.xml")):
    for el in ET.parse(f).getroot():
        name = el.get("name")
        if el.tag == "string":
            strings[name] = android_text("".join(el.itertext()))
        elif el.tag == "plurals":
            plurals[name] = {item.get("quantity"): android_text("".join(item.itertext())) for item in el}

kt = (app / "kotlin" / "com" / "trackrecord" / "app" / "remote" / "WebStrings.kt").read_text(encoding="utf-8")
labels = (app / "kotlin" / "com" / "trackrecord" / "app" / "ui" / "theme" / "ThemeLabels.kt").read_text(encoding="utf-8")

result = {}
# Every mapping must match the key pattern; a key it skips would be missing on the published page.
mapped = len(re.findall(r'" to R\.(?:string|plurals)\.', kt))
found = len(re.findall(r'"([a-z0-9_]+)" to R\.(?:string|plurals)\.([a-z0-9_]+)', kt))
if found != mapped:
    sys.exit(f"WebStrings.kt has {mapped} mappings but only {found} match the key pattern [a-z0-9_]")
for key, name in re.findall(r'"([a-z0-9_]+)" to R\.string\.([a-z0-9_]+)', kt):
    result[key] = strings[name]
for key, name in re.findall(r'"([a-z0-9_]+)" to R\.plurals\.([a-z0-9_]+)', kt):
    result[f"{key}_one"] = plurals[name]["one"]
    result[f"{key}_other"] = plurals[name]["other"]
for theme, name in re.findall(r"ThemeChoice\.([A-Z_]+) -> R\.string\.([a-z0-9_]+)", labels):
    result[f"theme_{theme}"] = strings[name]

Path(out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
print(len(result), "texts")
