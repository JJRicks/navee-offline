#!/usr/bin/env python3
"""Bounded disposable-worker Volt CI autoscaler. Credentials remain on this controller.

The reusable image must never have run a job or contained runner credentials.
Each job receives a fresh VM/JIT registration. This module deliberately has no
image-creation API and never executes repository-controlled code locally.
"""

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

REPOSITORY = "JJRicks/navee-offline"
WORKFLOW = ".github/workflows/tests.yml"
BASE_LABELS = {"self-hosted", "Linux", "volt-ci"}
JOB_LABELS = {"core-tests": "volt-job-core-tests", "web-setup": "volt-job-web-setup"}
OWNER_LABEL = "volt-ci-deployment"
WORKER_LABEL = "volt-ci-worker"
ROLE_LABEL = "volt-ci-role"


class ApiError(Exception):
    """Sanitized API failure: never include response bodies or credentials."""

    def __init__(self, service, method, path, status=None):
        super().__init__(f"{service} {method} {path.split('?')[0]} status={status}")
        self.status = status


def log(event, **fields):
    print(json.dumps({"event": event, **fields}, sort_keys=True), flush=True)


class Api:
    def __init__(self, service, token_file):
        self.service = service
        self.token_file = Path(token_file)
        self.base = ("https://api.github.com" if service == "github"
                     else "https://api.hetzner.cloud/v1")

    def request(self, method, path, body=None):
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("API paths must be relative")
        token = self.token_file.read_text().strip()
        if not token or "\n" in token or "\r" in token:
            raise ValueError("Invalid credential file")
        headers = {"Authorization": f"Bearer {token}", "User-Agent": "Volt-CI-controller",
                   "Accept": "application/vnd.github+json" if self.service == "github" else "application/json"}
        if self.service == "github":
            headers["X-GitHub-Api-Version"] = "2022-11-28"
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, response, code, message, response_headers, new_url):
                if urllib.parse.urlsplit(new_url)[:2] != urllib.parse.urlsplit(request.full_url)[:2]:
                    raise urllib.error.URLError("Cross-origin API redirect refused")
                return super().redirect_request(request, response, code, message, response_headers, new_url)

        try:
            with urllib.request.build_opener(SameOriginRedirect()).open(request, timeout=25) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            raise ApiError(self.service, method, path, error.code) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise ApiError(self.service, method, path) from None

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, body):
        return self.request("POST", path, body)

    def put(self, path, body):
        return self.request("PUT", path, body)

    def delete(self, path):
        return self.request("DELETE", path)


def paginated(api, path, field):
    """Read every page; never act on a silently truncated inventory."""
    result = []
    separator = "&" if "?" in path else "?"
    for page in range(1, 101):
        response = api.get(f"{path}{separator}per_page=100&page={page}")
        items = response[field]
        if not isinstance(items, list):
            raise ValueError("Invalid paginated API response")
        result.extend(items)
        # GitHub returns total_count, Hetzner pagination.next_page. A short
        # page alone is sufficient only when the API supplies neither.
        pagination = response.get("meta", {}).get("pagination", {})
        if pagination:
            if pagination.get("next_page") is None:
                return result
        elif "total_count" in response:
            if len(result) >= response["total_count"]:
                return result
            if not items:
                raise ValueError("Pagination ended before total_count")
        elif len(items) < 100:
            return result
    raise ValueError("Pagination safety limit reached")


def numeric(value):
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def mapping(value):
    return value if isinstance(value, dict) else {}


def load_config(path):
    config = json.loads(Path(path).read_text())
    if config.get("repository", REPOSITORY) != REPOSITORY:
        raise ValueError("This controller is scoped to JJRicks/navee-offline")
    config["repository"] = REPOSITORY
    config["deployment_id"] = str(uuid.UUID(config["deployment_id"]))
    for field in ("snapshot_id", "firewall_id", "ssh_key_id"):
        if not numeric(config.get(field)):
            raise ValueError(f"Invalid {field}")
    for field in ("github_token_file", "hetzner_token_file", "ssh_private_key_file", "state_dir"):
        if not Path(config[field]).is_absolute():
            raise ValueError(f"{field} must be absolute")
    for field in ("server_type", "location"):
        if not re.fullmatch(r"[a-z0-9-]+", config[field]):
            raise ValueError(f"Invalid {field}")
    defaults = {"poll_seconds": 20, "max_lifetime_seconds": 1800,
                "max_attempts_per_job": 2, "cooldown_seconds": 60,
                "startup_timeout_seconds": 600, "allow_public_repository": False,
                "runner_arch": "ARM64", "max_launches_per_hour": 12,
                "max_launches_per_day": 60, "max_workers": 1}
    for key, value in defaults.items():
        config.setdefault(key, value)
    for key in ("poll_seconds", "max_lifetime_seconds", "max_attempts_per_job",
                "cooldown_seconds", "startup_timeout_seconds", "max_launches_per_hour", "max_launches_per_day",
                "max_workers"):
        if not numeric(config[key]):
            raise ValueError(f"Invalid {key}")
    if config["max_workers"] > 2:
        raise ValueError("Worker concurrency exceeds controller safety cap")
    if config["max_lifetime_seconds"] > 1800 or config["max_attempts_per_job"] > 3:
        raise ValueError("Worker lifetime/attempt limits exceed controller safety cap")
    if config["max_launches_per_hour"] > 12 or config["max_launches_per_day"] > 60:
        raise ValueError("Launch rate exceeds controller safety cap")
    if config["runner_arch"] not in ("ARM64", "X64"):
        raise ValueError("Unsupported runner architecture")
    if not isinstance(config["allow_public_repository"], bool):
        raise ValueError("allow_public_repository must be boolean")
    config["allowed_actors"] = {"jjricks", "ollies5"}
    return config


class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.directory, 0o700)
        self.path = self.directory / "state.json"
        self.lock = None

    def acquire(self):
        self.lock = open(self.directory / "controller.lock", "a+")
        os.chmod(self.directory / "controller.lock", 0o600)
        try:
            fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except BlockingIOError:
            self.lock.close()
            self.lock = None
            return False

    def close(self):
        if self.lock:
            self.lock.close()
            self.lock = None

    def load(self):
        if not self.path.exists():
            return {"version": 2, "workers": [], "attempts": {}, "next_launch_at": 0}
        state = json.loads(self.path.read_text())
        if state.get("version") == 1:
            # Preserve the exact reservation, retry budgets and IP ownership
            # proof. The old controller rejects v2 rather than losing workers
            # after an accidental binary rollback.
            active = state.pop("active")
            state["workers"] = [] if active is None else [active]
            state["version"] = 2
        workers = state.get("workers")
        if (state.get("version") != 2 or not isinstance(state.get("attempts"), dict)
                or not isinstance(workers, list) or len(workers) > 2
                or any(not isinstance(worker, dict) for worker in workers)):
            raise ValueError("Invalid controller state; refusing to replace it")
        names = [worker.get("name") for worker in workers]
        keys = [worker.get("budget_key") for worker in workers]
        if (any(not isinstance(name, str) or not re.fullmatch(r"volt-ci-[a-z0-9-]+", name) for name in names)
                or len(set(names)) != len(names) or any(not isinstance(key, str) for key in keys)
                or len(set(keys)) != len(keys)):
            raise ValueError("Ambiguous worker state; refusing to replace it")
        return state

    def save(self, state):
        temporary = self.directory / "state.json.tmp"
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            json.dump(state, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.path)
        directory_fd = os.open(self.directory, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)


class SshWorker:
    """Per-worker host identity is generated before any create request."""
    def __init__(self, config):
        self.config = config
        self.directory = Path(config["state_dir"]) / "workers"
        self.directory.mkdir(mode=0o700, exist_ok=True)

    def folder(self, name):
        if not re.fullmatch(r"volt-ci-[a-z0-9-]+", name):
            raise ValueError("Invalid worker name")
        return self.directory / name

    def prepare(self, name):
        folder = self.folder(name)
        folder.mkdir(mode=0o700, exist_ok=True)
        key = folder / "host_ed25519"
        if not key.exists():
            subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(key)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        public = key.with_suffix(".pub").read_text().strip().split()
        known = folder / "known_hosts"
        known.write_text(f"{name} {public[0]} {public[1]}\n")
        os.chmod(known, 0o600)
        return "#cloud-config\n" + json.dumps({"ssh_deletekeys": True,
            "ssh_keys": {"ed25519_private": key.read_text(), "ed25519_public": " ".join(public[:2])}})

    def command(self, active, command):
        address = active["ip"]
        if not re.fullmatch(r"[0-9.]+", address):
            raise ValueError("Invalid worker IPv4")
        return ["ssh", "-T", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
                "-o", "ConnectionAttempts=1", "-o", "ConnectTimeout=8",
                "-o", "StrictHostKeyChecking=yes", "-o", f"HostKeyAlias={active['name']}",
                "-o", f"UserKnownHostsFile={self.folder(active['name']) / 'known_hosts'}",
                "-i", self.config["ssh_private_key_file"], f"root@{address}", command]

    def ready(self, active):
        try:
            command = "/usr/bin/cloud-init status --wait >/dev/null 2>&1 && test -x /opt/volt-ci/start-job"
            return subprocess.run(self.command(active, command), stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, timeout=12).returncode == 0
        except subprocess.TimeoutExpired:
            return False

    def start(self, active, jit):
        if not isinstance(jit, str) or not jit or "\n" in jit:
            raise ValueError("Invalid JIT response")
        # stdin, never argv/environment/logs; the worker script must return only
        # after consuming this value and starting its detached systemd service.
        subprocess.run(self.command(active, "/opt/volt-ci/start-job"), input=(jit + "\n").encode(),
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)

    def forget(self, name):
        folder = self.folder(name)
        if folder.exists():
            shutil.rmtree(folder)


class Controller:
    def __init__(self, config, github, cloud, store, worker, clock=time.time):
        self.config, self.gh, self.cloud = config, github, cloud
        self.store, self.worker, self.clock = store, worker, clock
        self.state = store.load()
        self.root = f"/repos/{REPOSITORY}"
        self.owner = config["deployment_id"]
        if self.state.get("deployment_id", self.owner) != self.owner:
            raise ValueError("State belongs to another deployment")
        self.state["deployment_id"] = self.owner
        self.state.setdefault("pending_ips", {})
        self.state.setdefault("launch_history", [])
        self.save()

    def save(self):
        self.store.save(self.state)

    def owned(self, item):
        labels = item.get("labels", {})
        return labels.get(OWNER_LABEL) == self.owner and labels.get(ROLE_LABEL) == "worker"

    def labels(self, run_id, attempt, job_name):
        return sorted(BASE_LABELS | {self.config["runner_arch"], f"volt-run-{run_id}-{attempt}",
                                    JOB_LABELS[job_name]})

    def resource_list(self, resource):
        selector = urllib.parse.quote(f"{OWNER_LABEL}={self.owner}", safe="")
        # Check exact ownership again even though the server filters the list.
        return [item for item in paginated(self.cloud, f"/{resource}?label_selector={selector}", resource)
                if self.owned(item)]

    def remove_runner(self, runner_id):
        if not runner_id:
            return
        try:
            self.gh.delete(f"{self.root}/actions/runners/{int(runner_id)}")
        except ApiError as error:
            if error.status != 404:
                raise

    def delete_server(self, server):
        if not self.owned(server):
            raise ValueError("Refusing to delete a server without exact deployment ownership")
        self.remember_address(server)
        try:
            self.cloud.delete(f"/servers/{int(server['id'])}")
        except ApiError as error:
            if error.status != 404:
                raise
        log("worker_delete_requested", server_id=server["id"])

    def remember_address(self, server):
        """Retain ownership proof across server deletion and interrupted tagging."""
        address = server.get("public_net", {}).get("ipv4") or {}
        if address.get("id"):
            self.state["pending_ips"][str(address["id"])] = {
                "server_id": server["id"], "worker": server["name"]}
            self.save()

    def reconcile_addresses(self):
        for identity, provenance in list(self.state["pending_ips"].items()):
            try:
                address = self.cloud.get(f"/primary_ips/{int(identity)}")["primary_ip"]
            except ApiError as error:
                if error.status != 404:
                    raise
                del self.state["pending_ips"][identity]
                self.save()
                continue
            owner = address.get("labels", {}).get(OWNER_LABEL)
            role = address.get("labels", {}).get(ROLE_LABEL)
            assignee = address.get("assignee_id")
            if (owner not in (None, self.owner) or role not in (None, "worker")
                    or assignee not in (None, provenance["server_id"])):
                log("ip_ownership_changed", ip_id=int(identity))
                # Never touch an address reassigned by an administrator.
                del self.state["pending_ips"][identity]
                self.save()
                continue
            if assignee is None:
                # Exact ownership was established from this deployment's server
                # while still assigned, even if the tagging call never completed.
                try:
                    self.cloud.delete(f"/primary_ips/{int(identity)}")
                except ApiError as error:
                    if error.status != 404:
                        raise
                del self.state["pending_ips"][identity]
                self.save()
                log("worker_ip_deleted", ip_id=int(identity))

    def clean_ip(self, primary_ip):
        if not self.owned(primary_ip) or primary_ip.get("assignee_id") is not None:
            return
        try:
            self.cloud.delete(f"/primary_ips/{int(primary_ip['id'])}")
        except ApiError as error:
            if error.status != 404:
                raise
        log("orphan_ip_deleted", ip_id=primary_ip["id"])

    def reconcile(self):
        """Cloud inventory/cleanup precede every GitHub request, even on auth loss."""
        servers = self.resource_list("servers")
        current = {}
        for active in self.state["workers"]:
            matches = [server for server in servers if server["name"] == active["name"]]
            if len(matches) > 1:
                raise ValueError("Ambiguous owned server name")
            if not matches:
                continue
            server = matches[0]
            current[active["name"]] = server
            self.remember_address(server)
            if active.get("server_id") and active["server_id"] != server["id"]:
                raise ValueError("Worker identity changed")
            active["server_id"] = server["id"]
            ipv4 = server.get("public_net", {}).get("ipv4") or {}
            if ipv4.get("id"):
                active["ip_id"] = ipv4["id"]
                active["ip"] = ipv4["ip"]
            if active["phase"] == "creating":
                active["phase"] = "booting"
            self.save()
        cleanup_pending = False
        for server in servers:
            if server["name"] not in current:
                cleanup_pending = True
                try:
                    self.delete_server(server)
                except ApiError as error:
                    log("orphan_cleanup_deferred", server_id=server["id"], status=error.status)
        try:
            for address in self.resource_list("primary_ips"):
                self.clean_ip(address)
            self.reconcile_addresses()
        except ApiError as error:
            # An IP endpoint outage must not prevent the active server's expiry,
            # power-off or interrupted-deletion handling in tick(). It still
            # blocks replacement spending until reconciliation succeeds.
            cleanup_pending = True
            log("ip_cleanup_deferred", status=error.status)
        # Addresses belonging to current workers are expected and retain their
        # ownership proof. Unassigned/orphan addresses must finish cleanup before
        # another slot is filled, without serializing healthy active workers.
        current_ids = {server["id"] for server in current.values()}
        pending_orphan = any(proof["server_id"] not in current_ids
                             for proof in self.state["pending_ips"].values())
        return current, cleanup_pending or pending_orphan

    def cleanup(self, active, reason, server=None):
        active["phase"] = "deleting"
        active["cleanup_reason"] = reason
        self.save()
        # A GitHub outage MUST NOT keep a billable VM alive. Deletion is requested
        # first and retried on the next poll until the cloud confirms absence.
        if server:
            self.delete_server(server)
        cloud_absent = server is None
        # Address ownership is persisted before deletion. Reconcile addresses on
        # the next poll so their API cannot hold up server or runner cleanup.
        runner_gone = True
        try:
            if active.get("runner_id"):
                self.remove_runner(active["runner_id"])
            elif active.get("registration_started"):
                # The POST may have succeeded despite losing its response.
                for runner in paginated(self.gh, f"{self.root}/actions/runners", "runners"):
                    if runner.get("name") == active["name"]:
                        self.remove_runner(runner["id"])
        except ApiError:
            runner_gone = False
            log("runner_cleanup_deferred", worker=active["name"])
        if cloud_absent and runner_gone:
            log("worker_finished", worker=active["name"], reason=reason)
            self.worker.forget(active["name"])
            self.state["workers"].remove(active)
            self.state["next_launch_at"] = self.clock() + self.config["cooldown_seconds"]
            self.save()

    def allowed_run(self, run):
        if not isinstance(run, dict):
            return False
        path = run.get("path")
        # GitHub can include a ref suffix in this field (tests.yml@main).
        # Only that suffix is optional; a different repository/path is rejected.
        if (not isinstance(path, str) or path.split("@", 1)[0] != WORKFLOW
                or path.endswith("@") or run.get("status") not in ("queued", "in_progress")):
            return False
        if not numeric(run.get("id")) or not numeric(run.get("run_attempt")):
            return False
        repository = mapping(run.get("repository"))
        if repository.get("full_name") != REPOSITORY:
            return False
        repository_id = repository.get("id")
        if not numeric(repository_id):
            return False
        for actor_field in ("actor", "triggering_actor"):
            actor = mapping(run.get(actor_field)).get("login")
            if not isinstance(actor, str):
                return False
            actor = actor.lower()
            if actor not in self.config["allowed_actors"]:
                return False
        head_repository = mapping(run.get("head_repository"))
        if (head_repository.get("full_name") != REPOSITORY
                or head_repository.get("id") != repository_id):
            return False
        if run.get("event") in ("push", "workflow_dispatch"):
            return True
        pull_requests = run.get("pull_requests")
        if (run.get("event") != "pull_request" or not isinstance(pull_requests, list)
                or len(pull_requests) != 1):
            return False
        for pr in pull_requests:
            if not isinstance(pr, dict) or not numeric(pr.get("number")):
                return False
            # These are authoritative associations from the Actions API, not PR
            # body text. This works with Actions(read) + Administration(write)
            # without additionally granting Pull requests access.
            if (mapping(mapping(pr.get("head")).get("repo")).get("id") != repository_id
                    or mapping(mapping(pr.get("base")).get("repo")).get("id") != repository_id):
                return False
        return True

    def jobs(self, run_id, attempt):
        return paginated(self.gh, f"{self.root}/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs")

    def budget_key(self, run_id, attempt, job_id):
        return f"{run_id}:{attempt}:{job_id}"

    def repository_policy_allows_runner(self):
        repo = self.gh.get(self.root)
        if repo.get("full_name") != REPOSITORY:
            return False
        if repo.get("private") is not True and not self.config["allow_public_repository"]:
            log("public_repository_paused")
            return False
        if repo.get("private") is True:
            policy = self.gh.get(f"{self.root}/actions/permissions/fork-pr-workflows-private-repos")
            if policy.get("run_workflows_from_fork_pull_requests") is not False:
                log("fork_workflows_policy_paused")
                return False
        return True

    def queued_jobs(self, run_id, attempt):
        jobs = self.jobs(run_id, attempt)
        for job in jobs:
            name = job.get("name")
            if (name not in JOB_LABELS or job.get("status") != "queued" or not numeric(job.get("id"))
                    or sum(other.get("name") == name for other in jobs) != 1
                    or set(job.get("labels", [])) != set(self.labels(run_id, attempt, name))):
                continue
            yield job

    def eligible(self):
        if not self.repository_policy_allows_runner():
            return []
        runs = {}
        for status in ("queued", "in_progress"):
            for run in paginated(self.gh, f"{self.root}/actions/runs?status={status}", "workflow_runs"):
                runs[run["id"]] = run
        candidates = []
        for run in sorted(runs.values(), key=lambda r: r["id"]):
            if not self.allowed_run(run):
                continue
            for job in self.queued_jobs(run["id"], run["run_attempt"]):
                key = self.budget_key(run["id"], run["run_attempt"], job["id"])
                if (self.state["attempts"].get(key, 0) >= self.config["max_attempts_per_job"]
                        or any(worker["budget_key"] == key for worker in self.state["workers"])):
                    continue
                candidates.append({"run_id": run["id"], "attempt": run["run_attempt"],
                                   "job_id": job["id"], "job_name": job["name"], "budget_key": key})
        return candidates

    def still_queued(self, candidate):
        if not self.repository_policy_allows_runner():
            return False
        run = self.gh.get(f"{self.root}/actions/runs/{candidate['run_id']}")
        if not self.allowed_run(run) or run["run_attempt"] != candidate["attempt"]:
            return False
        return any(job["id"] == candidate["job_id"] and job["name"] == candidate.get("job_name")
                   for job in self.queued_jobs(candidate["run_id"], candidate["attempt"]))

    def launch(self, candidate):
        # Every reservation counts, including uncertain creation and deletion.
        if (len(self.state["workers"]) >= self.config["max_workers"]
                or any(worker["budget_key"] == candidate["budget_key"] for worker in self.state["workers"])
                or self.state["attempts"].get(candidate["budget_key"], 0) >= self.config["max_attempts_per_job"]):
            return
        # Last-minute state/attempt validation is deliberately separate from the
        # initial queue scan. Cancellations/reruns must not provision stale work.
        if not self.still_queued(candidate):
            return
        now = self.clock()
        history = [stamp for stamp in self.state["launch_history"] if now - stamp < 86400]
        self.state["launch_history"] = history
        if (any(stamp > now for stamp in history)
                or len(history) >= self.config["max_launches_per_day"]
                or sum(now - stamp < 3600 for stamp in history) >= self.config["max_launches_per_hour"]):
            self.save()
            log("launch_rate_limited")
            return
        image = self.cloud.get(f"/images/{self.config['snapshot_id']}")["image"]
        expected_arch = "arm" if self.config["runner_arch"] == "ARM64" else "x86"
        if (image.get("type") != "snapshot" or image.get("status") != "available"
                or image.get("labels", {}).get(OWNER_LABEL) != self.owner
                or image.get("labels", {}).get(ROLE_LABEL) != "image"
                or image.get("architecture") != expected_arch):
            raise ValueError("Configured image is not this deployment's clean snapshot")
        count = self.state["attempts"].get(candidate["budget_key"], 0) + 1
        name = f"volt-ci-{self.owner[:8]}-{candidate['job_id']}-{count}-{uuid.uuid4().hex[:8]}"
        active = {**candidate, "name": name, "created_at": self.clock(), "phase": "reserved"}
        self.state["workers"].append(active)
        self.state["attempts"][candidate["budget_key"]] = count
        self.state["launch_history"].append(now)
        self.save()
        user_data = self.worker.prepare(name)
        active["phase"] = "creating"
        self.save()  # durable intent BEFORE the possibly ambiguous API mutation
        payload = {"name": name, "server_type": self.config["server_type"],
                   "location": self.config["location"], "image": self.config["snapshot_id"],
                   "ssh_keys": [self.config["ssh_key_id"]],
                   "firewalls": [{"firewall": self.config["firewall_id"]}],
                   "labels": {OWNER_LABEL: self.owner, WORKER_LABEL: name, ROLE_LABEL: "worker"},
                   "public_net": {"enable_ipv4": True, "enable_ipv6": False},
                   "start_after_create": True, "user_data": user_data}
        try:
            result = self.cloud.post("/servers", payload)
        except ApiError as error:
            # Even 4xx/5xx outcomes are resolved from owned inventory rather than
            # blindly repeating create. An unknown outcome keeps its slot reserved.
            log("create_outcome_unconfirmed", worker=name, status=error.status)
            return
        server = result["server"]
        if not self.owned(server) or server.get("name") != name:
            raise ValueError("Created server identity mismatch")
        active["server_id"] = server["id"]
        active["phase"] = "booting"
        self.save()
        log("worker_created", worker=name, server_id=server["id"], run_id=candidate["run_id"])

    def tag_address(self, active, server):
        if active.get("ip_tagged") or not active.get("ip_id"):
            return
        address = self.cloud.get(f"/primary_ips/{active['ip_id']}")["primary_ip"]
        if address.get("assignee_id") != server["id"]:
            raise ValueError("Worker IP assignment changed")
        if (address.get("labels", {}).get(OWNER_LABEL) not in (None, self.owner)
                or address.get("labels", {}).get(ROLE_LABEL) not in (None, "worker")):
            raise ValueError("Worker IP has another owner")
        self.cloud.put(f"/primary_ips/{active['ip_id']}", {"auto_delete": True,
            "labels": {**address.get("labels", {}), OWNER_LABEL: self.owner,
                       WORKER_LABEL: active["name"], ROLE_LABEL: "worker"}})
        active["ip_tagged"] = True
        self.save()

    def start_job(self, active, server):
        self.tag_address(active, server)
        if not active.get("ip") or not self.worker.ready(active):
            return
        if not self.still_queued(active):
            self.cleanup(active, "job_no_longer_queued", server)
            return
        # A crash after requesting JIT but before saving its id cannot safely
        # replay registration. Reconciliation cleans the named registration.
        active["phase"] = "registering"
        active["registration_started"] = True
        self.save()
        try:
            jit = self.gh.post(f"{self.root}/actions/runners/generate-jitconfig", {
                "name": active["name"], "runner_group_id": 1,
                "labels": self.labels(active["run_id"], active["attempt"], active["job_name"]),
                "work_folder": "_work"})
        except ApiError:
            self.cleanup(active, "jit_registration_failed", server)
            return
        active["runner_id"] = jit["runner"]["id"]
        active["phase"] = "starting"
        self.save()
        try:
            self.worker.start(active, jit["encoded_jit_config"])
        except (subprocess.SubprocessError, OSError):
            self.cleanup(active, "worker_start_failed", server)
            return
        active["phase"] = "running"
        active["started_at"] = self.clock()
        self.save()
        log("worker_started", worker=active["name"], runner_id=active["runner_id"])

    def observe_running(self, active, server):
        run = self.gh.get(f"{self.root}/actions/runs/{active['run_id']}")
        if run.get("run_attempt") != active["attempt"] or run.get("status") == "completed":
            self.cleanup(active, "run_finished_or_superseded", server)
            return
        jobs = self.jobs(active["run_id"], active["attempt"])
        assigned = [job for job in jobs if job.get("runner_id") == active.get("runner_id")]
        if assigned:
            job = assigned[0]
            if len(assigned) != 1 or job["id"] != active["job_id"]:
                self.cleanup(active, "unexpected_job_assignment", server)
                return
            if not active.get("assigned_job_id"):
                active["assigned_job_id"] = job["id"]
                self.save()
            if job.get("status") == "completed":
                self.cleanup(active, "job_completed", server)
        elif self.clock() - active.get("started_at", active["created_at"]) > 180:
            self.cleanup(active, "job_assignment_timeout", server)

    def advance(self, active, server, now):
        age = now - active["created_at"]
        if active["phase"] == "deleting":
            self.cleanup(active, active.get("cleanup_reason", "cleanup_resume"), server)
        elif age >= self.config["max_lifetime_seconds"] or age < 0:
            self.cleanup(active, "maximum_lifetime", server)
        elif active["phase"] in ("reserved", "registering", "starting"):
            self.cleanup(active, "interrupted_mutation", server)
        elif server is None:
            if active["phase"] != "creating":
                self.cleanup(active, "worker_disappeared")
            # An uncertain create keeps its slot until the lifetime deadline.
            # A different job may use the other slot; never retry this create.
        elif server.get("status") == "off":
            self.cleanup(active, "worker_powered_off", server)
        elif active["phase"] == "booting":
            if age > self.config["startup_timeout_seconds"]:
                self.cleanup(active, "boot_timeout", server)
            elif server.get("status") == "running":
                self.start_job(active, server)
        elif active["phase"] == "running":
            self.observe_running(active, server)

    def tick(self):
        now = self.clock()
        servers, cleanup_pending = self.reconcile()
        errors = []
        for active in list(self.state["workers"]):
            try:
                self.advance(active, servers.get(active["name"]), now)
            except (ApiError, ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
                # One worker's GitHub, SSH or cleanup failure must not prevent
                # its sibling's lifetime/power-off cleanup. No new spending is
                # allowed after any failed advance.
                errors.append(error)
        if errors:
            raise errors[0]
        # Reconciliation precedes new spending. Deleting slots remain reserved;
        # existing workers may run while other owned resources are cleaned up.
        if cleanup_pending or now < self.state["next_launch_at"]:
            return
        if len(self.state["workers"]) >= self.config["max_workers"]:
            return
        for candidate in self.eligible():
            self.launch(candidate)
            if len(self.state["workers"]) >= self.config["max_workers"]:
                break


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args(argv)
    config = load_config(args.config)
    store = Store(config["state_dir"])
    if not store.acquire():
        log("already_running")
        return 0
    try:
        controller = Controller(config, Api("github", config["github_token_file"]),
                                Api("hetzner", config["hetzner_token_file"]), store, SshWorker(config))
        while True:
            try:
                controller.tick()
            except ApiError as error:
                log("api_unavailable", service=error.args[0].split()[0], status=error.status)
                if args.once:
                    return 1
            except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
                # Do not log arbitrary exception text: it can contain remote
                # output or local secrets. Fail closed and retry reconciliation.
                log("controller_error", kind=type(error).__name__)
                if args.once:
                    return 1
            if args.once:
                return 0
            time.sleep(config["poll_seconds"])
    finally:
        store.close()


if __name__ == "__main__":
    sys.exit(main())
