# GitHub Chromium build

AIHub builds the full Android Chromium browser from the `yagay/chromium` fork and injects the AIHub overlay at build time.

## Workflow

Use **Actions → Build AIHub Chromium APK → Run workflow** in the `yagay/AIHub` repository.

The workflow reads `chromium.version` by default. You may override it with a branch, tag, or commit SHA when starting a manual build.

Output artifact:

```text
AIHub-ChromePublic.apk
AIHub-ChromePublic.apk.sha256
chromium-revision.txt
aihub-revision.txt
```

## Runner requirements

Use a persistent x86_64 Linux self-hosted runner. Recommended:

- 8+ CPU cores
- 32 GiB RAM
- 200-300 GiB free SSD space
- Ubuntu 22.04/24.04 or another Chromium-supported Linux distribution

The workflow refuses the first checkout when less than about 120 GiB is free. Later incremental builds require at least about 25 GiB free.

Register the runner from:

```text
AIHub repository → Settings → Actions → Runners → New self-hosted runner
```

Choose Linux x64 and run the commands GitHub shows on that page. Keep the runner service online before starting the workflow.

## Persistent build cache

The workflow stores Chromium outside the GitHub Actions workspace:

```text
$HOME/.cache/aihub-build/
├── depot_tools/
└── chromium/
    └── src/
        └── out/AIHub/
```

This is intentional. `actions/checkout` may clean the AIHub work directory, but the expensive Chromium checkout and Ninja outputs survive between runs.

Before every build the workflow resets only Chromium tracked source changes, pulls the requested revision from `yagay/chromium`, syncs DEPS, then reapplies the AIHub overlay. Ignored `out/AIHub` build outputs remain available for incremental compilation.

## First build

The first run downloads Chromium, Android dependencies and toolchains, so it is much heavier than later builds. Later runs normally reuse both the source checkout and Ninja objects.

The workflow builds:

```text
chrome_public_apk
```

for Android arm64 with release-style GN settings (`is_debug=false`, `symbol_level=0`). The APK is expected at:

```text
out/AIHub/apks/ChromePublic.apk
```

## Chromium upgrade

The default revision is pinned in:

```text
chromium.version
```

To upgrade:

1. update/sync `yagay/chromium` from upstream;
2. change `chromium.version` to the desired commit;
3. run the workflow;
4. if the overlay checker fails, adapt only the thin Chromium seam/hook for that revision.

Do not copy Chromium source into the AIHub repository.
