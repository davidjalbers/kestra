---
name: fork-maintenance
description: >
  Maintain the custom Kestra fork (davidjalbers/kestra) that adds 1Password secret retrieval
  via an op() Pebble function. Use this skill whenever the user mentions upgrading Kestra,
  applying the kestra-dja patch, building or pushing the davidjalbers/kestra Docker image,
  resolving patch conflicts in the fork, adding plugins to the Kestra image, modifying the 
  fork's Dockerfile/Makefile/OpFunction, regenerating kestra-dja.diff, or troubleshooting op()
  failures.
---

# Kestra Fork Maintenance

This skill guides you through maintaining the custom Kestra fork at `davidjalbers/kestra`.
The fork adds 1Password secret retrieval to Kestra's open-source edition via a custom `op()` Pebble function.

## Architecture Overview

### Why the fork exists

Kestra OSS has no mechanism to retrieve secrets from an external vault at Pebble template evaluation time. 
When secrets for a project live in 1Password, the fork adds `op()` so flows can do:

```yaml
env:
  HCLOUD_TOKEN: "{{ op('my-vault/terraform/provider-hcloud/token') }}"
```

### What the fork changes (5 files)

All customisations are captured in a single unified diff called [kestra-dja.diff](kestra-dja.diff).

**1. `Dockerfile`**
- Copies the `op` binary from `1password/op:2` into the image (`COPY --from=1password/op:2 /usr/local/bin/op /usr/local/bin/op`)
- Sets default `KESTRA_PLUGINS` with five plugins: `plugin-script-shell`, `plugin-script-python`, `plugin-docker`, `plugin-git`, `plugin-aws`
- Adds `--repositories=https://central.sonatype.com/repository/maven-snapshots` to the plugin install command

**2. `Makefile`**
- Changes `DOCKER_IMAGE` from `kestra/kestra` to `davidjalbers/kestra`
- Fixes the `build-docker` target to glob for the versioned executable (`kestra-*`) instead of assuming a fixed filename `kestra`

**3. `core/src/main/java/io/kestra/core/runners/pebble/Extension.java`**
- Registers `OpFunction` under the key `"op"` in the Pebble extension's `getFunctions()` method:
  ```java
  functions.put("op", new OpFunction());
  ```

**4. `core/src/main/java/io/kestra/core/runners/pebble/functions/OpFunction.java`** _(new file)_
- Implements the `op()` Pebble function
- Takes a single positional argument `ref` of the form `vault/item/field` — `op://` is prepended automatically
- Runs `op read op://<ref>` as a subprocess with a 10-second timeout
- Returns the secret value as a trimmed string; throws `RuntimeException` on non-zero exit or timeout

**5. `ui/src/override/services/flowAutoCompletionProvider.ts`** (and its spec file)
- Adds `op('${1:my-vault/item/field}')` to the autocomplete snippet list in the flow editor UI

### Runtime requirement

The Kestra JVM process needs the env var`OP_SERVICE_ACCOUNT_TOKEN` set to a valid 1Password service account token. 

---

### Prerequisites (for build and deploy tasks)

- Java 25 (macOS/Homebrew: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`; Debian/Ubuntu: install `openjdk-25-jdk` and `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`; adjust for other platforms)
- Docker running and logged into Docker Hub as `davidjalbers`
- Git push access to `davidjalbers/kestra` (SSH key or token configured)

---

### Git structure and placeholders

The `.claude` folder with this skill files (including the patch file) lives on a separate branch called `dja`.
When reading from and writing to these files and the patch file, use Git intelligently to navigate this 
(e.g. stash a diff file, change branches and then apply the stash, or use git show to read from a different branch).‚

| Item | Value |
|------|-------|
| Fork remote (`origin`) | `git@github.com:davidjalbers/kestra.git` |
| Upstream remote (`upstream`) | `https://github.com/kestra-io/kestra` |
| Tag convention | `v<VERSION>-dja` (e.g. `v1.3.0-dja`) |
| Docker Hub image | `davidjalbers/kestra:<version>` |
| `<NEW_VERSION>` | The new Kestra version you're upgrading to (e.g. `1.3.0`) |
| `<PATCH>` | The path to the patch file: `.claude/skills/fork-maintenance/kestra-dja.diff` |

---

## Task 1: Upgrade to a New Kestra Version

### Step-by-step

```bash
# 1. Check out the new release tag (detached HEAD is fine)
git checkout v<NEW_VERSION>

# 2. Apply the patch
git apply "<PATCH>"
# Expected: applies cleanly, possible whitespace warnings are fine
# If this fails, see Task 2 (Resolve Patch Conflicts) below

# 3. Build the Docker image
export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # adjust for your platform (see Prerequisites)
export DOCKER_DEFAULT_PLATFORM=linux/amd64
make build-docker
# Compiles full Kestra source + UI. Takes a few minutes.
# Image is tagged davidjalbers/kestra:<NEW_VERSION>

# 4. Push to Docker Hub
docker push davidjalbers/kestra:<NEW_VERSION>

# 5. Commit, tag, and push to the fork
git add -A
git commit -m "apply custom changes"
git tag v<NEW_VERSION>-dja
git push origin v<NEW_VERSION>-dja

# 6. Regenerate the patch file
git diff v<NEW_VERSION> HEAD > "<PATCH>"
```

---

## Task 2: Resolve Patch Conflicts

If `git apply` fails at step 2 of the upgrade:

```bash
git apply --reject "$PATCH"
```

This creates `.rej` files showing exactly what failed. The most likely conflict areas:

**Extension.java** — Kestra added new Pebble functions near the insertion point. Fix: re-insert `functions.put("op", new OpFunction());` inside the `getFunctions()` method, before or after the existing `functions.put(...)` calls. Also check the other modified files (Dockerfile, Makefile, UI) — if any of them also produced `.rej` files, resolve those too.

**Makefile** — `build-docker` target or `DOCKER_IMAGE` variable changed upstream. Fix: re-apply the `DOCKER_IMAGE = davidjalbers/kestra` rename and the executable-glob fix (replace the `cp build/executable/* docker/app/kestra` line with the glob logic that finds `kestra-*`).

**Dockerfile** — Base image version, apt packages, or plugin install syntax changed. Fix: re-apply (a) the `COPY --from=1password/op:2` line after the `FROM` line, (b) the `KESTRA_PLUGINS` default value, and (c) the `--repositories=https://central.sonatype.com/repository/maven-snapshots` flag on the plugin install command.

**OpFunction.java** — This is a new file so it should never conflict, just ensure it's created at the right path.

**UI autocomplete files** — Re-insert the `"op('${1:my-vault/item/field}')"` line in both the provider and its spec.

If facing other challenges or decisions, feel free to check back with the user on how to proceed.

After resolving all conflicts:

```bash
find . -name "*.rej" -delete
git add -A
git commit -m "apply custom changes"
git diff v<NEW_VERSION> HEAD > "$PATCH"
```

Then continue from step 3 of the upgrade process.

---

## Task 3: Modify the Fork Without a Version Upgrade

Examples: adding a new plugin to the default list, tweaking the Dockerfile, adjusting OpFunction behavior.

```bash
# Check out the latest patched tag (e.g. v1.3.1-dja)
git checkout "<TAG>"

# Make your changes and commit
git add -A && git commit -m "describe the change"

# Regenerate the patch against the current base tag
git diff v<CURRENT_BASE_VERSION> HEAD > "$PATCH"
```

If the change should go live immediately, also rebuild, push, and redeploy (Task 1, reusing the same version tag).

When adding a new plugin, the change is just appending to the `KESTRA_PLUGINS` default in the `Dockerfile`. The format is `io.kestra.plugin:plugin-<name>:LATEST`, space-separated.

---

## Task 4: Troubleshoot Runtime Issues

### op() returns an error or empty value

1. **Check `OP_SERVICE_ACCOUNT_TOKEN`** — The token must be set and valid.
2. **Check the reference format** — The argument to `op()` must be `vault/item/field` (three segments). The function prepends `op://` automatically. A common mistake is passing `op://vault/item/field` which becomes `op://op://...`.
3. **Check 1Password access** — The service account token must have read access to the referenced vault. Test with: `OP_SERVICE_ACCOUNT_TOKEN=<token> op read "op://vault/item/field"`
4. **Check timeout** — OpFunction has a 10-second timeout. If 1Password is slow or unreachable from the host, the call will fail with a timeout error.

### Kestra won't start after upgrade

1. **Check Docker image tag** — Ensure the specified tag matches the tag you pushed to Docker Hub.
2. **Check plugin compatibility** — New Kestra versions sometimes break plugin APIs. Check the Kestra release notes for breaking changes in the plugin SDK.
3. **Check port 8081** — The health check expects Kestra to respond on port 8081. Verify with `curl -s http://localhost:8081/api/v1/configs` from the host.
4. **Check container logs** — `docker logs kestra` (or whatever the container name is in the compose file).

---

## Important Conventions

- Always regenerate `kestra-dja.diff` after any change to the fork. The diff is the single source of truth for what the fork changes.
- The diff is generated as `git diff v<BASE_VERSION> HEAD` — it's always relative to the upstream tag, not to `main` or any other branch.
- Never push directly to `main` on the fork. Work on `releases/` branches.
- Tags on the fork follow `v<VERSION>-dja`. The `-dja` suffix distinguishes fork tags from upstream tags.
