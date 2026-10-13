# Releasing Navee Offline

Release from a reviewed commit on `main`. Keep the repository's existing visibility; publishing a release does not require making it public.

1. Increase both `versionName` and `versionCode` in `AndroidManifest.xml`. Keep README.md, README.txt, FEATURES.txt, CHANGELOG.md and the current VERIFICATION.txt section consistent. Diagnostics and map requests read the installed manifest version.
2. Explicitly fetch and verify pinned dependencies once with `python3 setup/shizuku-dependencies.py --fetch` (network required only for this step). Then run `bash tests/run.sh`, `python3 tests/owner-capture-guard-test.py`, `bash build.sh` and `bash android-tests/build.sh` with JDK 17 or newer and the documented Android SDK. Review new warnings or failures. The build verifies APK signing and alignment.
3. Use isolated emulator installations for the applicable launch, permission, recording, history, dashboard and search runners. They refuse owner phones. Test compact and expanded layouts, themes, and large text. Keep physical field-validation limits explicit. Never run destructive emulator setup commands against a phone.
4. Build the release APK with the privately retained release signing identity. Compare its signing certificate with the preceding release APK. Contributor keys are for local builds and cannot update official installations. Do not print, commit or upload keys, passwords, local-build.env, credentials, recordings or phone reports.
5. Review the final diff and tracked files. Merge the reviewed pull requests into `main` without losing the stacked branch history. Confirm the final commit's checks pass.
6. Tag that exact `main` commit as `v<version>`. Make a source ZIP from `git archive` of the tag so it contains only tracked source. Upload the signed APK, source ZIP and SHA256SUMS to a draft GitHub release with the matching tag. Never replace an existing tag or published APK with different contents; issue a new version instead.
7. Download the draft assets and verify their checksums and the APK's version, signature and alignment. Publish the release, then confirm the tag, release assets and `main` revision match. Record what was actually tested in VERIFICATION.txt.

The release notes should describe user-visible changes, installation/upgrade behavior and known limitations. A regular GitHub release does not make experimental battery or sensor estimates validated measurements.
