#!/usr/bin/env bash
# Build a clean CI image on a dedicated, newly created Ubuntu 24.04 worker.
# Never run on a production application server or a worker that has executed jobs.
set -euo pipefail
[[ $(id -u) == 0 ]] || { echo 'Run as root on the image builder.' >&2; exit 1; }
[[ ! -e /var/lib/volt-ci-job-started ]] || { echo 'Refusing to image a used worker.' >&2; exit 1; }
[[ $(uname -m) == aarch64 ]] || { echo "This image targets ARM64." >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl git openssh-client \
  openjdk-17-jdk-headless python3 tar gzip unzip zip zstd libicu74 libssl3t64 libkrb5-3 zlib1g
id volt-runner >/dev/null 2>&1 || useradd --create-home --shell /bin/bash volt-runner
passwd --lock volt-runner
install -d -o volt-runner -g volt-runner -m 0755 /opt/volt-runner /opt/hostedtoolcache
version=2.337.0
archive=/tmp/actions-runner.tar.gz
curl --fail --silent --show-error --location \
  "https://github.com/actions/runner/releases/download/v${version}/actions-runner-linux-arm64-${version}.tar.gz" -o "$archive"
echo '9b1dc70626422526e3c94767cf024896beb15da5342a3f4819bf2feac13e0393  /tmp/actions-runner.tar.gz' | sha256sum -c -
tar -xzf "$archive" -C /opt/volt-runner
chown -R volt-runner:volt-runner /opt/volt-runner
rm -f "$archive"
# One runner/job per VM. No sudo, Docker socket or deployment credentials.
install -d -m 0755 /opt/volt-ci
cat > /opt/volt-ci/start-job <<'START'
#!/usr/bin/env python3
import os, pathlib, re, subprocess, sys
if os.getuid() != 0:
    raise SystemExit('root required')
marker = pathlib.Path('/var/lib/volt-ci-job-started')
if marker.exists():
    raise SystemExit('worker already used')
jit = sys.stdin.buffer.readline(1048577).strip()
if not 20 <= len(jit) <= 1048576 or not re.fullmatch(rb'[A-Za-z0-9+/=_-]+', jit):
    raise SystemExit('invalid runner configuration')
fd = os.open(marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
os.close(fd)
directory = pathlib.Path('/run/volt-ci')
directory.mkdir(mode=0o700, exist_ok=True)
subprocess.run(['chown', 'volt-runner:volt-runner', str(directory)], check=True)
fd = os.open(directory / 'jit', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
with os.fdopen(fd, 'wb') as handle:
    handle.write(jit)
subprocess.run(['chown', 'volt-runner:volt-runner', str(directory / 'jit')], check=True)
subprocess.run(['systemctl', 'start', '--no-block', 'volt-ci-job.service'], check=True)
print('started')
START
cat > /opt/volt-ci/run-job <<'RUN'
#!/usr/bin/env bash
set -euo pipefail
jit=$(cat /run/volt-ci/jit)
rm -f /run/volt-ci/jit
cd /opt/volt-runner
exec ./run.sh --jitconfig "$jit"
RUN
chmod 0755 /opt/volt-ci/start-job /opt/volt-ci/run-job
cat > /etc/systemd/system/volt-ci-job.service <<'UNIT'
[Unit]
Description=Volt single-job GitHub runner
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
User=volt-runner
Group=volt-runner
WorkingDirectory=/opt/volt-runner
Environment=RUNNER_TOOL_CACHE=/opt/hostedtoolcache
Environment=JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
ExecStart=/opt/volt-ci/run-job
ExecStopPost=+/usr/bin/systemctl --no-block poweroff
RuntimeMaxSec=20min
TimeoutStopSec=30
KillMode=control-group
NoNewPrivileges=yes
ProtectSystem=full
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
PrivateDevices=yes
RestrictSUIDSGID=yes
UMask=0077
StandardOutput=journal
StandardError=journal
UNIT
# Safety shutdown even if bootstrap/registration fails. The external controller
# deletes the stopped VM; powered-off servers remain billable until deletion.
cat > /etc/systemd/system/volt-ci-expire.service <<'UNIT'
[Unit]
Description=Stop an abandoned Volt worker
[Service]
Type=oneshot
ExecStart=/usr/bin/systemctl --no-block poweroff
UNIT
cat > /etc/systemd/system/volt-ci-expire.timer <<'UNIT'
[Unit]
Description=Maximum Volt worker boot lifetime
[Timer]
OnBootSec=25min
Unit=volt-ci-expire.service
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable volt-ci-expire.timer
apt-get clean
rm -rf /var/lib/apt/lists/*
touch /var/lib/volt-ci-clean-image
echo 'Clean tools image installed; no runner registration or checkout exists.'
