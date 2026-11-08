#!/usr/bin/env bash
# Publish a pre-release only after SAME COMMIT has green Android CI,
# signed APK, and signed in-place upgrade checks. Runs only on main.
set -euo pipefail

: "$GITHUB_REPOSITORY"
: "$GH_TOKEN"
: "$TARGET_SHA"
tag="v2.0.0-rc.1"
apk_name="MasterCRM-2.0.0-rc1.apk"
expected_cert="8a0568717d5e5404c02d321ca6b4e9df830abba7ad0ca5cd4e799850e77e372f"

[[ "$TARGET_SHA" =~ ^[a-f0-9]{40}$ ]] || { echo "Bad commit SHA" >&2; exit 2; }
[[ "$GITHUB_REF_NAME" == "main" ]] || { echo "Only main may publish" >&2; exit 2; }
grep -Fq "versionCode 20" app/build.gradle
grep -Fq "versionName '2.0.0-rc1'" app/build.gradle
main_sha="$(gh api "repos/$GITHUB_REPOSITORY/git/ref/heads/main" --jq '.object.sha')"
[[ "$TARGET_SHA" == "$main_sha" ]] || {
  echo "Not publishing an obsolete main commit" >&2; exit 2;
}

if gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "Candidate release already exists; never overwrite it."
  exit 0
fi
if gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$tag" >/dev/null 2>&1; then
  echo "Tag exists without a release; refusing to retarget it." >&2
  exit 2
fi

run_state() {
  local name="$1"
  jq -r --arg sha "$TARGET_SHA" --arg name "$name" \
    '[.workflow_runs[] | select(.head_sha==$sha and .name==$name)]
       | first | if .==null then "missing||" else
         (.status+"|"+(.conclusion//"")+"|"+(.id|tostring)) end' <<<"$runs"
}

signed_id=""
all_green=false
# Another workflow can finish after signed-upgrade; wait for *all three* on
# the same immutable commit. Never release on failed or missing gates.
for attempt in $(seq 1 70); do
  latest="$(gh api "repos/$GITHUB_REPOSITORY/git/ref/heads/main" --jq '.object.sha')"
  [[ "$latest" == "$TARGET_SHA" ]] || { echo "main moved; refuse stale release" >&2; exit 2; }
  runs="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs?per_page=100")"
  ci="$(run_state "Android offline preview")"
  signed="$(run_state "Signed preview APK")"
  upgrade="$(run_state "Signed upgrade data-preservation test")"
  echo "Gate $attempt: Android=$ci Signed=$signed Upgrade=$upgrade"
  if [[ "$ci" == *"|failure|"* || "$signed" == *"|failure|"* ||
        "$upgrade" == *"|failure|"* || "$ci" == *"|cancelled|"* ||
        "$signed" == *"|cancelled|"* || "$upgrade" == *"|cancelled|"* ]]; then
    echo "A required workflow failed or was cancelled" >&2; exit 1
  fi
  if [[ "$ci" == "completed|success|"* &&
        "$signed" == "completed|success|"* &&
        "$upgrade" == "completed|success|"* ]]; then
    signed_id="$(cut -d '|' -f3 <<<"$signed")"
    all_green=true
    break
  fi
  sleep 20
done
[[ "$all_green" == "true" ]] || {
  echo "Timed out: one or more gates did not finish successfully" >&2; exit 1;
}

mkdir -p release
gh run download "$signed_id" --repo "$GITHUB_REPOSITORY" \
  --name mastercrm-preview-signed-release --dir release
test -s release/app-release.apk
mv release/app-release.apk "release/$apk_name"

apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
aapt="$ANDROID_HOME/build-tools/35.0.0/aapt"
"$apksigner" verify "release/$apk_name"
cert="$("$apksigner" verify --print-certs "release/$apk_name" |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
[[ "$cert" == "$expected_cert" ]] || {
  echo "Unexpected signing certificate: aborting release" >&2; exit 1;
}
badging="$("$aapt" dump badging "release/$apk_name")"
grep -Fq "package: name='com.mastercrm.offline.preview' versionCode='20' versionName='2.0.0-rc1'" <<<"$badging"
if "$aapt" dump permissions "release/$apk_name" | grep -q 'android.permission.INTERNET'; then
  echo "An offline app must not request INTERNET" >&2; exit 1
fi
(cd release && sha256sum "$apk_name" > SHA256SUMS.txt)

latest="$(gh api "repos/$GITHUB_REPOSITORY/git/ref/heads/main" --jq '.object.sha')"
[[ "$latest" == "$TARGET_SHA" ]] || { echo "main changed" >&2; exit 2; }
gh release create "$tag" \
  "release/$apk_name#$apk_name" \
  "release/SHA256SUMS.txt#SHA256SUMS.txt" \
  --repo "$GITHUB_REPOSITORY" \
  --target "$TARGET_SHA" \
  --title "MasterCRM 2.0.0 Release Candidate 1" \
  --prerelease \
  --notes-file docs/RELEASE_NOTES_2_0_RC1.md
echo "Published $tag only after three matching successful gates."
