# Disposable CI workers

The existing Java/Python tests and web setup tests/build run on a small ARM Linux
worker. Android SDK builds, emulator tests, release signing and deployment are
not part of this worker's job.

A lightweight controller runs separately on an existing always-on server. It
polls the GitHub Actions queue, restores a clean tools snapshot, supplies a
single-job runner registration over pinned SSH, and deletes the worker after
the job finishes. Up to two jobs can run together, each on its own fresh machine.
With no queued work, only
the clean snapshot remains in the Hetzner project.

## Boundaries

- Cloud and repository-admin credentials stay on the controller. Workers never
  receive either credential, deployment access or release signing keys.
- The GitHub controller token is limited to this repository: **Actions: read**
  and **Administration: read/write**. Metadata access is included. Renew it
  before its configured expiration.
- Workers run as an unprivileged user with no sudo or Docker socket. The job
  token has read-only contents access; checkout does not persist credentials.
- Each worker gets a fresh SSH host key through cloud-init. SSH requires that
  exact key. The worker firewall permits SSH only from the controller.
- Queue selection checks repository, workflow, actor, run attempt and same-repo
  PR provenance. Run and job labels route each worker to its intended queued job
  and keep retry budgets separate. **Labels are not an authorization boundary**:
  a workflow requesting only `self-hosted` can match any of these workers.
- The worker policy requires a private repository with only the trusted
  maintainers able to change workflows, and fork PR workflows disabled. The
  controller checks the private-repository fork setting before provisioning and
  registration, and pauses if it is enabled, missing or unreadable. The existing
  Actions/Admin token scopes cover this read. Private fork PRs therefore do not
  automatically run CI. The workflow's GitHub-hosted fallback does not replace
  these repository controls. Public operation remains disabled pending a
  separate runner-policy review; keep `allow_public_repository` false.
- Two workers maximum, bounded attempts and shared launch rates, and a 30-minute maximum
  lifetime limit costs. Each worker also powers itself off after job exit or a
  boot timeout. **Power-off alone is still billable**; the controller must delete
  it. If the controller host goes offline, inspect and delete an abandoned
  worker through Hetzner before resuming.
- Pushes, manual dispatches and same-repository PRs by the two configured
  maintainers are eligible. Only this repository's `tests.yml` workflow and
  exact run-attempt and job labels are accepted. The job names `core-tests` and
  `web-setup` must remain unique and match their labels. The default limits are
  two attempts per job, 12 worker launches per hour and 60 per day across the
  whole pool. Creation and deletion reservations count toward the worker cap,
  including requests whose outcome is uncertain. IP cleanup
  failures block new workers while still allowing expired or stopped servers
  to be deleted.

## Clean image

On a new dedicated Ubuntu 24.04 ARM server, run `image.sh`, verify its installed
tools and services, then run `clean-image.sh`. Snapshot the stopped machine with
the deployment UUID label and `volt-ci-role=image`. Confirm the snapshot is
available before deleting the builder.

The snapshot contains tools, an unregistered GitHub runner and the controller's
SSH **public** access key. It has no repository checkout, registered runner or
job credentials. Never update it from a machine that has executed PR code.
Build a new clean image when upgrading tools or security packages, switch the
controller to the verified new image, then delete the old owned snapshot.

GitHub's official runner download API supplies the pinned archive checksum used
in `image.sh`. The runner can update itself on a disposable worker; such updates
are not copied back into the golden image.

## Controller installation

Install `controller.py` in `/opt/volt-ci-controller/` and the service unit in
`/etc/systemd/system/`. Create the unprivileged `volt-ci` account. Keep private
configuration in `/etc/volt-ci/`, readable only by root and that account. Store
the two API tokens and worker SSH private key as separate permission-restricted
files. Do not put their values in JSON, command arguments, logs or Git.

Configure the exact deployment UUID, snapshot, firewall and SSH-key IDs, worker
size/location and token/key paths. Persistent state lives in `/var/lib/volt-ci`;
preserve it across controller restarts. The controller uses an exclusive lock
and one durable reservation per job to avoid duplicate workers after a crash or
uncertain create response. Each reservation has its own pinned host identity,
GitHub registration and cleanup lifecycle. It only cleans resources carrying its exact ownership labels.

Use `config.example.json` as the configuration template. Replace the resource
IDs and deployment UUID with values from the dedicated Hetzner project. Store
configuration as `/etc/volt-ci/config.json`; keep `allow_public_repository`
false until public-repository runner policy has been deliberately reviewed.
Set `max_workers` to **2** to run the core and web checks together on separate
CAX11 machines. Only 1 or 2 is accepted; an omitted setting defaults to 1 so
upgrading an existing controller does not silently increase concurrency.

Check `systemctl status volt-ci-controller` and
`journalctl -u volt-ci-controller` for sanitized lifecycle events. A stopped
controller cannot wake new workers. Before planned controller downtime, let the
active jobs finish and confirm Hetzner has no remaining worker.

Deploy controller changes that introduce new job labels before running the
matching workflow revision. Drain existing workers first. Queued runs using old
labels need a new run of the updated workflow; they are not eligible for the new
controller.

### Upgrading from the single-worker controller

1. Let running checks finish, then stop `volt-ci-controller`. Confirm state has
   no active reservation, the project's owned worker-server inventory is empty,
   and no worker IP cleanup remains. Keep queued jobs queued during deployment.
2. Back up the installed controller, private config and state in a root-only
   directory. Install the reviewed controller and set only `max_workers` to 2;
   leave the CAX11 size, snapshot, limits, credentials and policy unchanged.
3. Restart the service. State migrates atomically from version 1's `active`
   reservation to version 2's `workers` list, preserving retry budgets, launch
   history, cooldown and IP ownership proof. Migration also safely preserves an
   existing reservation if deployment was interrupted before draining.
4. Verify both jobs in a normal PR run execute on distinct machines concurrently
   and all workers, registrations and worker IPs disappear afterward.

To reduce concurrency again, set `max_workers` to 1 and restart. Existing jobs
finish normally; the controller fills no new slot until fewer than one worker
remains. Keep the new binary: the old single-worker version deliberately rejects
version 2 state. A binary rollback requires draining first, stopping the service,
and converting the **current** empty state back to version 1 (`workers: []` to
`active: null`) while preserving current budgets and cleanup proof. Never restore
an old state snapshot over newer cloud activity or discard an active reservation.

## Validation and cost

Run the network-free controller tests with:

```sh
python3 -m unittest discover -s ops/ci -p 'test_*.py' -v
bash -n ops/ci/image.sh ops/ci/clean-image.sh
```

Then verify a real two-job PR: both checks overlap and succeed on separate
fresh workers, and the project returns to zero servers and no billable worker IPv4
addresses. A GitHub account-level lock may also block self-hosted execution;
successful queued/running jobs are the verification, not an assumption.

On 17 October 2026, the project API listed CAX11 in `fsn1` as the cheapest
available suitable worker at **USD 0.0112/hour**, plus **USD 0.001/hour** for
IPv4. Each creation rounds
up to an hour, so two short jobs incur two hourly minima. Snapshot storage was
**USD 0.0199 per stored GB/month**. These are observed project prices, not a
permanent pricing promise. Idle snapshots continue to incur storage cost.
At those rates, a two-job run is approximately **USD 0.0244**. Running the same
two short jobs concurrently keeps the same two hourly minima; it increases peak
worker count, not the number of workers per run. The initial clean
snapshot is about 1.05 GB, or roughly **USD 0.02/month** while idle. Building the
image also incurs a temporary server charge.

The CI change is separate from application release approval. Installing this
controller does not authorize merging application PRs or publishing an APK.
