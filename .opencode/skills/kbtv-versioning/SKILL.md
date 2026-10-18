---
name: kbtv-versioning
description: KBTVCaster Android project version management. Record releases with semantic versioning, git tags, and workflow-driven changelogs tailored for Android/Kotlin projects. Use when user wants to (1) document KBTVCaster stable releases with DLNA/AirPlay casting features, (2) create git tags for Android APK releases, (3) publish GitHub Releases built by CI, (4) rollback to previous versions. Customized for KBTVCaster project structure and versioning conventions. Trigger phrases: 记录项目, record project, version, 版本, create release, 发布版本, 发版, rollback, 回滚, KBTV版本, KBTVCaster发布.
license: MIT
compatibility: opencode
metadata:
  audience: kbtv-developers
  workflow: android-release-management
---

## What I Do

I manage KBTVCaster versions throughout the development lifecycle:

- **Version determination**: Analyze changes since the last tag, suggest a SemVer bump
- **Release execution**: Drive `scripts/release.sh` (repo root), which bumps `versionName`/`versionCode`, writes the changelog, commits, tags, and pushes
- **CI-built releases**: Pushing a `v*` tag triggers `.github/workflows/release.yml`, which builds both APK variants and publishes them to a GitHub Release — no local APK builds, no local tarball archives
- **Rollback**: Guide checkout/reset to a previous tag

## When to Use Me

| Request Type | Examples |
|--------------|----------|
| Record version | "记录项目", "record project", "version", "版本", "标记版本" |
| Create release | "发布新版本", "create a release", "发版", "KBTVCaster发布" |
| Android specific | "APK版本", "Android发布", "new release" |
| Rollback version | "rollback", "回滚", "revert to previous version" |

## Release Flow (2026-10 modernization)

```
1. Analyze changes since last tag        (git log v<prev>..HEAD)
2. Suggest SemVer bump, WAIT for user confirmation
3. User confirms version X.Y.Z
4. Run: scripts/release.sh X.Y.Z
     ├─ preflight: clean tree, on master, tag unused, gh authed
     ├─ bump app/build.gradle.kts (versionName + versionCode)
     ├─ changelog releases/vX.Y.Z/VERSION_vX.Y.Z.md
     │    missing → scaffold + commit bump, STOP for human editing,
     │    rerun scripts/release.sh X.Y.Z after editing
     ├─ append releases/INDEX.md entry + manifest.json
     ├─ commit "Release X.Y.Z", annotated tag vX.Y.Z
     └─ push origin master vX.Y.Z   ← tag push triggers CI
5. GitHub Actions (.github/workflows/release.yml):
     ├─ assembleDebug            → app-debug.apk        (DLNA 稳定版)
     ├─ assembleDebug -Pkbtv.flags.airplay=true
                                 → app-debug-airplay.apk (实验性镜像)
     └─ GitHub Release: changelog as body + auto notes + both APKs
6. Verify: gh run watch --workflow=release.yml, then gh release view vX.Y.Z
```

### Key principles

- **Always confirm the version** with the user before running release.sh
- **Changelog is human-written** — the scaffold's "What's New" must be edited by a person before the release goes out
- **The tag is the release trigger** — nothing publishes without pushing the tag
- **Offline? Push later.** The commit+tag stay local; `git push origin master vX.Y.Z` whenever network returns, and CI picks it up then. No tarball fallback anymore.

## KBTVCaster Project Specifics

- **Version lives in** `app/build.gradle.kts` (`versionName` + `versionCode`; release.sh auto-increments the code)
- **Changelogs**: `releases/v<X.Y.Z>/VERSION_v<X.Y.Z>.md`, indexed in `releases/INDEX.md`
- **APK artifacts are CI-built** — never commit APKs; users download them from the GitHub Release
- **Release notes content**: DLNA/AirPlay casting features, ExoPlayer playback, service lifecycle, NSD/SSDP/mDNS discovery, plus the experimental-flag status of AirPlay mirroring

## Bundled Resources

### Scripts

| Script | Purpose |
|--------|---------|
| `scripts/analyze_changes.sh` | Analyze git changes since last version |
| `scripts/determine_version.sh` | Calculate next version based on change type |
| `scripts/rollback.sh` | Interactive rollback to previous version |

> The commit/tag/changelog/publish steps are owned by the repo-level `scripts/release.sh` (single entry point, keeps skill scripts from drifting out of sync with the workflow). The old `create_commit.sh` / `create_tag.sh` / `generate_changelog.sh` / `publish_release.sh` / `archive_project.sh` were removed in the 2026-10 modernization.

### References

| File | When to Read |
|------|--------------|
| `references/semantic_versioning.md` | SemVer rules and bump guidelines |
| `references/github_release_flow.md` | How the tag→workflow→Release pipeline works, monitoring, failure handling |
| `references/rollback_procedures.md` | Rollback methods and safety checks |

### Assets

| File | Purpose |
|------|---------|
| `assets/changelog_template.md` | Long-form template if you prefer writing the changelog by hand instead of editing the scaffold |

## Example Interaction

```
User: "发版"
System: Analyzes changes...
  Latest tag: v0.2.1
  Commits since: 40+ (AirPlay mirror stack, UI redesign, CI release pipeline)
  Suggestion: Minor version bump (0.2.1 → 0.3.0)

  Plan: scripts/release.sh 0.3.0
  Proceed with this plan? (confirm with "同意" or provide a different version)

User: "同意"
System:
  $ scripts/release.sh 0.3.0
  ... (first run scaffolds the changelog and stops)
  [edits releases/v0.3.0/VERSION_v0.3.0.md What's New]
  $ scripts/release.sh 0.3.0
  ... (commits, tags v0.3.0, pushes; CI builds APKs)
  $ gh run watch --workflow=release.yml
  ✅ Release v0.3.0 live with app-debug.apk + app-debug-airplay.apk
```

## Output Format

On success:
```
✅ KBTVCaster version v{x.x.x} released!

Git:
- Release commit: {hash}, tag: v{x.x.x} (pushed)

CI:
- Workflow run: {run_url}
- Artifacts: app-debug.apk, app-debug-airplay.apk on the GitHub Release

Rollback:
git checkout v{x.x.x}
git reset --hard v{x.x.x}
```
