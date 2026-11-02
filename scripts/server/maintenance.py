#!/usr/bin/env python3
"""Preview or remove old core images/cache without changing containers or data volumes."""
import argparse
import contextlib
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROLES = ("identity", "business", "gateway")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}\Z")
RELEASE_TAG = re.compile(r"tradepass-(identity|business|gateway):(?:[0-9a-f]{40}-[0-9]+|local)\Z")
LOCAL_DIGEST = re.compile(r"tradepass-(identity|business|gateway)@sha256:[0-9a-f]{64}\Z")
CREATED_TIME = re.compile(r"(?P<stamp>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})"
                          r"(?:\.(?P<fraction>[0-9]{1,9}))?(?P<zone>Z|[+-][0-9]{2}:[0-9]{2})\Z")
DEFAULT_HISTORY_DIR = Path("/docker/tradepass/jenkins/jenkins")


class SafetyError(RuntimeError):
    pass


class BusyError(SafetyError):
    pass


class Docker:
    def run(self, *args, timeout=120):
        result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            # Docker/Compose output can contain private configuration; do not echo it.
            raise SafetyError("Docker command failed: " + " ".join(args[:2]))
        return result.stdout.strip()

    def check_local(self):
        # DOCKER_CONTEXT takes precedence over DOCKER_HOST in Docker's CLI.
        endpoint = None if os.environ.get("DOCKER_CONTEXT") else os.environ.get("DOCKER_HOST")
        endpoint = endpoint or self.run("context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
        if not endpoint.startswith("unix://"):
            raise SafetyError("Maintenance requires the server's local Docker socket")

    def inspect_all(self, kind):
        ids = list(dict.fromkeys(self.run(kind, "ls", "-aq", "--no-trunc").split()))
        rows = []
        for start in range(0, len(ids), 50):
            rows.extend(json.loads(self.run(kind, "inspect", *ids[start:start + 50])))
        return rows

    def cache_command(self, age_hours, storage):
        help_text = self.run("builder", "prune", "--help")
        if "--max-used-space" in help_text:
            limit = "--max-used-space"
        elif "--keep-storage" in help_text:
            limit = "--keep-storage"
        else:
            raise SafetyError("Docker builder prune has no supported cache size limit")
        return ["builder", "prune", "--force", "--filter", "until=" + str(age_hours) + "h", limit, storage]


def emit(event, **values):
    print(json.dumps({"event": event, **values}, ensure_ascii=False, sort_keys=True), flush=True)


def is_build_command(argv):
    """Check executable/argument boundaries, never arbitrary substrings in shell source."""
    if not argv:
        return False
    names = [Path(arg).name for arg in argv]
    if any(name in {"core_release.py", "core_apply.py", "mvn", "mvnDebug", "mvnw"} for name in names):
        return True
    if any(arg in {"org.codehaus.plexus.classworlds.launcher.Launcher", "org.apache.maven.cli.MavenCli"}
           for arg in argv):
        return True
    executable = names[0]
    if executable in {"docker-buildx", "buildctl", "buildctl-daemonless.sh"}:
        return any(arg in {"build", "bake", "load", "import"} for arg in argv[1:])
    if executable == "docker":
        arguments = argv[1:]
        takes_value = {"--config", "--context", "--host", "-H", "--log-level", "-l", "--tlscacert", "--tlscert", "--tlskey"}
        while arguments and arguments[0].startswith("-"):
            option = arguments.pop(0)
            if option in takes_value and arguments:
                arguments.pop(0)
        if arguments and arguments[0] in {"build", "load", "import", "tag", "commit"}:
            return True
        if len(arguments) > 1 and arguments[0] in {"buildx", "image", "compose"}:
            return arguments[1] in {"build", "bake", "load", "import", "tag", "up", "create"}
    return False


def assert_idle(proc_dir=Path("/proc")):
    if not proc_dir.is_dir():
        raise SafetyError("Cannot verify active builds without Linux /proc")
    for entry in proc_dir.iterdir():
        if not entry.name.isdecimal() or int(entry.name) == os.getpid():
            continue
        try:
            argv = (entry / "cmdline").read_bytes().decode(errors="replace").rstrip("\0").split("\0")
        except (FileNotFoundError, ProcessLookupError):
            continue
        except PermissionError:
            raise SafetyError("Cannot verify all build processes; run as root") from None
        if is_build_command(argv):
            raise BusyError("A build or deployment is active; maintenance skipped")


@contextlib.contextmanager
def exclusive_lock(path):
    flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(str(path), flags, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise BusyError("Maintenance or deployment lock is busy; maintenance skipped") from None
        yield
    finally:
        os.close(fd)


def read_history(directory):
    if not directory.is_absolute() or not directory.is_dir() or directory.is_symlink():
        raise SafetyError("History directory must be an existing absolute directory")
    journal = directory / "transaction.json"
    if journal.exists() or journal.is_symlink():
        raise SafetyError("An interrupted deployment exists; recover it before cleanup")
    path = directory / "history.json"
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise SafetyError("Missing or unsafe release history")
    try:
        history = json.loads(path.read_text())
    except (ValueError, OSError):
        raise SafetyError("Cannot read valid release history") from None
    if not isinstance(history, dict) or set(history) != set(ROLES):
        raise SafetyError("Release history must cover exactly the three core services")
    for role in ROLES:
        record = history[role]
        if not isinstance(record, dict) or any(not isinstance(record.get(key), str) or
                                               not IMAGE_ID.fullmatch(record[key])
                                               for key in ("current", "previous")):
            raise SafetyError("Missing current/rollback image for " + role)
    return history


def snapshot(docker, directory):
    history = read_history(directory)
    containers = docker.inspect_all("container")
    images = {row["Id"]: row for row in docker.inspect_all("image")}
    protected = {row["Image"] for row in containers}
    for role in ROLES:
        name = "/tradepass-core-" + role + "-1"
        matches = [row for row in containers if row.get("Name") == name]
        if len(matches) != 1:
            raise SafetyError("Missing core container for " + role)
        row = matches[0]
        state, labels = row.get("State", {}), row.get("Config", {}).get("Labels") or {}
        if state.get("Status") != "running" or state.get("Health", {}).get("Status") != "healthy":
            raise SafetyError("Core container is not healthy: " + role)
        if labels.get("com.docker.compose.project") != "tradepass-core" or labels.get("com.docker.compose.service") != role:
            raise SafetyError("Core container ownership does not match: " + role)
        if row["Image"] != history[role]["current"]:
            raise SafetyError("Running image differs from current release history: " + role)
        for key in ("current", "previous"):
            image_id = history[role][key]
            if image_id not in images:
                raise SafetyError("Required " + key + " image is missing: " + role)
            protected.add(image_id)
    # History is updated atomically by the publisher; ensure it stayed unchanged during inspection.
    if read_history(directory) != history:
        raise SafetyError("Release history changed during inspection")
    fingerprint = hashlib.sha256(json.dumps(history, sort_keys=True).encode()).hexdigest()
    return {"history": history, "fingerprint": fingerprint, "protected": protected, "images": images}


def parse_created(value):
    """Normalize Docker RFC3339 nanoseconds for Python 3.9's microsecond-only parser."""
    match = CREATED_TIME.fullmatch(value) if isinstance(value, str) else None
    if not match:
        raise SafetyError("Cannot determine release image age")
    fraction = match.group("fraction")
    normalized = match.group("stamp")
    if fraction is not None:
        normalized += "." + (fraction + "000000")[:6]
    normalized += "+00:00" if match.group("zone") == "Z" else match.group("zone")
    try:
        created = datetime.fromisoformat(normalized)
        if created.tzinfo is None:
            raise ValueError()
        return created
    except ValueError:
        raise SafetyError("Cannot determine release image age") from None


def image_candidates(state, min_age_hours, now=None):
    now = now or datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=min_age_hours)
    candidates = []
    for image_id, row in state["images"].items():
        if image_id in state["protected"]:
            continue
        tags = row.get("RepoTags") or []
        # Preserve untagged/base/third-party images, aliases, and anything outside release naming.
        if len(tags) != 1 or not all(RELEASE_TAG.fullmatch(tag) for tag in tags):
            continue
        # Docker's containerd image store also supplies local repository digests after builds.
        # Preserve external registry references, but do not mistake our own local digests for them.
        if any(not isinstance(digest, str) or not LOCAL_DIGEST.fullmatch(digest)
               for digest in row.get("RepoDigests") or []):
            continue
        created = parse_created(row.get("Created"))
        if created > cutoff:
            continue
        candidates.append({"id": image_id, "tags": sorted(tags)})
    return sorted(candidates, key=lambda row: row["tags"])


def maintain(docker, directory, apply=False, min_image_age_hours=24, cache_age_hours=24,
             cache_keep_storage="4GB", skip_cache=False, idle_check=assert_idle):
    idle_check()
    initial = snapshot(docker, directory)
    candidates = image_candidates(initial, min_image_age_hours)
    multitag = [{"id": image_id, "tags": sorted(row["RepoTags"])}
                for image_id, row in initial["images"].items()
                if len(row.get("RepoTags") or []) > 1 and
                all(RELEASE_TAG.fullmatch(tag) for tag in row["RepoTags"])]
    cache = None if skip_cache else docker.cache_command(cache_age_hours, cache_keep_storage)
    emit("plan", apply=apply, keep=initial["history"], image_count=len(initial["images"]),
         remove_count=len(candidates), remove_images=candidates,
         preserved_multitag_images=multitag,
         cache_command=None if cache is None else ["docker", *cache])
    if not apply:
        return {"status": "dry-run", "remove_count": len(candidates)}
    removed = []
    for candidate in candidates:
        idle_check()
        current = snapshot(docker, directory)
        if current["fingerprint"] != initial["fingerprint"]:
            raise SafetyError("Release history changed; cleanup aborted")
        eligible = {row["id"]: row for row in image_candidates(current, min_image_age_hours)}
        if eligible.get(candidate["id"]) != candidate:
            raise SafetyError("Image protection or tags changed; cleanup aborted")
        # No force: Docker also protects images referenced by concurrently created containers.
        docker.run("image", "rm", candidate["id"], timeout=180)
        removed.append(candidate["id"])
        emit("image_removed", id=candidate["id"], tags=candidate["tags"])
    if cache is not None:
        idle_check()
        current = snapshot(docker, directory)
        if current["fingerprint"] != initial["fingerprint"]:
            raise SafetyError("Release history changed; cache cleanup aborted")
        # Omit --all. BuildKit prunes unused cache only; current and rollback images stay referenced.
        output = docker.run(*cache, timeout=1800)
        # Output contains cache IDs and a reclaimed summary, not application configuration.
        summary = [line.strip() for line in output.splitlines() if line.strip().startswith("Total:")]
        emit("cache_pruned", summary=summary[-1:] or ["completed"])
    return {"status": "complete", "removed_count": len(removed), "cache_pruned": cache is not None}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--history-dir", type=Path, default=DEFAULT_HISTORY_DIR)
    result.add_argument("--lock-file", type=Path, default=Path("/var/lock/tradepass-maintenance.lock"))
    result.add_argument("--apply", action="store_true", help="Apply the reviewed plan; default is dry-run")
    result.add_argument("--min-image-age-hours", type=int, default=24)
    result.add_argument("--cache-age-hours", type=int, default=24)
    result.add_argument("--cache-keep-storage", default="4GB")
    result.add_argument("--skip-cache", action="store_true")
    return result


def main(argv=None):
    argument_parser = parser()
    args = argument_parser.parse_args(argv)
    if args.min_image_age_hours < 24 or args.cache_age_hours < 1:
        argument_parser.error("Protect images for at least 24 hours and cache for at least one hour")
    if not re.fullmatch(r"[1-9][0-9]*(?:B|KB|MB|GB|TB|KiB|MiB|GiB|TiB)", args.cache_keep_storage):
        argument_parser.error("Cache storage must be a positive size, for example 4GB")
    try:
        docker = Docker()
        docker.check_local()
        # Validate paths/history before opening the locks; do not create directories automatically.
        read_history(args.history_dir)
        with exclusive_lock(args.lock_file), exclusive_lock(args.history_dir / "publish.lock"):
            outcome = maintain(docker, args.history_dir, args.apply, args.min_image_age_hours,
                               args.cache_age_hours, args.cache_keep_storage, args.skip_cache)
        emit("result", **outcome)
        return 0
    except BusyError as exc:
        emit("result", status="skipped", reason=str(exc))
        return 0
    except (SafetyError, OSError, ValueError, subprocess.TimeoutExpired) as exc:
        # Unexpected CLI/JSON errors also fail closed. Do not include raw command output.
        message = str(exc) if isinstance(exc, SafetyError) else type(exc).__name__
        emit("result", status="error", reason=message)
        return 1


if __name__ == "__main__":
    sys.exit(main())
