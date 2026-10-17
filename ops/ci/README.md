# Disposable CI workers

The existing Java/Python tests and web setup tests/build run on a small ARM Linux
worker. Android SDK builds, emulator tests, release signing and deployment are
not part of this worker's job.

A lightweight controller runs separately on an existing always-on server. It
polls the GitHub Actions queue, restores a clean tools snapshot, supplies a
single-job runner registration over pinned SSH, and deletes the worker after
the job finishes. Another job gets a fresh machine. With no queued work, only
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
  PR provenance. A unique per-run label isolates registration to that run.
  Fork/untrusted jobs use GitHub-hosted runners instead. Public-repository
  operation is also disabled in the controller until deliberately configured.
- One worker maximum, bounded attempts and launch rates, and a 30-minute maximum
  lifetime limit costs. Each worker also powers itself off after job exit or a
  boot timeout. **Power-off alone is still billable**; the controller must delete
  it. If the controller host goes offline, inspect and delete an abandoned
  worker through Hetzner before resuming.
- Pushes, manual dispatches and same-repository PRs by the two configured
  maintainers are eligible. Only this repository's `tests.yml` workflow and
  exact run-attempt labels are accepted. The default limits are two attempts
  per job, 12 worker launches per hour and 60 per day.

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
and durable reservations to avoid duplicate workers after a crash or uncertain
create response. It only cleans resources carrying its exact ownership labels.

Use `config.example.json` as the configuration template. Replace the resource
IDs and deployment UUID with values from the dedicated Hetzner project. Store
configuration as `/etc/volt-ci/config.json`; keep `allow_public_repository`
false until public-repository runner policy has been deliberately reviewed.

Check `systemctl status volt-ci-controller` and
`journalctl -u volt-ci-controller` for sanitized lifecycle events. A stopped
controller cannot wake new workers. Before planned controller downtime, let the
active job finish and confirm Hetzner has no remaining worker.

## Validation and cost

Run the network-free controller tests with:

```sh
python3 -m unittest discover -s ops/ci -p 'test_*.py' -v
bash -n ops/ci/image.sh ops/ci/clean-image.sh
```

Then verify a real two-job PR: both checks succeed, the second uses a fresh
worker, and the project returns to zero servers and no billable worker IPv4
addresses. A GitHub account-level lock may also block self-hosted execution;
successful queued/running jobs are the verification, not an assumption.

On 17 October 2026, the project API listed CAX11 in `fsn1` as the cheapest
available suitable worker at **USD 0.0112/hour**, plus **USD 0.001/hour** for
IPv4. Each creation rounds
up to an hour, so two short jobs incur two hourly minima. Snapshot storage was
**USD 0.0199 per stored GB/month**. These are observed project prices, not a
permanent pricing promise. Idle snapshots continue to incur storage cost.
At those rates, a two-job run is approximately **USD 0.0244**. The initial clean
snapshot is about 1.05 GB, or roughly **USD 0.02/month** while idle. Building the
image also incurs a temporary server charge.

The CI change is separate from application release approval. Installing this
controller does not authorize merging application PRs or publishing an APK.
