# GitHub Release Flow (tag → Actions → Release)

## Overview

Releases are **built by CI, not on the laptop**. The pipeline:

```
scripts/release.sh X.Y.Z
  └─ git push origin master vX.Y.Z
       └─ .github/workflows/release.yml (on: push tags v*)
            ├─ assembleRelease (KBTV_KEYSTORE_* env)      → app-release.apk
            ├─ assembleRelease -Pkbtv.flags.airplay=true  → app-release-airplay.apk
            └─ softprops/action-gh-release@v2
                 ├─ body: releases/vX.Y.Z/VERSION_vX.Y.Z.md (project changelog)
                 ├─ generate_release_notes: true (GitHub commit notes appended)
                 └─ files: both APKs
```

## Requirements

- Tag must match `v*` and be pushed to `origin` (annotated tags from release.sh)
- Workflow needs `permissions: contents: write` (already set in release.yml)
- `gh` CLI authenticated locally — only for *monitoring*; the Release itself is created by CI with the repo's own `GITHUB_TOKEN`

## Monitoring

```bash
# live log of the release build
gh run watch --workflow=release.yml

# list recent runs
gh run list --workflow=release.yml --limit 3

# the finished release
gh release view vX.Y.Z
```

## Failure Handling

| Symptom | What to do |
|---|---|
| Build step fails (compile/lint) | Open the run log: `gh run view <id> --log-failed`; fix on master, re-tag: delete remote+local tag, re-run release.sh steps (or move the tag) |
| Release publish fails, build OK | Usually a permissions problem — check `permissions: contents: write` survived; re-run the job: `gh run rerun <id> --failed` |
| Push failed (offline) | Nothing was lost: commit+tag are local. Push later: `git push origin master vX.Y.Z`; CI then builds normally |
| Wrong tag content published | `gh release delete vX.Y.Z`, `git push origin :refs/tags/vX.Y.Z`, fix, re-tag and push |

## Break-glass: manual release (no CI)

If Actions is unavailable, the old local path still works:

```bash
./gradlew assembleRelease -Pkbtv.signing.keystore=... -Pkbtv.signing.storePassword=...     -Pkbtv.signing.keyAlias=kbtv -Pkbtv.signing.keyPassword=...
gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk \
  --title "Release vX.Y.Z" --notes "$(cat releases/vX.Y.Z/VERSION_vX.Y.Z.md)"
```

Note: this bypasses the airplay-variant build and the CI environment — prefer fixing CI over using this.
