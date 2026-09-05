"""Audit the staged Git snapshot (also works after checkout in CI); never print matched secrets."""
import re
import subprocess
import sys
from pathlib import PurePosixPath

ROOT_FILES = {".gitignore", ".gitattributes", "README.md", "LICENSE", "NOTICE", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md"}
PUBLIC_DOCS = {"PRIVACY.md", "RELEASING.md", "ROADMAP.md", "THIRD_PARTY_NOTICES.md"}
TOOLS = {"verify_public_tree.py", "publish-alpha.ps1"}
PATTERNS = [
    ("private key", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("GitHub token", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b")),
    ("AWS access key", re.compile(rb"\bAKIA[A-Z0-9]{16}\b")),
    ("local home path", re.compile(rb"[A-Za-z]:[\\/]Users[\\/]|/Users/[a-zA-Z0-9_-]+/|/home/[a-zA-Z0-9_-]+/")),
]


def git(*args):
    return subprocess.check_output(["git", *args])


def allowed(name):
    path = PurePosixPath(name)
    if name in ROOT_FILES:
        return True
    if name.startswith(".github/"):
        return path.suffix in {".yml", ".yaml", ".md"} or name == ".github/CODEOWNERS"
    if name.startswith("docs/"):
        return len(path.parts) == 2 and path.name in PUBLIC_DOCS
    if name.startswith("tools/"):
        return len(path.parts) == 2 and path.name in TOOLS
    if not name.startswith("android-app/"):
        return False
    if any(part in {"build", ".gradle", ".kotlin", ".idea", "node_modules"} for part in path.parts):
        return False
    if path.name in {"local.properties", "signing.properties", "secrets.properties"}:
        return False
    return (path.suffix in {".kt", ".kts", ".toml", ".properties", ".xml", ".json", ".pro", ".md", ".txt", ".bat"}
            or path.name in {"gradlew", ".gitignore"}
            or name == "android-app/gradle/wrapper/gradle-wrapper.jar")


def main():
    names = [p.decode("utf-8") for p in git("ls-files", "-z").split(b"\0") if p]
    failures = []
    for name in names:
        if not allowed(name):
            failures.append(f"{name}: not in public source allowlist")
            continue
        mode = git("ls-files", "--stage", "--", name).split(b" ", 1)[0]
        if mode == b"120000":
            failures.append(f"{name}: symlinks are not published")
            continue
        data = git("show", ":" + name)
        if len(data) > 2_000_000:
            failures.append(f"{name}: unexpectedly large source file")
        if name.endswith(".jar"):
            continue
        for label, pattern in PATTERNS:
            if pattern.search(data):
                failures.append(f"{name}: potential {label} (value withheld)")
    if failures:
        print("Public-tree audit FAILED:\n" + "\n".join(failures))
        return 1
    print(f"Public-tree audit passed for {len(names)} staged/tracked files. Review synthetic fixtures manually too.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
