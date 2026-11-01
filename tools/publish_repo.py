"""Publishes the app's code to the public repo track-record/track-record, and the release APK and the Windows MSI as a
GitHub release (feedback round 47; PLATFORMS.md phase 8); both apps' update checks read that release.

The public history is its own: one commit per release on the local branch "public", holding HEAD's files minus
the development documents in PRIVATE, made under the GitHub account's noreply address. Signs in through Git
Credential Manager like publish_pages.py; the token stays in memory.

Build the release first, with a clean working tree: gradlew assembleRelease :desktopApp:packageMsi -Prelease.
Usage: publish_repo.py <project-root> [--no-release]"""
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

ROOT = os.path.abspath(sys.argv[1])
MAKE_RELEASE = "--no-release" not in sys.argv
OWNER, REPO = "track-record", "track-record"
BRANCH = "public"
# Development documents: design decisions, reviews, the spec and the instructions for the coding assistant.
PRIVATE = ["CLAUDE.md", "DECISIONS.md", "SPEC.md", "REVIEW.md", "REVIEW-2.md", "DESIGN-REVIEW.md", "INTERNET_PLAY.md", "PLATFORMS.md"]
APK = os.path.join(ROOT, "androidApp", "build", "outputs", "apk", "release", "androidApp-release.apk")
MSI_DIR = os.path.join(ROOT, "desktopApp", "build", "compose", "binaries", "main", "msi")
# What the release page says (it is also where the apps send people to update by hand).
NOTES = """**Android:** download the APK below and open it on your phone. Android asks once to allow installs from your browser or file manager.

**Windows:** download the MSI below and open it. It installs for you alone, without admin rights, and replaces an older version.
- Windows SmartScreen may say that it protected your PC from an unrecognised app: choose **More info**, then **Run anyway**.
- The first time guests join over Wi-Fi, Windows asks whether Track Record may use the network: allow it (private networks are enough).

Both apps tell you when a newer version is out."""


def log(msg):
    print(msg, flush=True)


def git(*args, env=None, input=None):
    r = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, env=env, input=input)
    if r.returncode != 0:
        raise SystemExit(f"git {' '.join(args[:2])} failed: {r.stderr.strip()[:400]}")
    return r.stdout.strip()


def api(method, url, token, body=None, data=None, content_type=None):
    if not url.startswith("https://"):
        url = f"https://api.github.com{url}"
    payload = data if data is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, method=method, data=payload)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "TrackRecord-publish")
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, json.loads(text) if text.startswith("{") else {"message": text[:200]}


if git("status", "--porcelain", "--untracked-files=no"):
    raise SystemExit("The working tree has changes: commit them first, so the published code is what the APK was built from.")
version = f"0.4.{git('rev-list', '--count', 'HEAD')}"
tag = f"v{version}"
MSI = os.path.join(MSI_DIR, f"TrackRecord-{version}.msi")
if MAKE_RELEASE:
    built = int(git("log", "-1", "--format=%ct"))
    if not os.path.isfile(APK):
        raise SystemExit(f"No release APK at {APK}: run gradlew assembleRelease.")
    if os.path.getmtime(APK) < built:
        raise SystemExit("The release APK is older than the last commit: run gradlew assembleRelease again.")
    if not os.path.isfile(MSI):
        raise SystemExit(f"No MSI for {version} at {MSI}: run gradlew :desktopApp:packageMsi -Prelease.")
    if os.path.getmtime(MSI) < built:
        raise SystemExit("The MSI is older than the last commit: run gradlew :desktopApp:packageMsi -Prelease again.")

log("Signing in to GitHub (Git Credential Manager may open a window)…")
r = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", capture_output=True, text=True, timeout=900)
if r.returncode != 0:
    raise SystemExit(f"sign-in failed: {r.stderr.strip()[:300]}")
cred = dict(line.split("=", 1) for line in r.stdout.splitlines() if "=" in line)
token = cred.get("password", "")
status, user = api("GET", "/user", token)
if status != 200:
    raise SystemExit(f"GitHub didn't accept the sign-in ({status}): {user.get('message')}")
login, uid = user["login"], user["id"]
log(f"Signed in as {login}")

# HEAD's files without the private documents, as a tree of its own (a separate index; the real one is untouched).
with tempfile.TemporaryDirectory() as tmp:
    env = dict(os.environ, GIT_INDEX_FILE=os.path.join(tmp, "index"))
    git("read-tree", "HEAD", env=env)
    git("rm", "--cached", "--quiet", "--ignore-unmatch", *PRIVATE, env=env)
    tree = git("write-tree", env=env)
for name in PRIVATE:
    if git("ls-tree", tree, "--", name):
        raise SystemExit(f"{name} would be published")

parent = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"refs/heads/{BRANCH}"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
if parent and git("rev-parse", f"{parent}^{{tree}}") == tree:
    log("The public branch already has this code.")
    commit = parent
else:
    noreply = f"{uid}+{login}@users.noreply.github.com"
    ident = dict(os.environ, GIT_AUTHOR_NAME=login, GIT_AUTHOR_EMAIL=noreply, GIT_COMMITTER_NAME=login, GIT_COMMITTER_EMAIL=noreply)
    commit = git("commit-tree", tree, *(["-p", parent] if parent else []), "-m", f"Track Record {version}", env=ident)
    git("update-ref", f"refs/heads/{BRANCH}", commit, *([parent] if parent else []))
    log(f"Public commit {commit[:10]} for {version}")

status, repo = api("GET", f"/repos/{OWNER}/{REPO}", token)
if status == 404:
    status, repo = api("POST", f"/orgs/{OWNER}/repos", token, {
        "name": REPO,
        "description": "Track Record: a music party game for Android and Windows. Guess when songs came out.",
        "homepage": f"https://github.com/{OWNER}/{REPO}/releases/latest",
        "private": False, "has_issues": True, "has_projects": False, "has_wiki": False, "auto_init": False,
    })
    if status != 201:
        raise SystemExit(f"Couldn't create the repo ({status}): {repo.get('message')}")
    log(f"Created {repo['html_url']}")
elif status != 200:
    raise SystemExit(f"Couldn't read the repo ({status}): {repo.get('message')}")

# The token goes to git through the credential helper, never on the command line.
git("push", "-q", f"https://github.com/{OWNER}/{REPO}.git", f"{commit}:refs/heads/main")
log(f"Pushed to https://github.com/{OWNER}/{REPO}")

if not MAKE_RELEASE:
    sys.exit(0)
status, existing = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{tag}", token)
if status == 200:
    raise SystemExit(f"Release {tag} exists already: {existing['html_url']}")
status, release = api("POST", f"/repos/{OWNER}/{REPO}/releases", token, {
    "tag_name": tag, "target_commitish": commit, "name": f"Track Record {version}",
    "body": NOTES,
    "draft": False, "prerelease": False,
})
if status != 201:
    raise SystemExit(f"Couldn't create the release ({status}): {release.get('message')}")
for path, name, kind in [
    (APK, f"TrackRecord-{version}.apk", "application/vnd.android.package-archive"),
    (MSI, f"TrackRecord-{version}.msi", "application/x-msi"),
]:
    with open(path, "rb") as f:
        data = f.read()
    log(f"Uploading {name} ({len(data) // 1_000_000} MB)…")
    upload = release["upload_url"].split("{")[0] + f"?name={name}"
    status, asset = api("POST", upload, token, data=data, content_type=kind)
    if status != 201:
        raise SystemExit(f"Couldn't upload {name} ({status}): {asset.get('message')}")
log(f"Released {tag}: {release['html_url']}")
