# Build and test Volt

Volt uses **Java and native Android views**, built with Android SDK tools and shell scripts. There is no Gradle project. These instructions are for contributors; to use the app, [install a release APK](getting-started.md).

## Requirements

| Tool | Version / purpose |
| --- | --- |
| JDK | 17 or newer |
| Python | 3 |
| Android SDK platform | **36** |
| Android SDK build-tools | **36.1.0** |
| Android platform-tools | Needed to install on a device or emulator |
| Shell utilities | Bash, Git, `zip` and standard Unix utilities |

The optional browser helper has a separate Node 20+/npm build. Node is not needed to build the Android app.

## Build the Android app

```sh
git clone https://github.com/JJRicks/volt.git
cd volt
export ANDROID_SDK_ROOT=/path/to/Android/sdk
export JAVA_HOME=/path/to/jdk
python3 setup/shizuku-dependencies.py --fetch
bash build.sh
```

Replace the SDK and JDK paths with your installed locations. The dependency fetch is explicit and verifies pinned hashes; subsequent Android builds verify and use the cached dependencies offline. [Shizuku build details](../setup/SHIZUKU.md#dependencies-and-build) describe the included components.

The build signs the APK and verifies its signature and alignment. Its default output is:

```text
dist/volt.apk
```

To install on your own connected test device:

```sh
adb install -r dist/volt.apk
```

**A fresh clone creates its own local signing key.** It cannot update a project release signed with a different key. Export a backup before uninstalling or replacing an existing installation. Keep the same private key for future local updates; the project release key is not needed to contribute.

## Build configuration

| Variable | Default |
| --- | --- |
| `NAVEE_BUILD_DIR` | `.local/build` |
| `NAVEE_KEY_DIR` | `.local/keys` |
| `NAVEE_APK_PATH` | `dist/volt.apk` |

Relative override paths resolve from the repository root. An optional, ignored `local-build.env` can hold trusted local shell assignments; explicit environment values take precedence. Build output, local keys and this configuration file are ignored by Git. Keep custom output paths outside tracked source, and review staged files before pushing. Custom signing configuration is described in [Contributing](../CONTRIBUTING.md#signing-and-private-files).

## Run core tests

These tests need the JDK and Python but no Android SDK, device, internet connection or signing key:

```sh
bash tests/run.sh
python3 tests/owner-capture-guard-test.py
```

They exercise protocol, storage, calculations and owner-capture logic with synthetic data. Passing them does not establish physical scooter behavior or compatibility with every phone and firmware.

## Build native tests

Build the main app first, then its separate instrumentation APK with the same local configuration and signing key:

```sh
bash build.sh
bash android-tests/build.sh
```

The default runner output is `.local/build/parked-validation/parked-validation.apk`. Read the [development and validation notes](development-notes.md#device-checks) and the selected runner before executing it. Disposable emulator fixtures can clear test data; parked-scooter modes can deliberately change real settings. Select only the checks appropriate to your change and test device.

## Optional browser helper

The static USB setup website is built separately:

```sh
cd setup/web
npm ci --ignore-scripts
npm test
npm run build
```

Its [README](../setup/web/README.md#build-and-local-use) covers local serving, browser checks, required security headers and deployment boundaries. Building it does not deploy it.

For source layout and implementation invariants, see [Development notes](development-notes.md). For contribution expectations, see [Contributing](../CONTRIBUTING.md); release maintainers should also read [Releasing](../RELEASING.md).
