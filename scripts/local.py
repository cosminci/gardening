#!/usr/bin/env python3
"""Start a private local journal, optionally refreshed from a NAS snapshot."""

import argparse
import fcntl
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / ".local"
DATABASE = DATA / "gardening.db"
STATIC_DIR = ROOT / "frontend" / "dist"
BACKEND_PORT = 8080
BACKEND_READY_TIMEOUT = 180

REMOTE_SNAPSHOT = """set -eu
test -f "$1" || { echo 'NAS database does not exist' >&2; exit 1; }
directory=$(mktemp -d)
trap 'rm -rf "$directory"' EXIT
trap 'exit 1' HUP INT TERM
sqlite3 "$1" ".backup '$directory/journal.db'"
cat "$directory/journal.db"
"""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def port() -> int:
    try:
        backend = int(os.environ.get("GARDENING_PORT", BACKEND_PORT))
    except ValueError as error:
        raise RuntimeError("GARDENING_PORT must be a number between 1 and 65535.") from error
    require(0 < backend <= 65535, "GARDENING_PORT must be a number between 1 and 65535.")
    return backend


def preflight(refresh: bool) -> None:
    for tool in ("java", "sbt", "npm", "node"):
        require(shutil.which(tool) is not None, f"Install {tool} before starting local development.")
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False)
    require(
        java.returncode == 0 and re.search(r'\bversion "25(?:\.|")', java.stderr),
        "Activate the pinned Java 25 toolchain (for example, run 'mise exec -- python3 scripts/local.py').",
    )
    require((ROOT / "frontend/node_modules").is_dir(), "Run 'cd frontend && npm ci' first.")
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind(("127.0.0.1", port()))
        except OSError as error:
            raise RuntimeError(f"Port {port()} is unavailable on 127.0.0.1.") from error
    if refresh:
        require(shutil.which("ssh") is not None, "Install ssh to refresh from the NAS.")
        require(os.environ.get("GARDENING_NAS_SSH"), "Set GARDENING_NAS_SSH to the NAS SSH destination.")
        path = os.environ.get("GARDENING_NAS_DB_PATH")
        require(path and path.startswith("/"), "Set GARDENING_NAS_DB_PATH to the absolute NAS database path.")


def validate_snapshot(path: Path) -> None:
    with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as connection:
        require(connection.execute("PRAGMA integrity_check").fetchone() == ("ok",), "NAS snapshot failed integrity check.")
        require(not connection.execute("PRAGMA foreign_key_check").fetchone(), "NAS snapshot has invalid foreign keys.")
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        require(
            {"plant", "operation", "pesticide", "substrate_component", "flyway_schema_history"} <= tables,
            "NAS snapshot is not a migrated gardening journal.",
        )


def refresh(confirm: bool) -> None:
    if DATABASE.exists() and not confirm:
        answer = input("Replace your local journal and discard local edits? Type 'replace': ")
        require(answer == "replace", "Refresh cancelled; local journal is unchanged.")

    source = os.environ["GARDENING_NAS_DB_PATH"]
    destination = os.environ["GARDENING_NAS_SSH"]
    command = f"sh -s -- {shlex.quote(source)}"
    with tempfile.NamedTemporaryFile(prefix=".snapshot-", suffix=".db", dir=DATA, delete=False) as temporary:
        snapshot = Path(temporary.name)
        try:
            result = subprocess.run(
                ["ssh", "-o", "BatchMode=yes", "--", destination, command],
                input=REMOTE_SNAPSHOT.encode(),
                stdout=temporary,
                stderr=subprocess.PIPE,
                timeout=120,
                check=False,
            )
            require(result.returncode == 0, "NAS snapshot failed; check SSH access, database path, and NAS sqlite3.")
            temporary.flush()
            os.fsync(temporary.fileno())
            validate_snapshot(snapshot)
            if DATABASE.exists():
                with sqlite3.connect(DATABASE) as connection:
                    checkpoint = connection.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
                    require(checkpoint is not None and checkpoint[0] == 0, "Local journal is in use; stop it before refreshing.")
                for suffix in ("-wal", "-shm"):
                    DATABASE.with_name(DATABASE.name + suffix).unlink(missing_ok=True)
            os.replace(snapshot, DATABASE)
            directory_fd = os.open(DATA, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            snapshot.unlink(missing_ok=True)


def terminate(processes: list[subprocess.Popen[bytes]]) -> None:
    for process in processes:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
    for process in processes:
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def await_backend(backend_port: int, backend: subprocess.Popen[bytes]) -> None:
    health = f"http://127.0.0.1:{backend_port}/health"
    deadline = time.monotonic() + BACKEND_READY_TIMEOUT
    while time.monotonic() < deadline:
        require(backend.poll() is None, "The backend stopped before it became ready.")
        try:
            with urllib.request.urlopen(health, timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(0.5)
    raise RuntimeError("The backend did not become ready in time.")


def start() -> None:
    processes: list[subprocess.Popen[bytes]] = []
    backend_port = port()
    environment = {
        **os.environ,
        "GARDENING_DB_PATH": str(DATABASE),
        "GARDENING_HOST": "127.0.0.1",
        "GARDENING_PORT": str(backend_port),
        "GARDENING_STATIC_DIR": str(STATIC_DIR),
    }
    try:
        print("Building the frontend…", flush=True)
        build = subprocess.run(["npm", "run", "build"], cwd=ROOT / "frontend", env=environment, check=False)
        require(build.returncode == 0, "Frontend build failed; fix the errors above and retry.")
        print("Starting the backend…", flush=True)
        processes.append(subprocess.Popen(["sbt", "run"], cwd=ROOT / "backend", env=environment, start_new_session=True))
        await_backend(backend_port, processes[0])
        print(f"Local app: http://127.0.0.1:{backend_port}", flush=True)
        while all(process.poll() is None for process in processes):
            time.sleep(0.2)
        raise RuntimeError("The local backend stopped; shutting down.")
    finally:
        terminate(processes)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog=(
            "Run 'mise exec -- python3 scripts/local.py' after 'mise install' and "
            "'cd frontend && npm ci'. For a NAS copy, set GARDENING_NAS_SSH and "
            "GARDENING_NAS_DB_PATH, then add --refresh; type 'replace' to discard "
            "an existing local journal (or pass --yes). The NAS needs sqlite3. "
            "The frontend is built and the backend serves it on GARDENING_PORT "
            "(default 8080); set it if that port is occupied. For frontend work "
            "with hot reload, run 'cd frontend && npm run dev' separately. "
            "Local edits never sync back to the NAS."
        ),
    )
    parser.add_argument("--refresh", action="store_true", help="replace local journal from a consistent NAS snapshot")
    parser.add_argument("--yes", action="store_true", help="confirm replacement of an existing local journal")
    args = parser.parse_args()
    require(not args.yes or args.refresh, "--yes requires --refresh.")
    DATA.mkdir(mode=0o700, exist_ok=True)
    with (DATA / ".lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("Another local development session is already running.") from error
        for abandoned in DATA.glob(".snapshot-*.db"):
            abandoned.unlink()
        preflight(args.refresh)
        if args.refresh:
            refresh(args.yes)
        start()


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda _signal, _frame: sys.exit(1))
    try:
        main()
    except KeyboardInterrupt:
        print("Local setup stopped.", file=sys.stderr)
        sys.exit(130)
    except (RuntimeError, OSError, sqlite3.Error, subprocess.TimeoutExpired, EOFError) as error:
        print(f"Local setup: {error}", file=sys.stderr)
        sys.exit(1)
