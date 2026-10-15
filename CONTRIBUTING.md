# Contributing to Volt

Thanks for helping make Volt useful across phones and scooters. It is an independent community project, not affiliated with or endorsed by NAVEE.

## Report a bug or compatibility result

Include the Volt version, phone model, Android version, scooter model/region/firmware where known, steps to reproduce, expected behavior and what happened. For layout problems, include the window size or folded/unfolded state and any custom font/display scaling.

Redact screenshots and logs before sharing. Do not attach owner setup identities, Bluetooth addresses, serial numbers, PINs, account tokens, private backups, full Android bug reports or precise ride routes. A short sanitized exception is more useful than an entire phone capture. Use [SECURITY.md](SECURITY.md) for vulnerabilities and exposed secrets.

## Propose a change

For substantial features or new scooter support, open an issue describing the intended behavior first. Keep a pull request focused enough to review, explain the user-visible change and relevant validation, and identify anything requiring physical hardware testing. Preserve compatibility with existing profiles, recordings and saved layouts.

Use a feature branch and include documentation changes where behavior changes. An independent maintainer should review the final commit before merging. Do not describe a control as verified merely because it compiles or the scooter acknowledges a write.

## Build and test

Follow the [README build instructions](README.md#build-from-source). The app uses Java, native Android views and a shell build; the optional USB helper is a separate static Node/npm project.

```sh
bash tests/run.sh
python3 tests/owner-capture-guard-test.py
bash build.sh
bash android-tests/build.sh
```

Run checks relevant to your change. UI and lifecycle changes need native checks on a disposable emulator, including narrow screens and large text when relevant. The [development notes](docs/development-notes.md) describe the test runners and implementation invariants. Some parked validation modes change scooter settings; do not run them on someone else's device or an unattended scooter.

For website changes, use the commands in [setup/web/README.md](setup/web/README.md). Keep dependencies pinned and preserve the explicit consent, local processing and cleanup boundaries.

## Signing and private files

Contributor builds use your own local signing key. They cannot update an installed project release signed with a different key; export anything you need before uninstalling an app. The project release key is not required to contribute and must never be shared in a PR.

The ignored `local-build.env` contains trusted local shell assignments. You can override build paths with `NAVEE_BUILD_DIR`, `NAVEE_KEY_DIR` and `NAVEE_APK_PATH`, or privately configure `NAVEE_SIGNING_KEYSTORE`, `NAVEE_SIGNING_ALIAS` and `NAVEE_SIGNING_PASSWORD`. Do not commit this file or signing material.

Review staged changes before pushing. Keep captures, credentials, recordings, private deployment details and generated binaries out of Git. Use clearly synthetic test identities and coordinates. `.gitignore` helps prevent accidents; it does not remove files already committed or erase history.

## Behavior to preserve

- Controls require fresh authenticated state; cached values never authorize an action.
- Reconnection does not replay pending commands or automatically change the lock.
- Scooter commands use canonical units; display preferences do not alter raw values.
- Missing telemetry stays unavailable, with gaps excluded from energy integration.
- Recording lifecycle and the idle background timeout remain separate.
- Simulation cannot send real commands or contaminate recorded history.
- Layout changes preserve saved configurations and accessible controls.

More detailed constraints and native test guidance are in [development notes](docs/development-notes.md). The release process is in [RELEASING.md](RELEASING.md).

## Attribution

Only contribute code and assets you have the right to share. Preserve third-party notices and identify the source and license of new dependencies or artwork. Application contributions use the repository's [MIT License](LICENSE); separately licensed assets retain their own terms in [NOTICE](NOTICE).
