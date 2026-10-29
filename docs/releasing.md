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

### Approving a release

The release job runs in the `release` environment, which requires an approval before the job
starts. After you push a tag the run shows "Waiting" until a reviewer approves it: open the run on
GitHub and choose "Review deployments", or from a terminal:

```bash
gh run list --workflow Release --limit 1          # find the run id
gh api repos/jhot21/jtmpo/actions/runs/<run id>/pending_deployments   # the environment id
gh api repos/jhot21/jtmpo/actions/runs/<run id>/pending_deployments --method POST \
  -F 'environment_ids[]=<environment id>' -f state=approved -f comment='ok'
```

Every release run needs this, finals and pre-releases alike. Agents and scripts must not approve
a release on their own: the approval is the human check that a signed build was meant to happen.

### Release environment

The signing secrets are stored as secrets of the `release` environment, not of the repository, so
a job that doesn't run in that environment can't read them. That matters because a tag can point
at any commit and a commit can change `release.yml`: dropping `environment: release` from a
malicious copy of the workflow only leaves it without the keys.

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the keystore, `base64 < jtmpo-release-key.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | the key alias (`jtmpo`) |
| `RELEASE_KEY_PASSWORD` | the key password |

The environment's settings: the owner (`jhot21`) is the required reviewer; self-review is allowed
because the repository has one maintainer; administrators can't bypass the approval; and it only
deploys from tags matching `v*`. A repository ruleset ("release tags") also stops anyone but the
repository admin from creating, moving or deleting `v*` tags. Both live in GitHub's settings, not
in this repo. To recreate the environment:

```bash
gh api repos/jhot21/jtmpo/environments/release --method PUT --input - <<EOF
{ "prevent_self_review": false, "can_admins_bypass": false,
  "reviewers": [ { "type": "User", "id": $(gh api users/jhot21 --jq .id) } ],
  "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true } }
EOF
gh api repos/jhot21/jtmpo/environments/release/deployment-branch-policies \
  --method POST -f name='v*' -f type=tag
```

Then set the four secrets with `gh secret set <NAME> --env release`, feeding each value on
standard input (for example `base64 < jtmpo-release-key.jks | tr -d '\n' | gh secret set
RELEASE_KEYSTORE_BASE64 --env release`), and check the keystore's certificate against
`EXPECTED_CERT_SHA256` first. To rotate a password or the keystore, set the secret again the same
way.
