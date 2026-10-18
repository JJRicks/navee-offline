#!/usr/bin/env python3
"""Network-free autoscaler lifecycle and failure-path tests."""
import copy
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import urllib.parse

import controller as ci


def first_worker(controller):
    return next(iter(controller.state["workers"]), None)


def run_record(run_id=10, attempt=1):
    repo = {"id": 123, "full_name": ci.REPOSITORY}
    return {"id": run_id, "run_attempt": attempt, "repository": repo,
            "head_repository": copy.deepcopy(repo), "path": ci.WORKFLOW,
            "status": "queued", "event": "push", "actor": {"login": "JJRicks"},
            "triggering_actor": {"login": "JJRicks"}, "pull_requests": []}


def job_record(job_id=100, run_id=10, attempt=1, name="core-tests"):
    return {"id": job_id, "name": name, "status": "queued", "runner_id": None,
            "labels": sorted(ci.BASE_LABELS | {"ARM64", f"volt-run-{run_id}-{attempt}",
                                               ci.JOB_LABELS[name]})}


class FakeGithub:
    def __init__(self):
        self.runs = {}
        self.job_lists = {}
        self.runners = {}
        self.calls = []
        self.private = True
        self.fork_policy = {"run_workflows_from_fork_pull_requests": False}
        self.fail_fork_policy = False
        self.fail_get = False
        self.fail_delete = False
        self.fail_register = False
        self.register_despite_error = False
        self.next_runner_id = 300
        self.page_size = 100
        self.run_get_hook = None

    def queued(self, run=None, jobs=None):
        run = run or run_record()
        self.runs[run["id"]] = run
        self.job_lists[(run["id"], run["run_attempt"])] = jobs or [job_record()]

    def page(self, items, query, field):
        page = int(query.get("page", [1])[0])
        start = (page - 1) * self.page_size
        return {field: copy.deepcopy(items[start:start + self.page_size]), "total_count": len(items)}

    def get(self, url):
        self.calls.append(("GET", url))
        if self.fail_get:
            raise ci.ApiError("github", "GET", url, 401)
        path, _, raw_query = url.partition("?")
        query = urllib.parse.parse_qs(raw_query)
        root = f"/repos/{ci.REPOSITORY}"
        if path == root:
            return {"id": 123, "full_name": ci.REPOSITORY, "private": self.private}
        if path == root + "/actions/permissions/fork-pr-workflows-private-repos":
            if self.fail_fork_policy:
                raise ci.ApiError("github", "GET", path, 403)
            return copy.deepcopy(self.fork_policy)
        if path == root + "/actions/runs":
            return self.page([r for r in self.runs.values() if r["status"] == query["status"][0]],
                             query, "workflow_runs")
        if path == root + "/actions/runners":
            return self.page(list(self.runners.values()), query, "runners")
        parts = path[len(root):].strip("/").split("/")
        if parts[:2] == ["actions", "runs"]:
            run_id = int(parts[2])
            if len(parts) == 3:
                if self.run_get_hook:
                    self.run_get_hook(self.runs[run_id])
                return copy.deepcopy(self.runs[run_id])
            if len(parts) == 6 and parts[3] == "attempts" and parts[5] == "jobs":
                return self.page(self.job_lists[(run_id, int(parts[4]))], query, "jobs")
        raise AssertionError(f"Unexpected GitHub GET {url}")

    def post(self, path, body):
        self.calls.append(("POST", path, copy.deepcopy(body)))
        if not path.endswith("/generate-jitconfig"):
            raise AssertionError(path)
        if self.fail_register and not self.register_despite_error:
            raise ci.ApiError("github", "POST", path, 503)
        runner = {"id": self.next_runner_id, "name": body["name"]}
        self.next_runner_id += 1
        self.runners[runner["id"]] = runner
        if self.fail_register:
            raise ci.ApiError("github", "POST", path)
        return {"runner": copy.deepcopy(runner), "encoded_jit_config": "one-job-fixture"}

    def delete(self, path):
        self.calls.append(("DELETE", path))
        if self.fail_delete:
            raise ci.ApiError("github", "DELETE", path, 401)
        identity = int(path.rsplit("/", 1)[1])
        if identity not in self.runners:
            raise ci.ApiError("github", "DELETE", path, 404)
        del self.runners[identity]
        return {}


class FakeCloud:
    def __init__(self):
        self.servers = {}
        self.ips = {}
        self.calls = []
        self.fail_create = False
        self.create_despite_error = False
        self.fail_delete = False
        self.pending_delete = False
        self.fail_inventory = False
        self.fail_ip_inventory = False
        self.fail_ip_get = False
        self.fail_ip_delete = False
        self.next_id = 500
        self.image = {"type": "snapshot", "status": "available", "architecture": "arm",
                      "labels": {ci.OWNER_LABEL: "173ec832-d089-46a7-ac9a-264b165f9146", ci.ROLE_LABEL: "image"}}

    def get(self, url):
        self.calls.append(("GET", url))
        path, _, raw_query = url.partition("?")
        query = urllib.parse.parse_qs(raw_query)
        if path == "/images/1":
            return {"image": copy.deepcopy(self.image)}
        if path in ("/servers", "/primary_ips"):
            if self.fail_inventory or (path == "/primary_ips" and self.fail_ip_inventory):
                raise ci.ApiError("hetzner", "GET", path, 503)
            field = path[1:]
            values = self.servers.values() if field == "servers" else self.ips.values()
            # Deliberately return even foreign resources, testing controller's
            # independent exact ownership check rather than trusting filtering.
            items = list(values)
            page = int(query.get("page", [1])[0])
            return {field: copy.deepcopy(items[(page - 1) * 100:page * 100]),
                    "meta": {"pagination": {"next_page": page + 1 if len(items) > page * 100 else None}}}
        if path.startswith("/primary_ips/"):
            if self.fail_ip_get:
                raise ci.ApiError("hetzner", "GET", path, 503)
            identity = int(path.rsplit("/", 1)[1])
            if identity not in self.ips:
                raise ci.ApiError("hetzner", "GET", path, 404)
            return {"primary_ip": copy.deepcopy(self.ips[identity])}
        raise AssertionError(url)

    def post(self, path, body):
        self.calls.append(("POST", path, copy.deepcopy(body)))
        if path != "/servers":
            raise AssertionError(path)
        if self.fail_create and not self.create_despite_error:
            raise ci.ApiError("hetzner", "POST", path)
        identity = self.next_id
        self.next_id += 1
        ip_id = identity + 1000
        server = {"id": identity, "name": body["name"], "labels": body["labels"],
                  "status": "running", "public_net": {"ipv4": {"id": ip_id, "ip": "192.0.2.10"}}}
        self.servers[identity] = copy.deepcopy(server)
        self.ips[ip_id] = {"id": ip_id, "assignee_id": identity, "labels": {}, "auto_delete": False}
        if self.fail_create:
            raise ci.ApiError("hetzner", "POST", path)
        return {"server": server}

    def put(self, path, body):
        self.calls.append(("PUT", path, copy.deepcopy(body)))
        self.ips[int(path.rsplit("/", 1)[1])].update(body)
        return {}

    def delete(self, path):
        self.calls.append(("DELETE", path))
        if self.fail_delete or (path.startswith("/primary_ips/") and self.fail_ip_delete):
            raise ci.ApiError("hetzner", "DELETE", path, 503)
        identity = int(path.rsplit("/", 1)[1])
        if path.startswith("/servers/"):
            if self.pending_delete:
                return {}
            server = self.servers.pop(identity, None)
            if server:
                ip_id = server["public_net"]["ipv4"]["id"]
                if ip_id in self.ips:
                    if self.ips[ip_id]["auto_delete"]:
                        del self.ips[ip_id]
                    else:
                        self.ips[ip_id]["assignee_id"] = None
        elif path.startswith("/primary_ips/"):
            if self.ips.get(identity, {}).get("assignee_id") is not None:
                raise AssertionError("Tried deleting assigned IP")
            self.ips.pop(identity, None)
        else:
            raise AssertionError(path)
        return {}


class FakeWorker:
    def __init__(self):
        self.prepared = []
        self.started = []
        self.forgotten = []
        self.is_ready = True
        self.start_error = False

    def prepare(self, name):
        self.prepared.append(name)
        return "#cloud-config\n{\"ssh_keys\":\"fixture-only\"}"

    def ready(self, active):
        return self.is_ready

    def start(self, active, jit):
        self.started.append((active["name"], jit))
        if self.start_error:
            raise subprocess.TimeoutExpired("ssh", 30)

    def forget(self, name):
        self.forgotten.append(name)


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = {
            "deployment_id": "173ec832-d089-46a7-ac9a-264b165f9146", "repository": ci.REPOSITORY,
            "state_dir": self.temp.name, "github_token_file": self.temp.name + "/github",
            "hetzner_token_file": self.temp.name + "/cloud", "ssh_private_key_file": self.temp.name + "/ssh",
            "snapshot_id": 1, "server_type": "cax11", "location": "fsn1", "firewall_id": 2,
            "ssh_key_id": 3, "allowed_actors": {"jjricks", "ollies5"}, "poll_seconds": 20,
            "max_lifetime_seconds": 1800, "max_attempts_per_job": 2, "cooldown_seconds": 60,
            "startup_timeout_seconds": 600, "allow_public_repository": False, "runner_arch": "ARM64",
            "max_launches_per_hour": 12, "max_launches_per_day": 60, "max_workers": 1}
        self.gh, self.cloud, self.worker = FakeGithub(), FakeCloud(), FakeWorker()
        self.now = 1000
        self.store = ci.Store(self.temp.name)
        self.controller = self.make_controller()

    def make_controller(self):
        return ci.Controller(self.config, self.gh, self.cloud, self.store, self.worker, lambda: self.now)

    def launch(self, jobs=None):
        self.gh.queued(jobs=jobs)
        self.controller.tick()
        self.assertEqual(first_worker(self.controller)["phase"], "booting")

    def running(self, jobs=None):
        self.launch(jobs)
        self.controller.tick()
        self.assertEqual(first_worker(self.controller)["phase"], "running")

    def posts(self):
        return [call for call in self.cloud.calls if call[0] == "POST"]

    def test_no_queue_costs_nothing(self):
        self.controller.tick()
        self.assertFalse(self.posts())
        self.assertIsNone(first_worker(self.controller))
        self.assertFalse(self.worker.prepared)

    def test_full_job_then_delete_keeps_clean_image(self):
        self.running()
        active = first_worker(self.controller)
        self.assertEqual(len(self.posts()), 1)
        payload = self.posts()[0][2]
        self.assertEqual(payload["image"], 1)
        self.assertEqual(payload["server_type"], "cax11")
        self.assertNotIn("token", payload["user_data"])
        self.assertEqual(self.worker.started, [(active["name"], "one-job-fixture")])
        self.gh.job_lists[(10, 1)][0].update(status="completed", runner_id=active["runner_id"])
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.controller.tick()
        self.assertIsNone(first_worker(self.controller))
        self.assertFalse(self.cloud.ips)
        self.assertFalse(self.gh.runners)
        self.assertFalse(any(call[0] != "GET" and "images" in call[1] for call in self.cloud.calls))
        jit_call = next(call for call in self.gh.calls if call[0] == "POST")
        self.assertIn("ARM64", jit_call[2]["labels"])

    def test_exhausted_job_cannot_receive_sibling_worker(self):
        core, web = job_record(100), job_record(101, name="web-setup")
        self.controller.state["attempts"]["10:1:100"] = 2
        self.running([core, web])
        active = first_worker(self.controller)
        self.assertEqual(active["job_id"], 101)
        registration = next(call[2] for call in self.gh.calls if call[0] == "POST")
        runner_labels = set(registration["labels"])
        # GitHub schedules when every requested label is on the runner. The
        # exhausted core job must not match a runner provisioned for web setup.
        self.assertFalse(set(core["labels"]).issubset(runner_labels))
        self.assertTrue(set(web["labels"]).issubset(runner_labels))
        self.gh.job_lists[(10, 1)][1].update(status="in_progress", runner_id=active["runner_id"])
        self.controller.tick()
        self.assertEqual(active["assigned_job_id"], 101)
        self.assertEqual(self.controller.state["attempts"]["10:1:100"], 2)
        self.assertEqual(self.controller.state["attempts"]["10:1:101"], 1)
        self.assertEqual(len(self.cloud.servers), 1)
        self.gh.job_lists[(10, 1)][1]["status"] = "completed"
        self.controller.tick()
        self.controller.tick()
        self.now += 61
        self.controller.tick()
        self.assertIsNone(first_worker(self.controller))
        self.assertEqual(len(self.posts()), 1)

    def test_unexpected_assignment_deletes_without_refunding_budget(self):
        self.running([job_record(100), job_record(101, name="web-setup")])
        active = first_worker(self.controller)
        self.gh.job_lists[(10, 1)][1].update(status="in_progress", runner_id=active["runner_id"])
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertEqual(active["cleanup_reason"], "unexpected_job_assignment")
        self.assertEqual(self.controller.state["attempts"], {"10:1:100": 1})

    def test_duplicate_job_names_never_register_shared_labels(self):
        self.gh.queued(jobs=[job_record(100), job_record(101)])
        self.controller.tick()
        self.assertFalse(self.posts())

    def test_workflow_requests_the_controller_job_labels(self):
        workflow = (Path(__file__).resolve().parents[2] / ci.WORKFLOW).read_text()
        blocks = dict(re.findall(r"^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)", workflow, re.M | re.S))
        for name in ci.JOB_LABELS:
            with self.subTest(job=name):
                expression = re.search(r"format\('([^']+)', github.run_id, github.run_attempt\)", blocks[name])
                self.assertIsNotNone(expression)
                requested = json.loads(expression.group(1).format(10, 1))
                self.assertEqual(set(requested), set(self.controller.labels(10, 1, name)))

    def test_trusted_same_repo_pr_needs_no_extra_api_scope(self):
        run = run_record()
        run["event"] = "pull_request"
        run["pull_requests"] = [{"number": 60, "head": {"repo": {"id": 123}},
                                 "base": {"repo": {"id": 123}}}]
        self.gh.queued(run)
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.assertFalse(any("/pulls/" in call[1] for call in self.gh.calls))

    def test_trusted_manual_dispatch_uses_same_repo_and_actor_checks(self):
        run = run_record()
        run["event"] = "workflow_dispatch"
        self.gh.queued(run)
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.assertTrue(self.controller.allowed_run(run))
        for replacement in ({"head_repository": {"id": 999, "full_name": "outsider/fork"}},
                            {"actor": {"login": "outsider"}},
                            {"triggering_actor": {"login": "outsider"}},
                            {"path": ".github/workflows/untrusted.yml"}):
            with self.subTest(replacement=replacement):
                self.assertFalse(self.controller.allowed_run({**run, **replacement}))

    def test_workflow_path_accepts_only_optional_ref_suffix(self):
        run = run_record()
        for path in (ci.WORKFLOW, ci.WORKFLOW + "@main", ci.WORKFLOW + "@refs/heads/codex/ci"):
            with self.subTest(path=path):
                self.assertTrue(self.controller.allowed_run({**run, "path": path}))
        for path in (None, ci.WORKFLOW + "@", ci.WORKFLOW + "/extra@main",
                     "someone/else/" + ci.WORKFLOW + "@main", ".github/workflows/other.yml@main"):
            with self.subTest(path=path):
                self.assertFalse(self.controller.allowed_run({**run, "path": path}))

    def test_disallowed_runs_do_not_provision(self):
        mutations = [
            lambda r: r.update(path=".github/workflows/other.yml"),
            lambda r: r.update(event="pull_request_target"),
            lambda r: r.update(actor={"login": "stranger"}),
            lambda r: r.update(actor=None),
            lambda r: r.update(triggering_actor={"login": "stranger"}),
            lambda r: r.update(head_repository={"id": 999, "full_name": ci.REPOSITORY}),
            lambda r: r.update(head_repository=None),
            lambda r: r.update(repository={"id": 123, "full_name": "someone/else"}),
            lambda r: r.update(event="pull_request", pull_requests=[]),
            lambda r: r.update(event="pull_request", pull_requests=None),
            lambda r: r.update(event="pull_request", pull_requests=[None]),
            lambda r: r.update(event="pull_request", pull_requests=[{"number": 60, "head": {"repo": None}}]),
            lambda r: r.update(event="pull_request", pull_requests=[{"number": 60,
                "head": {"repo": {"id": 999}}, "base": {"repo": {"id": 123}}}]),
            lambda r: r.update(event="pull_request", pull_requests=[{"number": 60}, {"number": 61}]),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                run = run_record()
                mutation(run)
                self.gh.queued(run)
                self.controller.tick()
                self.assertFalse(self.posts())

    def test_public_repository_pauses_by_default(self):
        self.gh.private = False
        self.gh.queued()
        self.controller.tick()
        self.assertFalse(self.posts())

    def test_fork_workflow_policy_must_be_explicitly_disabled(self):
        self.gh.queued()
        for policy in ({"run_workflows_from_fork_pull_requests": True}, {},
                       {"run_workflows_from_fork_pull_requests": None},
                       {"run_workflows_from_fork_pull_requests": 0}):
            with self.subTest(policy=policy):
                self.gh.fork_policy = policy
                self.controller.tick()
                self.assertFalse(self.posts())
        self.gh.fail_fork_policy = True
        with self.assertRaises(ci.ApiError):
            self.controller.tick()
        self.assertFalse(self.posts())

    def test_fork_policy_rechecked_before_create_and_registration(self):
        self.gh.queued()
        candidate = self.controller.eligible()[0]
        self.gh.fork_policy["run_workflows_from_fork_pull_requests"] = True
        self.controller.launch(candidate)
        self.assertFalse(self.posts())
        self.gh.fork_policy["run_workflows_from_fork_pull_requests"] = False
        self.controller.launch(candidate)
        self.gh.fork_policy["run_workflows_from_fork_pull_requests"] = True
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertFalse(self.worker.started)
        self.assertFalse(any(call[0] == "POST" for call in self.gh.calls))

    def test_labels_must_match_exact_run_and_attempt(self):
        for labels in (["ubuntu-latest"], list(ci.BASE_LABELS),
                       list(ci.BASE_LABELS | {"ARM64", "volt-run-10-2", "volt-job-core-tests"}),
                       list(ci.BASE_LABELS | {"ARM64", "volt-run-10-1"}),
                       list(ci.BASE_LABELS | {"ARM64", "volt-run-10-1", "volt-job-web-setup"}),
                       list(ci.BASE_LABELS | {"ARM64", "volt-run-10-1", "volt-job-core-tests", "other"})):
            with self.subTest(labels=labels):
                job = job_record()
                job["labels"] = labels
                self.gh.queued(jobs=[job])
                self.controller.tick()
                self.assertFalse(self.posts())

    def test_cancel_race_during_last_queue_check(self):
        self.gh.queued()
        self.gh.run_get_hook = lambda r: r.update(status="completed")
        self.controller.tick()
        self.assertFalse(self.posts())

    def test_new_attempt_race_during_last_queue_check(self):
        self.gh.queued()
        self.gh.run_get_hook = lambda r: r.update(run_attempt=2)
        self.controller.tick()
        self.assertFalse(self.posts())

    def test_cancel_while_booting_deletes_without_registration(self):
        self.launch()
        self.gh.runs[10]["status"] = "completed"
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertFalse(self.worker.started)

    def test_unknown_create_recovers_exact_owned_server(self):
        self.cloud.fail_create = True
        self.cloud.create_despite_error = True
        self.gh.queued()
        self.controller.tick()
        self.assertEqual(first_worker(self.controller)["phase"], "creating")
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertEqual(first_worker(self.controller)["phase"], "running")
        self.assertEqual(len(self.posts()), 1)

    def test_unknown_create_without_server_does_not_retry_blindly(self):
        self.cloud.fail_create = True
        self.gh.queued()
        self.controller.tick()
        self.controller = self.make_controller()
        for step in range(10):
            self.now += 20
            self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.now += 1800
        self.controller.tick()
        self.assertIsNone(first_worker(self.controller))
        self.assertEqual(len(self.posts()), 1)

    def test_maximum_lifetime_still_deletes_when_github_auth_fails(self):
        self.running()
        self.gh.fail_get = True
        self.gh.fail_delete = True
        self.now += 1801
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertEqual(first_worker(self.controller)["phase"], "deleting")
        self.controller.tick()
        self.assertFalse(self.cloud.ips)
        self.assertEqual(len(self.posts()), 1)
        self.gh.fail_delete = False
        self.controller.tick()
        self.assertIsNone(first_worker(self.controller))

    def test_auth_failure_before_deadline_never_launches_more(self):
        self.running()
        self.gh.fail_get = True
        with self.assertRaises(ci.ApiError):
            self.controller.tick()
        self.assertEqual(len(self.cloud.servers), 1)
        self.assertEqual(len(self.posts()), 1)

    def test_boot_deadline(self):
        self.launch()
        self.worker.is_ready = False
        self.now += 601
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertFalse(self.worker.started)

    def test_power_off_removes_server_before_github_reads(self):
        self.running()
        next(iter(self.cloud.servers.values()))["status"] = "off"
        self.gh.fail_get = True
        self.controller.tick()
        self.assertFalse(self.cloud.servers)

    def test_ip_failures_allow_server_cleanup_but_block_replacement(self):
        for failure in ("fail_ip_inventory", "fail_ip_get", "fail_ip_delete"):
            for reason in ("expired", "off", "deleting"):
                with self.subTest(failure=failure, reason=reason), tempfile.TemporaryDirectory() as directory:
                    config = {**self.config, "state_dir": directory}
                    github, cloud, worker = FakeGithub(), FakeCloud(), FakeWorker()
                    now = [1000]
                    controller = ci.Controller(config, github, cloud, ci.Store(directory), worker, lambda: now[0])
                    github.queued()
                    controller.tick()
                    controller.tick()
                    active = first_worker(controller)
                    self.assertEqual(active["phase"], "running")
                    server_id = active["server_id"]
                    # An unrelated, owned orphan IP can also fail deletion.
                    cloud.ips[2000] = {"id": 2000, "assignee_id": None, "auto_delete": False,
                        "labels": {ci.OWNER_LABEL: config["deployment_id"], ci.ROLE_LABEL: "worker"}}
                    setattr(cloud, failure, True)
                    if reason == "expired":
                        now[0] += 1801
                    elif reason == "off":
                        cloud.servers[server_id]["status"] = "off"
                    else:
                        active["phase"] = "deleting"
                        controller.save()
                        controller = ci.Controller(config, github, cloud, ci.Store(directory), worker, lambda: now[0])
                    controller.tick()
                    self.assertNotIn(server_id, cloud.servers)
                    self.assertIn(("DELETE", f"/servers/{server_id}"), cloud.calls)
                    self.assertFalse(github.runners)
                    controller.tick()
                    self.assertIsNone(first_worker(controller))
                    now[0] += 61
                    controller.tick()
                    self.assertEqual(sum(call[0] == "POST" for call in cloud.calls), 1)
                    # Pending cleanup must survive restart and resume before a
                    # fresh worker is allowed to incur another server charge.
                    controller = ci.Controller(config, github, cloud, ci.Store(directory), worker, lambda: now[0])
                    controller.tick()
                    self.assertFalse(cloud.servers)
                    setattr(cloud, failure, False)
                    controller.tick()
                    self.assertEqual(sum(call[0] == "POST" for call in cloud.calls), 2)
                    self.assertNotIn(2000, cloud.ips)

    def test_pending_assigned_ip_blocks_new_worker_until_reconciled(self):
        self.cloud.next_id = 501
        self.controller.state["pending_ips"]["1500"] = {"server_id": 500, "worker": "volt-ci-old"}
        self.cloud.ips[1500] = {"id": 1500, "assignee_id": 500, "labels": {}, "auto_delete": False}
        self.gh.queued()
        self.controller.tick()
        self.assertFalse(self.posts())
        self.cloud.ips[1500]["assignee_id"] = None
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.assertNotIn(1500, self.cloud.ips)

    def test_orphan_owned_resources_deleted_unrelated_untouched(self):
        own = self.config["deployment_id"]
        self.cloud.servers[500] = {"id": 500, "name": "volt-ci-orphan", "labels": {
            ci.OWNER_LABEL: own, ci.ROLE_LABEL: "worker"},
            "status": "running", "public_net": {"ipv4": {"id": 1500, "ip": "192.0.2.10"}}}
        self.cloud.servers[501] = {"id": 501, "name": "unrelated", "labels": {}, "status": "running",
            "public_net": {"ipv4": {"id": 1501, "ip": "192.0.2.11"}}}
        self.cloud.ips[1500] = {"id": 1500, "assignee_id": 500, "labels": {}, "auto_delete": False}
        self.cloud.ips[1501] = {"id": 1501, "assignee_id": 501, "labels": {}, "auto_delete": False}
        self.gh.fail_get = True
        self.controller.tick()
        self.assertEqual(set(self.cloud.servers), {501})
        self.assertEqual(set(self.cloud.ips), {1501})

    def test_unknown_registration_is_cleaned_by_exact_name(self):
        self.launch()
        self.gh.fail_register = True
        self.gh.register_despite_error = True
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertFalse(self.gh.runners)
        self.assertFalse(self.worker.started)

    def test_interrupted_registration_restart_does_not_register_again(self):
        self.launch()
        active = first_worker(self.controller)
        active.update(phase="registering", registration_started=True)
        self.gh.runners[888] = {"id": 888, "name": active["name"]}
        self.gh.runners[999] = {"id": 999, "name": "unrelated-runner"}
        self.controller.save()
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertEqual(set(self.gh.runners), {999})
        self.assertFalse(any(call[0] == "POST" for call in self.gh.calls))

    def test_start_failure_deletes_and_never_replays_jit(self):
        self.launch()
        self.worker.start_error = True
        self.controller.tick()
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.assertEqual(len(self.worker.started), 1)
        self.assertFalse(self.gh.runners)

    def test_pending_cloud_delete_blocks_replacement(self):
        self.running()
        self.cloud.pending_delete = True
        self.now += 1801
        self.controller.tick()
        for _ in range(3):
            self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.assertEqual(len(self.cloud.servers), 1)

    def test_failed_cloud_delete_retries_durable_cleanup(self):
        self.running()
        self.cloud.fail_delete = True
        self.now += 1801
        with self.assertRaises(ci.ApiError):
            self.controller.tick()
        self.assertEqual(first_worker(self.controller)["phase"], "deleting")
        self.cloud.fail_delete = False
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.controller.tick()
        self.assertIsNone(first_worker(self.controller))

    def test_exhausted_attempt_budget_and_cooldown(self):
        self.gh.queued()
        self.controller.state["attempts"]["10:1:100"] = 2
        self.controller.tick()
        self.assertFalse(self.posts())
        self.controller.state["attempts"]["10:1:100"] = 0
        self.controller.state["next_launch_at"] = self.now + 60
        self.controller.tick()
        self.assertFalse(self.posts())
        self.now += 61
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)

    def test_all_pages_of_queue_and_jobs_are_examined(self):
        self.gh.page_size = 1
        bad = run_record(9)
        bad["actor"] = {"login": "not-allowed"}
        self.gh.queued(bad, [job_record(99, 9)])
        ignored = job_record(100)
        ignored["labels"] = ["ubuntu-latest"]
        self.gh.queued(run_record(), [ignored, job_record(101, name="web-setup")])
        self.controller.tick()
        self.assertEqual(first_worker(self.controller)["job_id"], 101)
        self.assertTrue(any("page=2" in call[1] for call in self.gh.calls))

    def test_cloud_inventory_failure_never_creates(self):
        self.gh.queued()
        self.cloud.fail_inventory = True
        with self.assertRaises(ci.ApiError):
            self.controller.tick()
        self.assertFalse(self.posts())

    def test_reassigned_ip_never_deleted(self):
        self.controller.state["pending_ips"]["1500"] = {"server_id": 500, "worker": "volt-ci-old"}
        self.cloud.ips[1500] = {"id": 1500, "assignee_id": 999, "labels": {}, "auto_delete": False}
        self.controller.tick()
        self.assertIn(1500, self.cloud.ips)
        self.assertNotIn("1500", self.controller.state["pending_ips"])

    def test_new_attempt_while_running_destroys_old_worker(self):
        self.running()
        self.gh.runs[10]["run_attempt"] = 2
        self.controller.tick()
        self.assertFalse(self.cloud.servers)

    def test_no_assignment_times_out(self):
        self.running()
        self.now += 181
        self.controller.tick()
        self.assertFalse(self.cloud.servers)

    def test_backwards_clock_fails_closed(self):
        self.running()
        self.now -= 1
        self.controller.tick()
        self.assertFalse(self.cloud.servers)

    def test_state_is_scoped_to_deployment(self):
        self.config["deployment_id"] = "473ec832-d089-46a7-ac9a-264b165f9146"
        with self.assertRaises(ValueError):
            self.make_controller()

    def test_builder_with_same_deployment_is_untouched(self):
        self.cloud.servers[900] = {"id": 900, "name": "builder", "labels": {
            ci.OWNER_LABEL: self.config["deployment_id"], ci.ROLE_LABEL: "builder"},
            "status": "running", "public_net": {"ipv4": {"id": 1900, "ip": "192.0.2.19"}}}
        self.cloud.ips[1900] = {"id": 1900, "assignee_id": 900, "auto_delete": False,
                               "labels": {ci.OWNER_LABEL: self.config["deployment_id"], ci.ROLE_LABEL: "builder"}}
        self.controller.tick()
        self.assertIn(900, self.cloud.servers)
        self.assertIn(1900, self.cloud.ips)
        self.assertFalse(any(call[0] == "DELETE" for call in self.cloud.calls))

    def test_snapshot_must_have_expected_owner_role_and_architecture(self):
        original = copy.deepcopy(self.cloud.image)
        bad_images = [
            {**original, "type": "system"}, {**original, "status": "creating"},
            {**original, "architecture": "x86"}, {**original, "labels": {}},
            {**original, "labels": {ci.OWNER_LABEL: self.config["deployment_id"], ci.ROLE_LABEL: "worker"}},
        ]
        self.gh.queued()
        for image in bad_images:
            with self.subTest(image=image):
                self.cloud.image = image
                with self.assertRaises(ValueError):
                    self.controller.tick()
                self.assertFalse(self.posts())
                self.assertFalse(self.worker.prepared)

    def test_hourly_launch_limit_survives_restart(self):
        self.gh.queued()
        self.controller.state["launch_history"] = [self.now - 100] * 12
        self.controller.save()
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertFalse(self.posts())
        self.now += 3601
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)

    def test_daily_launch_limit_with_no_recent_hour(self):
        self.gh.queued()
        self.controller.state["launch_history"] = [self.now - 7200] * 60
        self.controller.tick()
        self.assertFalse(self.posts())

    def test_ambiguous_create_is_counted_in_global_rate(self):
        self.gh.queued()
        self.cloud.fail_create = True
        self.controller.tick()
        self.assertEqual(self.store.load()["launch_history"], [self.now])

    def test_future_launch_timestamp_blocks_new_spending(self):
        self.gh.queued()
        self.controller.state["launch_history"] = [self.now + 1]
        self.controller.tick()
        self.assertFalse(self.posts())

    def test_loaded_config_enforces_cost_and_scope_limits(self):
        source = {key: value for key, value in self.config.items() if key != "allowed_actors"}
        path = Path(self.temp.name) / "config.json"
        path.write_text(json.dumps(source))
        self.assertEqual(ci.load_config(path)["runner_arch"], "ARM64")
        path.write_text(json.dumps({key: value for key, value in source.items() if key != "max_workers"}))
        self.assertEqual(ci.load_config(path)["max_workers"], 1)
        path.write_text(json.dumps({**source, "max_workers": 2}))
        self.assertEqual(ci.load_config(path)["max_workers"], 2)
        for key, value in (("max_launches_per_hour", 13), ("max_launches_per_day", 61),
                           ("max_lifetime_seconds", 1801), ("max_attempts_per_job", 4),
                           ("repository", "another/repo"), ("runner_arch", "ARM32"),
                           ("max_workers", 3), ("max_workers", 0), ("max_workers", True),
                           ("snapshot_id", True), ("state_dir", "relative/path")):
            with self.subTest(key=key):
                path.write_text(json.dumps({**source, key: value}))
                with self.assertRaises(ValueError):
                    ci.load_config(path)

    def test_ip_repurposed_as_builder_is_never_deleted(self):
        self.controller.state["pending_ips"]["1500"] = {"server_id": 500, "worker": "volt-ci-old"}
        self.cloud.ips[1500] = {"id": 1500, "assignee_id": None, "auto_delete": False,
            "labels": {ci.OWNER_LABEL: self.config["deployment_id"], ci.ROLE_LABEL: "builder"}}
        self.controller.tick()
        self.assertIn(1500, self.cloud.ips)


class ParallelControllerTests(unittest.TestCase):
    setUp = ControllerTests.setUp
    make_controller = ControllerTests.make_controller
    posts = ControllerTests.posts

    def queue_pair(self):
        self.config["max_workers"] = 2
        self.gh.queued(jobs=[job_record(100), job_record(101, name="web-setup")])

    def test_two_jobs_start_together_and_return_to_zero(self):
        self.queue_pair()
        self.controller.tick()
        self.assertEqual(len(self.cloud.servers), 2)
        self.assertEqual({worker["job_id"] for worker in self.controller.state["workers"]}, {100, 101})
        self.controller.tick()
        self.assertEqual(len(self.worker.started), 2)
        self.assertEqual({worker["phase"] for worker in self.controller.state["workers"]}, {"running"})
        for job, worker in zip(self.gh.job_lists[(10, 1)], self.controller.state["workers"]):
            job.update(status="completed", runner_id=worker["runner_id"])
        self.controller.tick()
        self.controller.tick()
        self.assertEqual(self.controller.state["workers"], [])
        self.assertFalse(self.cloud.servers)
        self.assertFalse(self.cloud.ips)
        self.assertFalse(self.gh.runners)
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual(len(set(self.worker.forgotten)), 2)

    def test_active_job_is_not_duplicated_while_github_still_reports_queued(self):
        self.config["max_workers"] = 2
        self.gh.queued()
        self.controller.tick()
        self.controller = self.make_controller()
        self.controller.tick()
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.assertEqual(len(self.controller.state["workers"]), 1)
        self.assertEqual(self.controller.state["attempts"], {"10:1:100": 1})

    def test_healthy_assigned_ip_does_not_block_spare_slot(self):
        self.config["max_workers"] = 2
        self.gh.queued()
        self.controller.tick()
        self.controller.tick()
        self.gh.job_lists[(10, 1)].append(job_record(101, name="web-setup"))
        self.controller.tick()
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual(len(self.cloud.servers), 2)

    def test_more_queued_runs_cannot_exceed_two_including_direct_launch(self):
        self.queue_pair()
        self.gh.queued(run_record(11), [job_record(110, 11)])
        extra = self.controller.eligible()[-1]
        self.controller.tick()
        self.controller.launch(extra)
        self.controller.tick()
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual({worker["run_id"] for worker in self.controller.state["workers"]}, {10})

    def test_uncertain_create_reserves_both_slots_across_restart(self):
        self.queue_pair()
        self.cloud.fail_create = True
        self.controller.tick()
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual({worker["phase"] for worker in self.controller.state["workers"]}, {"creating"})
        self.gh.queued(run_record(11), [job_record(110, 11)])
        self.controller = self.make_controller()
        for _ in range(5):
            self.now += 20
            self.controller.tick()
        self.assertEqual(len(self.posts()), 2)
        self.now += 1800
        self.controller.tick()
        self.assertEqual(self.controller.state["workers"], [])
        self.assertEqual(len(self.posts()), 2)

    def test_unknown_created_pair_recovers_exact_workers_without_replaying_create(self):
        self.queue_pair()
        self.cloud.fail_create = True
        self.cloud.create_despite_error = True
        self.controller.tick()
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual(len(self.worker.started), 2)
        self.assertEqual({worker["phase"] for worker in self.controller.state["workers"]}, {"running"})
        self.assertEqual(len({worker["server_id"] for worker in self.controller.state["workers"]}), 2)

    def test_interrupted_registration_cleans_only_affected_worker(self):
        self.queue_pair()
        self.controller.tick()
        first, second = self.controller.state["workers"]
        first.update(phase="registering", registration_started=True)
        self.gh.runners[888] = {"id": 888, "name": first["name"]}
        self.controller.save()
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertNotIn(first["server_id"], self.cloud.servers)
        self.assertIn(second["server_id"], self.cloud.servers)
        self.assertNotIn(888, self.gh.runners)
        self.assertEqual(len(self.worker.started), 1)
        self.assertEqual(self.worker.started[0][0], second["name"])
        self.assertEqual(len(self.posts()), 2)

    def test_one_github_failure_does_not_block_other_worker_expiry(self):
        self.queue_pair()
        self.controller.tick()
        self.controller.tick()
        first, second = self.controller.state["workers"]
        first["created_at"] = self.now + 1000
        self.now += 1801
        self.gh.fail_get = True
        with self.assertRaises(ci.ApiError):
            self.controller.tick()
        self.assertIn(first["server_id"], self.cloud.servers)
        self.assertNotIn(second["server_id"], self.cloud.servers)
        self.assertEqual(second["phase"], "deleting")
        self.assertEqual(len(self.posts()), 2)

    def test_ip_outage_does_not_block_either_worker_expiry(self):
        self.queue_pair()
        self.controller.tick()
        self.controller.tick()
        self.cloud.fail_ip_inventory = True
        self.cloud.fail_ip_get = True
        self.now += 1801
        self.controller.tick()
        self.assertFalse(self.cloud.servers)
        self.controller.tick()
        self.now += 61
        self.controller.tick()
        self.assertFalse(self.controller.state["workers"])
        self.assertEqual(len(self.posts()), 2)

    def test_pending_delete_reserves_slot_while_sibling_continues(self):
        self.queue_pair()
        self.controller.tick()
        self.controller.tick()
        first, second = self.controller.state["workers"]
        self.gh.job_lists[(10, 1)][0].update(status="completed", runner_id=first["runner_id"])
        self.gh.job_lists[(10, 1)][1].update(status="in_progress", runner_id=second["runner_id"])
        self.gh.queued(run_record(11), [job_record(110, 11)])
        self.cloud.pending_delete = True
        for _ in range(3):
            self.controller.tick()
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual(len(self.cloud.servers), 2)
        self.assertEqual(first["phase"], "deleting")
        self.assertEqual(second["phase"], "running")

    def test_finished_slot_refills_after_cleanup_while_sibling_runs(self):
        self.queue_pair()
        self.controller.tick()
        self.controller.tick()
        first, second = self.controller.state["workers"]
        self.gh.job_lists[(10, 1)][0].update(status="completed", runner_id=first["runner_id"])
        self.gh.job_lists[(10, 1)][1].update(status="in_progress", runner_id=second["runner_id"])
        self.gh.queued(run_record(11), [job_record(110, 11)])
        self.controller.tick()
        self.controller.tick()
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual(len(self.cloud.servers), 1)
        self.now += 61
        self.controller.tick()
        self.assertEqual(len(self.posts()), 3)
        self.assertEqual(len(self.cloud.servers), 2)
        self.assertEqual({worker["job_id"] for worker in self.controller.state["workers"]}, {101, 110})
        self.controller.tick()
        self.assertEqual(len(self.worker.started), 3)
        self.assertIn(second["runner_id"], self.gh.runners)

    def test_hourly_budget_is_global_not_per_slot(self):
        self.queue_pair()
        self.controller.state["launch_history"] = [self.now - 100] * 11
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.assertEqual(len(self.controller.state["launch_history"]), 12)

    def test_lowering_capacity_drains_existing_workers_without_replacement(self):
        self.queue_pair()
        self.controller.tick()
        self.controller.tick()
        self.config["max_workers"] = 1
        self.controller = self.make_controller()
        self.controller.tick()
        self.assertEqual(len(self.cloud.servers), 2)
        self.assertEqual(len(self.posts()), 2)
        self.assertEqual(len(self.worker.started), 2)

    def test_single_worker_state_migrates_without_losing_cleanup_or_budgets(self):
        self.gh.queued()
        self.controller.tick()
        self.controller.tick()
        old = copy.deepcopy(self.controller.state)
        worker = old.pop("workers")[0]
        old.update(version=1, active=worker)
        old["next_launch_at"] = self.now + 20
        self.store.save(old)
        self.config["max_workers"] = 2
        self.controller = self.make_controller()
        migrated = self.store.load()
        self.assertEqual(migrated["version"], 2)
        self.assertNotIn("active", migrated)
        self.assertEqual(migrated["workers"], [worker])
        for field in ("attempts", "pending_ips", "launch_history", "next_launch_at", "deployment_id"):
            self.assertEqual(migrated[field], old[field])
        self.controller.tick()
        self.assertEqual(len(self.posts()), 1)
        self.assertEqual(len(self.worker.started), 1)

    def test_invalid_duplicate_pool_state_is_not_overwritten(self):
        self.queue_pair()
        self.controller.tick()
        original = copy.deepcopy(self.controller.state)
        invalid_states = [
            {**original, "workers": [original["workers"][0]] * 2},
            {**original, "workers": original["workers"] * 2},
            {**original, "workers": [None]},
        ]
        for state in invalid_states:
            with self.subTest(state=state):
                self.store.save(state)
                before = self.store.path.read_bytes()
                with self.assertRaises(ValueError):
                    self.make_controller()
                self.assertEqual(self.store.path.read_bytes(), before)


class StoreAndConfigTests(unittest.TestCase):
    def test_exclusive_lock_and_atomic_json(self):
        with tempfile.TemporaryDirectory() as temp:
            first, second = ci.Store(temp), ci.Store(temp)
            try:
                self.assertTrue(first.acquire())
                self.assertFalse(second.acquire())
                state = {"version": 2, "workers": [], "attempts": {}, "next_launch_at": 0}
                first.save(state)
                self.assertEqual(second.load(), state)
                self.assertEqual((Path(temp) / "state.json").stat().st_mode & 0o777, 0o600)
                self.assertFalse((Path(temp) / "state.json.tmp").exists())
                first.close()
                self.assertTrue(second.acquire())
            finally:
                first.close()
                second.close()

    def test_corrupt_state_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "state.json"
            path.write_text("{broken")
            with self.assertRaises(ValueError):
                ci.Store(temp).load()
            self.assertEqual(path.read_text(), "{broken")

    def test_pagination_failure_does_not_return_partial_data(self):
        class Broken:
            def get(self, path):
                return {"runners": [], "total_count": 1}
        with self.assertRaises(ValueError):
            ci.paginated(Broken(), "/runners", "runners")

    def test_ssh_uses_pinned_identity_and_not_token_arguments(self):
        with tempfile.TemporaryDirectory() as temp:
            worker = ci.SshWorker({"state_dir": temp, "ssh_private_key_file": "/controller/authorize"})
            active = {"name": "volt-ci-fixture", "ip": "192.0.2.1"}
            command = worker.command(active, "/opt/volt-ci/start-job")
            self.assertIn("StrictHostKeyChecking=yes", command)
            self.assertIn("HostKeyAlias=volt-ci-fixture", command)
            self.assertIn("IdentitiesOnly=yes", command)
            self.assertNotIn("one-job-fixture", " ".join(command))
            with self.assertRaises(ValueError):
                worker.folder("../../foreign")


if __name__ == "__main__":
    unittest.main()
