"""Publishes the internet play guest page (app/src/main/assets/remote, SPEC v4) as the organization site of the
"track-record" GitHub organization, served by GitHub Pages at https://track-record.github.io/ (feedback round 28). Signs in through Git Credential Manager (Felix confirms in its window / the browser);
the token stays in memory and in GCM's own store, never in a file or the output.
Run from anywhere; publishes the files listed in FILES.
Usage: publish_pages.py <project-root> <work-dir>"""
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT, WORK = sys.argv[1], sys.argv[2]
OWNER = "track-record"
# An organization site: the repo named <owner>.github.io is served at the root of that address.
REPO = f"{OWNER}.github.io"
SITE = f"https://{REPO}/"
# (source, published path): the app's own guest page (assets/remote; SPEC v4). strings.json is made
# from the app's string resources.
GAME = "app/src/main/assets/remote"
FILES = [(f"{GAME}/{f}", f) for f in ("index.html", "app.js", "app.css", "scenes.js", "icon.svg", "http.js", "music.js", "p2p.js", "p2p-common.js",
                                     "vendor/trystero-0.26.0.mjs", "vendor/TRYSTERO-LICENSE", "fonts/roboto.woff2", "fonts/OFL.txt")]


def log(msg):
    print(msg, flush=True)


def credential():
    """Asks git for GitHub credentials; Git Credential Manager signs in interactively if needed."""
    r = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", capture_output=True, text=True, timeout=900)
    if r.returncode != 0:
        raise SystemExit(f"sign-in failed: {r.stderr.strip()[:300]}")
    fields = dict(line.split("=", 1) for line in r.stdout.splitlines() if "=" in line)
    return fields


def api(method, path, token, body=None):
    req = urllib.request.Request(f"https://api.github.com{path}", method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "TrackRecord-publish")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, json.loads(text) if text.startswith("{") else {"message": text[:200]}


def git(*args, cwd=None, env=None):
    r = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        raise SystemExit(f"git {' '.join(args[:2])} failed: {r.stderr.strip()[:400]}")
    return r.stdout


log("Signing in to GitHub (Git Credential Manager may open a window)…")
cred = credential()
token = cred.get("password", "")
status, user = api("GET", "/user", token)
if status != 200:
    raise SystemExit(f"GitHub didn't accept the sign-in ({status}): {user.get('message')}")
login, uid = user["login"], user["id"]
log(f"Signed in as {login}")
# Remember the sign-in the way git normally does, so the push below (and later ones) work.
subprocess.run(["git", "credential", "approve"], input="".join(f"{k}={v}\n" for k, v in cred.items()) + "\n", text=True, check=True)

status, repo = api("POST", f"/orgs/{OWNER}/repos", token, {
    "name": REPO,
    "description": "Track Record: the guest page for playing over the internet (static, served by GitHub Pages).",
    "private": False, "has_issues": False, "has_projects": False, "has_wiki": False, "auto_init": False,
})
if status == 201:
    log(f"Created {repo['html_url']}")
elif status == 422:
    status, repo = api("GET", f"/repos/{OWNER}/{REPO}", token)
    if status != 200:
        raise SystemExit(f"{REPO} exists but can't be read ({status})")
    log(f"Using the existing {repo['html_url']}")
else:
    raise SystemExit(f"Creating the repo failed ({status}): {repo.get('message')}")

# The site's files, in a fresh folder.
site = os.path.join(WORK, REPO)
shutil.rmtree(site, ignore_errors=True)
os.makedirs(os.path.join(site, "vendor"))
os.makedirs(os.path.join(site, "fonts"))
for source, target in FILES:
    shutil.copyfile(os.path.join(ROOT, source), os.path.join(site, target))
subprocess.run([sys.executable, os.path.join(ROOT, "tools", "web_strings.py"), ROOT, os.path.join(site, "strings.json")], check=True)
open(os.path.join(site, ".nojekyll"), "w").close()
with open(os.path.join(site, "README.md"), "w", encoding="utf-8", newline="\n") as fh:
    fh.write(f"""# Track Record — guest page

The page friends open to play [Track Record]({SITE}) over the internet,
from an invite link the host shares. It connects directly to the host's phone (WebRTC); this site only
serves the static files.

The invite link's part after `#` (room and secret) never reaches any server, GitHub included.
`vendor/trystero-0.26.0.mjs` is [Trystero](https://github.com/dmotz/trystero) (MIT, see
`vendor/TRYSTERO-LICENSE`).
`fonts/roboto.woff2` is a subset of [Roboto](https://github.com/googlefonts/roboto-3-classic) (SIL Open Font License,
see `fonts/OFL.txt`).
""")
noreply = f"{uid}+{login}@users.noreply.github.com"
git("init", "-q", "-b", "main", cwd=site)
git("add", "-A", cwd=site)
git("-c", f"user.name={login}", "-c", f"user.email={noreply}", "commit", "-q", "-m", "Guest page for internet play", cwd=site)
git("push", "-q", "--force", f"https://github.com/{OWNER}/{REPO}.git", "main", cwd=site)
log("Pushed the page")

status, pages = api("POST", f"/repos/{OWNER}/{REPO}/pages", token, {"source": {"branch": "main", "path": "/"}})
if status not in (201, 409):
    raise SystemExit(f"Turning on Pages failed ({status}): {pages.get('message')}")
url = f"{SITE}strings.json"
log("Pages is on; waiting for the first build…")
for _ in range(60):
    status, info = api("GET", f"/repos/{OWNER}/{REPO}/pages", token)
    if info.get("status") == "built":
        break
    time.sleep(5)
for _ in range(24):
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            if resp.status == 200:
                break
    except Exception:
        pass
    time.sleep(5)
log(f"PAGE {url}")
