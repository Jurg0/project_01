#!/usr/bin/env bash
set -euo pipefail

# Usage: ./release.sh v1.0.0 "Optional release message"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <version-tag> [message]"
    echo "Example: $0 v1.0.0 \"Initial release\""
    exit 1
fi

VERSION="$1"
MESSAGE="${2:-Release $VERSION}"

# Validate tag format
if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: Version must match vX.Y.Z (e.g., v1.0.0)"
    exit 1
fi

# Check for uncommitted changes
if [ -n "$(git status --porcelain)" ]; then
    echo "Error: Working directory has uncommitted changes. Commit or stash them first."
    exit 1
fi

# Check tag doesn't already exist
if git rev-parse "$VERSION" >/dev/null 2>&1; then
    echo "Error: Tag $VERSION already exists."
    exit 1
fi

echo "Building debug APK..."
./project_01_android/gradlew -p ./project_01_android assembleDebug

# Uncomment after setting up signing (Priority 16):
# echo "Building release APK..."
# ./project_01_android/gradlew -p ./project_01_android assembleRelease

APK="project_01_android/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "Error: Debug APK not found at $APK"
    exit 1
fi

APK_SIZE=$(du -h "$APK" | cut -f1)
echo "Debug APK built successfully ($APK_SIZE): $APK"

echo ""
echo "Creating tag $VERSION..."
git tag -a "$VERSION" -m "$MESSAGE"

echo "Pushing tag to origin..."
git push origin "$VERSION"

echo ""
echo "Done! Tag $VERSION pushed to origin."
echo "GitHub Actions will build the APK and create the release automatically."
echo "Check the release at: https://github.com/Jurg0/project_01/releases/tag/$VERSION"
