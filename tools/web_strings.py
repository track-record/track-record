"""Writes the guest page's texts as strings.json for the published page (SPEC v4; D-180), from the
app's texts (Compose Resources in the ui module, PLATFORMS.md phase 5) and the keys in WebStrings.kt (the same map
the app serves at /api/strings).
Usage: web_strings.py <project-root> <out.json>"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root, out = Path(sys.argv[1]), sys.argv[2]
values = root / "ui" / "src" / "commonMain" / "composeResources" / "values"

# Compose Resources' texts are as shown: no escapes or quotes to undo (tools/to_compose_resources.py).
strings, plurals = {}, {}
for f in sorted(values.glob("*.xml")):
    for el in ET.parse(f).getroot():
        name = el.get("name")
        if el.tag == "string":
            strings[name] = "".join(el.itertext())
        elif el.tag == "plurals":
            plurals[name] = {item.get("quantity"): "".join(item.itertext()) for item in el}

# Wherever the files are (androidApp now, ui once moved).
kt = next(root.glob("*/src/*/kotlin/com/trackrecord/app/remote/WebStrings.kt")).read_text(encoding="utf-8")
labels = next(root.glob("*/src/*/kotlin/com/trackrecord/app/ui/theme/ThemeLabels.kt")).read_text(encoding="utf-8")

result = {}
# Every mapping must match the key pattern; a key it skips would be missing on the published page.
mapped = len(re.findall(r'" to Res\.(?:string|plurals)\.', kt))
found = len(re.findall(r'"([a-z0-9_]+)" to Res\.(?:string|plurals)\.([a-z0-9_]+)', kt))
if found != mapped:
    sys.exit(f"WebStrings.kt has {mapped} mappings but only {found} match the key pattern [a-z0-9_]")
for key, name in re.findall(r'"([a-z0-9_]+)" to Res\.string\.([a-z0-9_]+)', kt):
    result[key] = strings[name]
for key, name in re.findall(r'"([a-z0-9_]+)" to Res\.plurals\.([a-z0-9_]+)', kt):
    result[f"{key}_one"] = plurals[name]["one"]
    result[f"{key}_other"] = plurals[name]["other"]
for theme, name in re.findall(r"ThemeChoice\.([A-Z_]+) -> Res\.string\.([a-z0-9_]+)", labels):
    result[f"theme_{theme}"] = strings[name]
if not result:
    sys.exit("no texts found")

Path(out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
print(len(result), "texts")
