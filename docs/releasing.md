# Releasing

`task build:release` assembles a minified, resource-shrunk release APK at
`app/build/outputs/apk/release/jtmpo.apk`. It is signed when all four signing variables
(`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are set, and unsigned
otherwise, in which case the output is `jtmpo-unsigned.apk`. Check the task's own output line
to see which.

Local builds take their version from the defaults in `gradle.properties` (`task set-version -- X.Y.Z`
edits them). Published releases don't use those: the version comes from the git tag, below.

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

Pushing a tag builds, signs and publishes the APK through `.github/workflows/release.yml`. The tag
is the only input, so there is no version-bump commit. Three tag forms are accepted and nothing
else (no `+build` suffixes, upper case, leading zeros or other labels):

| Tag | Kind | Source commit |
| --- | --- | --- |
| `vX.Y.Z` | final release | must be on `main` |
| `vX.Y.Z-rc.N` | pre-release (release candidate), N 1 to 48 | any branch |
| `vX.Y.Z-beta.N` | pre-release (beta), N 1 to 39 | any branch |

The version name is the tag without the `v`. The version code is
`((X*100 + Y)*100 + Z)*100 + stage`, where the stage is 99 for a final, `50 + N` for `rc.N` and
`10 + N` for `beta.N`. Minor and patch must each be 99 or less, and the code must stay under
Android's 2100000000 limit. The rules live in `scripts/release-version.sh` (tested by
`scripts/release-version.test.sh`, part of `task test:scripts`). Examples:

| Tag | versionName | versionCode |
| --- | --- | --- |
| `v0.3.1-beta.1` | `0.3.1-beta.1` | 30111 |
| `v0.3.1-rc.2` | `0.3.1-rc.2` | 30152 |
| `v0.3.1` | `0.3.1` | 30199 |

So for one version beta < rc < final, and any stage of the next patch outranks the previous
final. Android only installs an update with a higher version code, so don't tag a lower version
than one already released. `v0.2.1` (code 201) and `v0.3.0` (code 300) used the old scheme
`X*10000 + Y*100 + Z`. Every code the new scheme gives to a version later than `v0.3.0` is higher
than both (for example `v0.3.1-beta.1` is 30111). Very small versions are the exception: `v0.0.1`
would get 199, below the old codes.

Watch out for a final release of a lower version after a higher pre-release exists. Say
`v0.4.0-rc.1` (code 40051) is out and you then tag a hotfix `v0.3.2` (code 30299). The workflow
admits it, because a final is only compared with the latest final, but a device running the RC
will not install it: its code is lower. Testers on the RC wait for the next RC or the final of
the higher version, or uninstall, which wipes local data, so they should back up first.

To cut a test build from a branch, tag its tip and push the tag:

```bash
git tag v0.3.1-rc.1 && git push origin v0.3.1-rc.1
```

A final release is tagged the same way from `main`:

```bash
git tag v0.3.1 && git push origin v0.3.1
```

The workflow refuses a final tag whose commit is not on `main`; pre-releases skip that check.
It then runs `./gradlew lint test`, builds the signed release with the derived version passed as
Gradle properties, and checks that the APK carries the expected signing certificate and the
expected version name and code. The code must also be higher than the previous release's: a
final is compared with the latest final release, a pre-release with the latest release of any
kind. It attaches `jtmpo-<version>.apk` and `SHA256SUMS` to a new GitHub release with generated
notes. Pre-releases are marked as such on GitHub, so they never become the "latest" release.

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
