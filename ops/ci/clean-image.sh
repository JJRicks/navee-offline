#!/usr/bin/env bash
# Final action on a verified builder immediately before shutdown/snapshot.
set -euo pipefail
[[ $(id -u) == 0 && -e /var/lib/volt-ci-clean-image ]]
[[ ! -e /var/lib/volt-ci-job-started && ! -e /opt/volt-runner/.runner ]]
[[ ! -e /opt/volt-runner/.credentials && ! -e /opt/volt-runner/_work ]]
rm -f /root/.bash_history /home/volt-runner/.bash_history
cloud-init clean --logs --machine-id
rm -f /etc/ssh/ssh_host_*
sync
systemctl poweroff
