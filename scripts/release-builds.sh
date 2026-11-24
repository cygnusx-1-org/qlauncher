#!/bin/bash

# build.gradle.kts
BUILD_GRADLE_FILENAME="app/build.gradle.kts"

# version name
VERSION_NAME=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "${BUILD_GRADLE_FILENAME}" | head -n1)

if [ -z "$VERSION_NAME" ]; then
  echo "Error: Could not find versionName in ${BUILD_GRADLE_FILENAME}"
  exit 1
fi

# Fails when the repo has uncommitted changes to tracked files. Untracked files (changelog_override.txt
# is one) are not part of the build, so they are allowed.
require_clean() {
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "Error: qlauncher has uncommitted changes. Commit or stash them before releasing."
    git status --short --untracked-files=no
    exit 1
  fi
}

# The in-app update checker reads this manifest from master; scripts/update-manifest.py writes it
MANIFEST_FILENAME="updates/qlauncher.json"

CHANGELOG_OVERRIDE_FILENAME="changelog_override.txt"

# The GitHub release body is the override file as written, notes and all. The manifest only gets
# its "* " / "- " bullets, so a note like "v8a is the 64-bit build" stays out of the in-app changelog.
RELEASE_NOTES=""
MANIFEST_CHANGELOG_ARGS=()
if [ -f "${CHANGELOG_OVERRIDE_FILENAME}" ]; then
  RELEASE_NOTES=$(cat "${CHANGELOG_OVERRIDE_FILENAME}")

  while IFS= read -r LINE; do
    if [ -n "${LINE}" ]; then
      MANIFEST_CHANGELOG_ARGS+=(-m "${LINE}")
    fi
  done < <(sed -nE 's/^[*-][[:space:]]+//p' "${CHANGELOG_OVERRIDE_FILENAME}" | sed -E 's/[[:space:]]+$//')
fi

require_changelog() {
  if [ "${#MANIFEST_CHANGELOG_ARGS[@]}" -eq 0 ]; then
    echo "Error: ${CHANGELOG_OVERRIDE_FILENAME} is missing or has no \"* \" lines for this release."
    exit 1
  fi
}

# Section 1: everything before "./gradlew :app:assembleRelease"
1() {
  # Check the release notes before anything gets committed or tagged
  require_changelog

  if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo "Error: qlauncher is not a git repository."
    exit 1
  fi

  if git rev-parse -q --verify "refs/tags/${VERSION_NAME}" > /dev/null; then
    echo "Error: qlauncher already has tag ${VERSION_NAME}. Update versionName in ${BUILD_GRADLE_FILENAME} first."
    exit 1
  fi

  # Commit the versionName bump, unless it was already committed
  if ! git diff --quiet HEAD -- "${BUILD_GRADLE_FILENAME}"; then
    git commit -m "Updated versionName to ${VERSION_NAME}" -- "${BUILD_GRADLE_FILENAME}"
  fi

  # Refuse to tag from a dirty working tree — anything still uncommitted here
  # was not captured by the release commit, so the tag would point at a tree
  # that does not match what actually gets built and released.
  require_clean

  # Creating new tag for the new release, at the current commit
  git tag -a "${VERSION_NAME}" -m "Version ${VERSION_NAME}" || exit 1

  # Push only this tag; the GitHub release is created from it. --tags would also push any other
  # local tags.
  git push origin "refs/tags/${VERSION_NAME}" || exit 1
}

# Section 2: "./gradlew :app:assembleRelease"
2() {
  # Creating the APKs in app/build/outputs/apk/release, copied by the releaseApk task to
  # app/build/outputs/release/QLauncher_<versionName>_<abi>.apk
  ./gradlew :app:assembleRelease

  RC="${?}"

  # Check return code, and exit with the return code if it is not zero.
  if [ "${RC}" -ne 0 ]; then
    exit "${RC}"
  fi
}

# Section 3: everything after "./gradlew :app:assembleRelease"
3() {
  require_changelog

  # Build the APKs and upload them to GitHub as a new release
  scripts/release-github.sh "${RELEASE_NOTES}" || exit 1

  # The manifest checks that the release has the APKs, so it can only be written after the upload
  scripts/update-manifest.py "${MANIFEST_CHANGELOG_ARGS[@]}" || exit 1

  git commit -m "Updated ${MANIFEST_FILENAME} to ${VERSION_NAME}" -- "${MANIFEST_FILENAME}" || exit 1

  # Push the release commits (the versionName bump and the manifest) to origin
  git push
}

# Dispatch: run all sections in order by default, or an individual section
# when its name is passed as the argument.
case "${1}" in
  1) 1 ;;
  2) 2 ;;
  3) 3 ;;
  ""|all)
    1
    2
    3
    ;;
  *)
    echo "Usage: ${0} [1|2|3|all]"
    exit 1
    ;;
esac
