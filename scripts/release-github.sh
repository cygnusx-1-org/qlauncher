#!/bin/bash

GITHUB_REPO="cygnusx-1-org/qlauncher"

BUILD_GRADLE_FILENAME="app/build.gradle.kts"

VERSION_NAME=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "${BUILD_GRADLE_FILENAME}" | head -n1)

if [ -z "$VERSION_NAME" ]; then
  echo "Error: Could not find versionName in ${BUILD_GRADLE_FILENAME}"
  exit 1
fi

# The releaseApk task in app/build.gradle.kts copies the APKs here, named after the version and ABI
APK_DIR="app/build/outputs/release"
ASSEMBLE_TASK=":app:assembleRelease"

# Only these two go on the release; scripts/update-manifest.py points the in-app updater at them.
# The x86 and universal APKs built next to them, and older versions' APKs, stay behind.
APKS=(
  "${APK_DIR}/QLauncher_${VERSION_NAME}_arm64-v8a.apk"
  "${APK_DIR}/QLauncher_${VERSION_NAME}_armeabi-v7a.apk"
)

# The same two APKs are also copied, under names without the version, onto the release tagged
# "latest", so a link or Downloader code always gets the current build, e.g.
#   https://github.com/cygnusx-1-org/qlauncher/releases/download/latest/QLauncher_arm64-v8a.apk
LATEST_TAG="latest"

# Without keystore.properties the release build is signed with the debug key, and still builds.
# Such an APK can't update a release install, so it must never reach a release.
if [ ! -s "keystore.properties" ]; then
  echo "Error: keystore.properties is missing or empty, so the APKs would be signed with the debug key."
  exit 1
fi

# Guard the tree before uploading anything to GitHub. The APK filenames carry no commit, so the
# only way to know they match the release is to build them from the tagged commit, unmodified.
if ! git rev-parse --git-dir > /dev/null 2>&1; then
  echo "Error: qlauncher is not a git repository."
  exit 1
fi

if ! git rev-parse -q --verify "refs/tags/${VERSION_NAME}" > /dev/null; then
  echo "Error: qlauncher has no tag ${VERSION_NAME}. Run scripts/release-builds.sh 1 first."
  exit 1
fi

if [ "$(git rev-parse HEAD)" != "$(git rev-parse "${VERSION_NAME}^{commit}")" ]; then
  echo "Error: qlauncher is not at tag ${VERSION_NAME}, so the APKs would not be built from the released commit."
  exit 1
fi

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Error: qlauncher has uncommitted changes, so the APKs would not match tag ${VERSION_NAME}."
  git status --short --untracked-files=no
  exit 1
fi

CHANGELOG_OVERRIDE_FILENAME="changelog_override.txt"

RELEASE_NOTES="${1}"
# Fall back to changelog_override.txt when no release notes were passed on the command line, so a
# direct `scripts/release-github.sh` invocation still picks up this release's notes.
if [ -z "${RELEASE_NOTES}" ] && [ -f "${CHANGELOG_OVERRIDE_FILENAME}" ]; then
  RELEASE_NOTES="$(cat "${CHANGELOG_OVERRIDE_FILENAME}")"
fi

if [ -z "${RELEASE_NOTES}" ]; then
  echo "Error: release notes are empty and ${CHANGELOG_OVERRIDE_FILENAME} was not found or is empty." >&2
  exit 1
fi

echo "Release notes set to: $RELEASE_NOTES"

./gradlew "${ASSEMBLE_TASK}" || exit 1

for APK in "${APKS[@]}"; do
  if [ ! -f "${APK}" ]; then
    echo "Error: ${APK} was not built."
    exit 1
  fi
done

# Create the "latest" release the first time, before this version's. Discoverium picks the newest
# release by publish date, and replacing assets doesn't change that date, so "latest" stays older
# than every versioned release only if it is published first. --latest=false keeps GitHub's
# "Latest" badge on the versioned releases.
if ! gh release view "${LATEST_TAG}" --repo "${GITHUB_REPO}" > /dev/null 2>&1; then
  gh release create "${LATEST_TAG}" \
    --repo "${GITHUB_REPO}" \
    --target master \
    --latest=false \
    --title "Latest release" \
    --notes "The newest APKs under names without the version, for links and Downloader codes that always get the current build. The release notes are on the versioned releases." || exit 1
fi

# A release asset is named after the file it was uploaded from, so the APKs are copied to their
# names without the version first. --clobber deletes the old asset before uploading the new one,
# so each link 404s for the seconds its upload takes. This goes before this version's release:
# uploading again is harmless, while creating that release twice fails, so a failure anywhere here
# is fixed by running the script again.
LATEST_DIR=$(mktemp -d -p /var/tmp) || exit 1
trap 'rm -rf "${LATEST_DIR}"' EXIT

for APK in "${APKS[@]}"; do
  APK_NAME=$(basename "${APK}")
  cp "${APK}" "${LATEST_DIR}/${APK_NAME/"_${VERSION_NAME}_"/_}" || exit 1
done

if ! gh release upload "${LATEST_TAG}" "${LATEST_DIR}"/*.apk --repo "${GITHUB_REPO}" --clobber; then
  echo "Error: could not upload the APKs to the ${LATEST_TAG} release."
  exit 1
fi

# --verify-tag: the tag must already be on GitHub, or gh would create it from master instead
gh release create "${VERSION_NAME}" "${APKS[@]}" \
  --repo "${GITHUB_REPO}" \
  --verify-tag \
  --title "${VERSION_NAME}" \
  --notes "${RELEASE_NOTES}" || exit 1
