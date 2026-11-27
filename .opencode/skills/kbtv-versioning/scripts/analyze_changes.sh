#!/bin/bash
# Analyze git changes since last version
# Usage: ./analyze_changes.sh [--output json]

OUTPUT_FORMAT="${1:-text}"

# Get latest tag
LATEST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")

if [ "$OUTPUT_FORMAT" = "--output" ] && [ "$2" = "json" ]; then
    # JSON mode: stdout must be pure JSON (no preamble lines).
    if [ -z "$LATEST_TAG" ]; then
        COMMIT_COUNT=$(git rev-list HEAD --count)
        CHANGED_FILES=$(git diff --name-only HEAD | wc -l)
        COMMITS=$(git log --oneline -10)
    else
        COMMIT_COUNT=$(git rev-list "$LATEST_TAG"..HEAD --count)
        CHANGED_FILES=$(git diff --name-only "$LATEST_TAG" HEAD | wc -l)
        COMMITS=$(git log "$LATEST_TAG"..HEAD --oneline)
    fi
    # Escape backslashes and double quotes so commit subjects stay valid JSON
    # strings; the whole array is bracketed as JSON requires.
    RECENT_COMMITS=$(echo "$COMMITS" | head -20 | python -c "
import sys, json
items = [json.dumps(line.strip()) for line in sys.stdin if line.strip()]
print('[' + ', '.join(items) + ']')
")
    cat <<EOF
{
  "latest_tag": "$LATEST_TAG",
  "commit_count": $COMMIT_COUNT,
  "changed_files": $CHANGED_FILES,
  "recent_commits": $RECENT_COMMITS
}
EOF
else
    if [ -z "$LATEST_TAG" ]; then
        echo "No previous tag found. This is a new project."
        COMMIT_COUNT=$(git rev-list HEAD --count)
        CHANGED_FILES=$(git diff --name-only HEAD | wc -l)
        COMMITS=$(git log --oneline -10)
    else
        echo "Latest tag: $LATEST_TAG"
        COMMIT_COUNT=$(git rev-list "$LATEST_TAG"..HEAD --count)
        CHANGED_FILES=$(git diff --name-only "$LATEST_TAG" HEAD | wc -l)
        COMMITS=$(git log "$LATEST_TAG"..HEAD --oneline)
    fi
    echo "Commits since last version: $COMMIT_COUNT"
    echo "Changed files: $CHANGED_FILES"
    echo ""
    echo "Recent commits:"
    echo "$COMMITS"
fi
