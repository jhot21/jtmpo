# Releasing

`task build:release` assembles a minified, resource-shrunk release APK at
`app/build/outputs/apk/release/jtmpo.apk`. It is signed when all four signing variables
(`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are set, and unsigned
otherwise, in which case the output is `jtmpo-unsigned.apk`. Check the task's own output line
to see which.

Version numbers live in `gradle.properties`. Setting a version also bumps the build number
(`VERSION_CODE`):

```bash
mise exec -- task set-version -- 0.2.0   # VERSION_NAME=0.2.0, VERSION_CODE+1
mise exec -- task build:release
```

## One-time signing key setup

The keystore stays out of git (`.gitignore` has `*.jks`). Back it up: losing it means installed
copies can only be updated after an uninstall, which wipes local data.

```bash
keytool -genkeypair -v -keystore ./jtmpo-release-key.jks -alias jtmpo \
  -keyalg RSA -keysize 4096 -validity 10000

mise set --file mise.local.toml KEYSTORE_PASSWORD='<store password>' --age-encrypt
mise set --file mise.local.toml KEY_PASSWORD='<key password>' --age-encrypt
```

The passwords go in `mise.local.toml`, which is gitignored and age-encrypted. `mise.toml` sets
the keystore path (`KEYSTORE_PATH`, next to the repo root) and `Taskfile.yml` sets the alias
(`KEY_ALIAS`). Without `mise`, export the four variables yourself.

## Publishing a release

Pushing a tag builds, signs and publishes the APK through `.github/workflows/release.yml`:

```bash
mise exec -- task set-version -- 0.3.0   # bumps gradle.properties; commit it and merge to main
git tag v0.3.0 && git push origin v0.3.0
```

The workflow refuses to run unless the tag is `v` plus `VERSION_NAME` from `gradle.properties` and
the tagged commit is on `main`. It then runs `./gradlew lint test`, builds the signed release,
checks that the APK carries the expected signing certificate and the `VERSION_CODE` from
`gradle.properties`, and that the code is higher than the previous release's, and attaches
`jtmpo-<version>.apk` and `SHA256SUMS` to a new GitHub release with generated notes.

The signing certificate's SHA-256 is pinned in `EXPECTED_CERT_SHA256` in the workflow. If you
ever generate a new keystore, update it there, and note that installed copies can only update
across a certificate change after an uninstall.

### Repository secrets

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the keystore, `base64 < jtmpo-release-key.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | the key alias (`jtmpo`) |
| `RELEASE_KEY_PASSWORD` | the key password |
