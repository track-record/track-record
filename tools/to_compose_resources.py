"""Moves the app's texts, icons and font from Android resources to Compose Resources in the ui module (PLATFORMS.md
phase 5, step 1) and rewrites the Kotlin that uses them. Run once; it stops if the Android files are gone.

- Texts: strings*.xml become ui/src/commonMain/composeResources/values/. Compose Resources only fill numbered
  placeholders ("%d" becomes "%1$d") and show Android's escapes as written, so \\' and \\" are unescaped and
  quoted texts lose their quotes. The launcher name (app_label) stays an Android resource.
- Every text's expected value, read as Android reads it, goes to ui/src/desktopTest/resources/expected-texts.json,
  which ComposeTextsTest compares with what Compose Resources give.
- Icons (not the launcher's) and Roboto move to composeResources/drawable and font.
- Kotlin in androidApp: R.string/plurals/drawable/font become Res.*, with the accessor imports; the resource
  functions come from org.jetbrains.compose.resources; texts outside composables go through Texts.
Usage: to_compose_resources.py <project-root>"""
import glob
import io
import json
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = sys.argv[1].rstrip("/") + "/"
APP_RES = ROOT + "androidApp/src/main/res/"
UI_RES = ROOT + "ui/src/commonMain/composeResources/"
PACKAGE = "com.trackrecord.app.resources"
KEEP = {"app_label"}
LAUNCHER = {"ic_launcher_foreground", "ic_launcher_monochrome"}


def android_text(raw):
    """A text as Android shows it: surrounding quotes removed, escapes resolved (as tools/web_strings.py reads it)."""
    text = raw.strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), text)


def numbered(text):
    n = [0]

    def number(m):
        n[0] += 1
        return "%" + str(n[0]) + "$" + m.group(1)
    return re.sub(r"%([ds])", number, text)


def xml_text(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def convert_strings():
    files = sorted(glob.glob(APP_RES + "values/strings*.xml"))
    if not files:
        sys.exit("no Android string files: already converted?")
    os.makedirs(UI_RES + "values", exist_ok=True)
    expected = {"strings": {}, "plurals": {}}
    kept = []
    for f in files:
        name = os.path.basename(f)
        source = io.open(f, encoding="utf-8").read()
        root = ET.fromstring(source.encode("utf-8"))
        # Line by line, so the comments stay: each element is written again from its parsed text.
        out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        lines = source.splitlines()
        i = 0
        elements = {el.get("name"): el for el in root}
        while i < len(lines):
            line = lines[i]
            m = re.match(r'\s*<(string|plurals) name="([^"]+)"', line)
            if m:
                tag, key = m.groups()
                el = elements[key]
                # Skip to the element's end.
                end = "</string>" if tag == "string" else "</plurals>"
                while end not in lines[i]:
                    i += 1
                i += 1
                if key in KEEP:
                    kept.append(ET.tostring(el, encoding="unicode").strip())
                    continue
                if tag == "string":
                    text = numbered(android_text("".join(el.itertext())))
                    expected["strings"][key] = text
                    out.append(f'    <string name="{key}">{xml_text(text)}</string>')
                else:
                    items = {item.get("quantity"): numbered(android_text("".join(item.itertext()))) for item in el}
                    expected["plurals"][key] = items
                    out.append(f'    <plurals name="{key}">')
                    for quantity, text in items.items():
                        out.append(f'        <item quantity="{quantity}">{xml_text(text)}</item>')
                    out.append("    </plurals>")
                continue
            stripped = line.strip()
            if stripped.startswith("<!--"):
                # A comment, possibly over several lines.
                while "-->" not in lines[i]:
                    out.append(lines[i])
                    i += 1
                out.append(lines[i])
            elif stripped == "":
                out.append("")
            i += 1
        out.append("</resources>")
        io.open(UI_RES + "values/" + name, "w", encoding="utf-8", newline="").write("\n".join(out) + "\n")
        os.remove(f)
    # The launcher's name stays with the Android app.
    io.open(APP_RES + "values/strings.xml", "w", encoding="utf-8", newline="").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <!-- The launcher\'s name; the texts are Compose Resources in the ui module'
        " (PLATFORMS.md phase 5). -->\n    " + "\n    ".join(kept) + "\n</resources>\n")
    test_res = ROOT + "ui/src/desktopTest/resources/"
    os.makedirs(test_res, exist_ok=True)
    io.open(test_res + "expected-texts.json", "w", encoding="utf-8", newline="").write(json.dumps(expected, ensure_ascii=False, indent=1, sort_keys=True) + "\n")
    return set(expected["strings"]) | set(expected["plurals"])


def move_assets():
    os.makedirs(UI_RES + "drawable", exist_ok=True)
    os.makedirs(UI_RES + "font", exist_ok=True)
    for f in glob.glob(APP_RES + "drawable/*.xml"):
        name = os.path.splitext(os.path.basename(f))[0]
        if name not in LAUNCHER:
            shutil.move(f, UI_RES + "drawable/" + os.path.basename(f))
    shutil.move(APP_RES + "font/roboto.ttf", UI_RES + "font/roboto.ttf")
    os.rmdir(APP_RES + "font")


RESOURCE_FUNCTIONS = ["stringResource", "pluralStringResource", "painterResource"]


def rewrite_kotlin(path):
    s = io.open(path, encoding="utf-8").read()
    original = s
    # Texts outside composables (Android's Resources/Context getters, Toasts) go through Texts.
    s = re.sub(r"\b[\w.]+\.getString\(R\.string\.", "Texts.get(R.string.", s)
    s = re.sub(r"\b[\w.]+\.getQuantityString\(R\.plurals\.", "Texts.plural(R.plurals.", s)
    s = re.sub(r"Toast\.makeText\(([\w@.]+), R\.string\.(\w+)", r"Toast.makeText(\1, Texts.get(R.string.\2)", s)
    names = set()

    def res(m):
        kind, name = m.groups()
        if kind == "string" and name in KEEP:
            return m.group(0)
        names.add(name)
        return f"Res.{kind}.{name}"
    s = re.sub(r"\bR\.(string|plurals|drawable|font)\.(\w+)", res, s)
    if s == original:
        return False
    imports = set(re.findall(r"^import .*$", s, re.M))
    add = {f"import {PACKAGE}.Res"} | {f"import {PACKAGE}.{n}" for n in names}
    for fn in RESOURCE_FUNCTIONS:
        s = s.replace(f"import androidx.compose.ui.res.{fn}\n", f"import org.jetbrains.compose.resources.{fn}\n")
    if "Texts." in s:
        add.add("import com.trackrecord.app.ui.Texts")
    if not re.search(r"\bR\.", s):
        s = s.replace("import com.trackrecord.app.R\n", "")
    new = sorted(add - imports)
    if new:
        # After the package line's imports; ktlint sorts them.
        first = re.search(r"^import .*$", s, re.M)
        if first:
            s = s[:first.start()] + "\n".join(new) + "\n" + s[first.start():]
        else:
            s = re.sub(r"^(package .*\n)", lambda m: m.group(1) + "\n" + "\n".join(new) + "\n", s, count=1, flags=re.M)
    io.open(path, "w", encoding="utf-8", newline="").write(s)
    return True


if __name__ == "__main__":
    keys = convert_strings()
    move_assets()
    changed = [p for p in glob.glob(ROOT + "androidApp/src/**/*.kt", recursive=True) if rewrite_kotlin(p)]
    print(f"{len(keys)} texts converted, {len(changed)} Kotlin files rewritten")
