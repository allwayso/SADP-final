"""Build a tested, fixed-commit offline release. Requires Python, Node and Java 17+."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
from datetime import datetime, timezone
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]


def run(args, cwd=ROOT):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "release")
    args = parser.parse_args()
    if git("status", "--porcelain", "--untracked-files=no"):
        raise SystemExit("Commit tracked changes before packaging a fixed revision.")
    source_paths = ["backend/src", "frontend/src", "miniapp", "scripts", "docs", ".env.example"]
    if git("ls-files", "--others", "--exclude-standard", "--", *source_paths):
        raise SystemExit("Untracked source files must be committed before packaging.")
    revision = git("rev-parse", "HEAD")
    output_root = args.output.resolve()
    output = output_root / revision
    output.mkdir(parents=True, exist_ok=False)
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    if not npm:
        raise SystemExit("npm is required.")
    run([npm, "ci", "--no-audit", "--no-fund"], ROOT / "frontend")
    run([npm, "run", "build"], ROOT / "frontend")
    run([npm, "run", "test:location"], ROOT / "frontend")
    run(["node", "--test", "miniapp/tests/session.test.cjs"])
    run(["python", "-m", "unittest", "discover", "-s", "scripts/tests", "-v"])
    static = ROOT / "backend/src/main/resources/static"
    # This generated directory is ignored by Git and must stay inside this checkout.
    assert static.resolve().is_relative_to(ROOT.resolve()) and static.name == "static"
    if static.exists():
        shutil.rmtree(static)
    shutil.copytree(ROOT / "frontend/dist", static)
    wrapper = ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    command = [wrapper, "-o", "package"] if os.name == "nt" else ["bash", wrapper, "-o", "package"]
    run(command, ROOT / "backend")
    jar = ROOT / "backend/target/check-collect-0.1.0.jar"
    with ZipFile(jar) as archive:
        assert archive.testzip() is None
        for file in (ROOT / "frontend/dist").rglob("*"):
            if file.is_file():
                assert archive.read("BOOT-INF/classes/static/" + file.relative_to(ROOT / "frontend/dist").as_posix()) == file.read_bytes()
        compiled = archive.read("BOOT-INF/classes/edu/sadp/checkcollect/identity/MiniWechatPairingController.class")
        assert int.from_bytes(compiled[6:8], "big") == 61, "JAR must target Java 17"
    shutil.copy2(jar, output / "app.jar")
    with ZipFile(output / "miniapp.zip", "w", ZIP_DEFLATED) as archive:
        for file in (ROOT / "miniapp").rglob("*"):
            relative = file.relative_to(ROOT / "miniapp")
            if file.is_file() and relative.parts[0] not in {"tests", "node_modules"} and file.name != "project.private.config.json":
                archive.write(file, relative.as_posix())
    for name in ["deploy-wechat-release.sh", "rollback-wechat-release.sh", "rewrite-app-image.py", "Dockerfile.wechat-runtime"]:
        shutil.copy2(ROOT / "scripts" / name, output / name)
    run(["git", "bundle", "create", output / "source.bundle", "HEAD"])
    manifest = {
        "revision": revision, "javaRelease": 17,
        "builtAt": datetime.now(timezone.utc).isoformat(),
        "jarSha256": digest(output / "app.jar"), "miniappSha256": digest(output / "miniapp.zip"),
        "databaseMigrations": False,
        "checks": ["frontend-build", "location-tests", "miniapp-tests", "deployment-tests", "backend-tests", "jar-static-content", "java17-bytecode"],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    files = sorted(path for path in output.iterdir() if path.is_file())
    (output / "SHA256SUMS").write_text("".join(f"{digest(file)}  {file.name}\n" for file in files), encoding="ascii")
    if git("status", "--porcelain", "--untracked-files=no") or git("rev-parse", "HEAD") != revision:
        raise SystemExit("Source changed during build; do not deploy this output.")
    tar_path = output_root / (revision + ".tar.gz")
    with tarfile.open(tar_path, "w:gz") as archive:
        for file in sorted(output.iterdir()):
            archive.add(file, arcname=file.name)
    checksum = digest(tar_path)
    tar_path.with_suffix(tar_path.suffix + ".sha256").write_text(f"{checksum}  {tar_path.name}\n", encoding="ascii")
    print(f"Release: {tar_path}\nSHA256: {checksum}\nRevision: {revision}")


if __name__ == "__main__":
    main()
