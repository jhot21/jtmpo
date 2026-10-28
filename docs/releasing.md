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
