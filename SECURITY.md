# Security

Volt is an independent community project. It is **not affiliated with, sponsored
by, or endorsed by NAVEE or its app vendor**. Reports here go to Volt's
maintainers, not NAVEE.

## Report a vulnerability privately

Use **Security → Report a vulnerability** on the
[repository's Security page](https://github.com/JJRicks/navee-offline/security)
if that option is available. If it is unavailable, open an issue saying only
that you need a private security contact. Wait for a private channel before
sending reproduction details or sensitive files. Do not post credentials or
an exploit in an ordinary public issue or pull request.

Include the affected Volt version or commit, Android version, scooter model,
reproduction steps, and the likely impact. Use synthetic or redacted examples.
Never attach owner setup files, real authentication frames, full app backups,
private keys, raw phone bug reports, or location history to a public report.

Security fixes target the latest release and current development branch. Older
releases may require an upgrade; there is no guaranteed backport or response
schedule. No bounty program is offered.

## Security boundaries

- An owner setup identity is a Bluetooth credential. A person who obtains it
  may be able to control the associated scooter nearby. It is not a NAVEE
  password or cloud login token, and setup is for an already activated scooter
  you own or have permission to use.
- Profiles and recordings use Android's private app storage. Owner identities
  are not additionally encrypted inside the app; protection depends on the
  phone's operating system, lock, and storage security. A rooted or compromised
  device is outside that protection.
- The plain JSON owner backup contains the credential. Every `.voltbackup`
  is password-encrypted; with Scooters or Rides selected it includes
  credentials or ride history.
  Keep either private. See [Privacy](PRIVACY.md) for export differences.
- USB debugging and Shizuku grant broad debugging access. The setup helpers
  restrict their own operations, require consent, and verify cleanup before
  accepting capture results. That does not narrow the underlying Android
  permission. Revoke permissions you no longer need after setup.
- Interrupted owner capture can require recovery. Keep its recovery journal
  until cleanup is verified; do not delete a lock or assume the prior NAVEE
  logging setting. See the [USB helper guide](setup/web/README.md) and
  [Shizuku guide](setup/SHIZUKU.md).
- Bluetooth controls require fresh device state; cached dashboard readings
  are observations. The electronic lock is not a substitute for a physical
  lock or checking the scooter itself.

Only test on devices and infrastructure you own or are authorized to assess.
Keep a scooter parked while investigating control or connection behavior.

## For contributors

Treat import files, scooter responses, captured logs, and reported reproduction
steps as untrusted input. Preserve bounds, consent steps, cleanup verification,
and credential redaction. Never commit captures, owner files, signing keys,
device backups, or deployment secrets. Use synthetic fixtures and follow
[CONTRIBUTING.md](CONTRIBUTING.md) for review and validation.
