#!/bin/bash
# KBTVCaster release command.
#
# Usage: scripts/release.sh <X.Y.Z>        e.g. scripts/release.sh 0.3.0
#
# Flow (see .opencode/skills/kbtv-versioning/ for conventions):
#   preflight -> bump versionName/versionCode -> require changelog
#   -> release commit -> annotated tag -> push master + tag.
# Pushing the v* tag triggers .github/workflows/release.yml, which builds
# both APK variants and publishes them to a GitHub Release.

set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

VERSION="${1:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release.sh <X.Y.Z>"
TAG="v$VERSION"
DIR="releases/$TAG"
CHANGELOG="$DIR/VERSION_$TAG.md"
GRADLE=app/build.gradle.kts

# ---------- preflight ----------
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not a git repo"
# The target changelog may carry uncommitted edits (the scaffold flow expects
# a human edit between runs); everything else must be clean.
DIRTY=$(git status --porcelain | grep -v " $CHANGELOG\$" || true)
[ -z "$DIRTY" ] || die "working tree not clean — commit or stash first"
[ "$(git branch --show-current)" = "master" ] || die "not on master branch"
! git rev-parse -q --verify "$TAG" >/dev/null || die "tag $TAG already exists locally"
if git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
  die "tag $TAG already exists on origin"
fi
command -v gh >/dev/null || die "gh CLI not installed (needed to watch the release workflow)"
gh auth status >/dev/null 2>&1 || die "gh not authenticated (run: gh auth login)"
[ -f "$GRADLE" ] || die "$GRADLE not found"

TODAY=$(date +%Y-%m-%d)
LAST_TAG=$(git describe --abbrev=0 2>/dev/null || true)
if [ -n "$LAST_TAG" ]; then RANGE="$LAST_TAG..HEAD"; else RANGE="HEAD"; fi

# ---------- version bump ----------
OLD_CODE=$(grep -m1 'versionCode' "$GRADLE" | grep -o '[0-9][0-9]*')
NEW_CODE=$((OLD_CODE + 1))
sed -i "s/versionName = \"[0-9.]*\"/versionName = \"$VERSION\"/" "$GRADLE"
sed -i "s/versionCode = $OLD_CODE/versionCode = $NEW_CODE/" "$GRADLE"
grep -q "versionName = \"$VERSION\"" "$GRADLE" || die "failed to bump versionName in $GRADLE"
grep -q "versionCode = $NEW_CODE" "$GRADLE" || die "failed to bump versionCode in $GRADLE"
echo "Bumped $GRADLE: versionName=$VERSION versionCode=$NEW_CODE (was code $OLD_CODE)"

# ---------- changelog ----------
if [ ! -f "$CHANGELOG" ]; then
  mkdir -p "$DIR"
  {
    echo "# Version $VERSION"
    echo
    echo "**Release Date**: $TODAY  "
    echo "**Status**: Released  "
    echo "**Previous Version**: ${LAST_TAG:-none}"
    echo
    echo "## What's New"
    echo
    echo "<EDIT ME — summarize the changes from the commits below>"
    echo
    echo "## Recent Commits"
    echo
    echo '```'
    git log --oneline -n 40 "$RANGE"
    echo '```'
    echo
    echo "## Rollback"
    echo
    echo '```bash'
    echo "git checkout ${LAST_TAG:-<previous-tag>}"
    echo '```'
  } > "$CHANGELOG"
  git add "$GRADLE" "$CHANGELOG"
  git commit -q -m "Scaffold changelog for $TAG"
  die "scaffolded $CHANGELOG and committed the version bump.
Edit the What's New section, then rerun: scripts/release.sh $VERSION"
fi

# ---------- index + manifest ----------
if ! grep -q "## $TAG" releases/INDEX.md; then
  cat >> releases/INDEX.md <<EOF

## $TAG
- Date: $TODAY
- Changelog: [$CHANGELOG]($CHANGELOG)
- Tag: \`$TAG\` (workflow-built APKs on the GitHub Release)
EOF
fi
cat > "$DIR/manifest.json" <<EOF
{
  "version": "$VERSION",
  "tag": "$TAG",
  "date": "$TODAY",
  "builtBy": "GitHub Actions (.github/workflows/release.yml)"
}
EOF

git add "$GRADLE" releases/
git commit -q -m "Release $VERSION"

# ---------- tag + push (tag push triggers the release workflow) ----------
git tag -a "$TAG" -m "Release $VERSION"
git push origin master "$TAG"

REPO_URL=$(gh repo view --json url -q .url)
echo
echo "Released $TAG at commit $(git rev-parse --short HEAD)"
echo "APK build in progress:"
echo "  gh run watch --workflow=release.yml"
echo "  $REPO_URL/actions/workflows/release.yml"
echo "Release page (once built):"
echo "  $REPO_URL/releases/tag/$TAG"
