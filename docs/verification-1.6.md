# MasterCRM 1.6 Professional UX verification

## Baseline 1.5

Main commit `c3c04e12b483c38902e2f7718db70da5fdef5e43`, merged PR #19:

- [Android CI, unit/Lint/build + emulator](https://github.com/xatabihc/MASTERCRM/actions/runs/42380861697): success.
- [Signed APK](https://github.com/xatabihc/MASTERCRM/actions/runs/42380861730): success.
- [Signed upgrade with data retention](https://github.com/xatabihc/MASTERCRM/actions/runs/42380861748): success (0.5 → 1.5; synthetic client).
- Downloaded signed artifact independently verified using SDK 35 apksigner and aapt: `com.mastercrm.offline.preview`, versionCode 15, versionName 1.5.0.
- Certificate SHA-256: `8a0568717d5e5404c02d321ca6b4e9df830abba7ad0ca5cd4e799850e77e372f`.

## 1.6 implementation

[PR #20](https://github.com/xatabihc/MASTERCRM/pull/20), branch `feature/1.6-professional-ux`.

System bars, cutouts and IME use AndroidX Insets. Floating form windows use adjustResize and scroll the focused field into view. Main search temporarily gains the header/navigation space while typing. Controls have at least 48 dp targets, content-sized navigation/period tabs, wrapping financial columns and readable dark primary buttons.

Activity state preserves editor text, cursor, spinners, client selection, list search/filter/page/scroll and open detail cards. Draft restoration never calls financial writes. Password forms are excluded. Tapping outside a data-entry form no longer discards it. Explicit Cancel/Back still dismisses a draft.

Client history renders at most 100 jobs per page instead of inflating up to 1000 cards. Expense actions originating beyond the first page retain the originating job. Package ID, schema version 2, backup formats and pinned signing key are unchanged.

## Validation status

Local JDK/Gradle 8.11.1, SDK 35: 19 JVM tests passed; Lint passed with no errors (existing warnings about dependency versions, framework ExifInterface and extraction rules); debug and instrumentation APKs built successfully. CI uses the project's exact Gradle 8.9 configuration.

Regression instrumentation covers actual visible IME and field bounds, client/order/payment/expense drafts after Activity recreation, selected tab/search restoration and financial invariants. Existing SQLite, backup, camera, debt and workflow tests remain enabled. CI now runs API 34 (100% font) and 35 (130% font), including actual portrait/landscape rotation, enabling the soft keyboard even with emulator hardware input.

[PR #20](https://github.com/xatabihc/MASTERCRM/pull/20) merged as `14655ffb74e6054fb23205e13976ca95f9d90493` after [all required checks passed](https://github.com/xatabihc/MASTERCRM/actions/runs/42384103021): 19 JVM tests, Lint/build, 45 instrumentation tests on each Android API.

[Signed APK release](https://github.com/xatabihc/MASTERCRM/actions/runs/42384657471) succeeded. Independently verified version 16/1.6.0, package, pinned certificate, v2/v3 signature and absence of INTERNET permission. APK SHA-256: `95172f580bbd810efa7a4f4f732b14ff42c5742d1c807984ffb0fe3b6d55ff98`.

[Signed upgrade matrix](https://github.com/xatabihc/MASTERCRM/actions/runs/42384657461) passed both immutable 0.5 → 1.6 and 1.5 → 1.6 scenarios. Both retained their synthetic client using in-place installation. The synthetic script refuses real devices. No signing material is stored locally or committed.

The first post-merge API 35 run exposed an Espresso root-selection race after Activity recreation: it waited for the background Activity to focus although a restored modal dialog owned focus. Test queries now explicitly select dialog roots, and expense draft verification repeats recreation three times. This follow-up changes tests/documentation only; production code and APK version are unchanged. Final CI/device evidence is recorded in the follow-up PR.

## Safe continuation / installation

1. Check all PR #20 jobs; fix failures and rerun. Merge only after verify and both instrumentation jobs succeed on the final commit.
2. On main verify Android CI, Signed preview APK and both Signed upgrade jobs. Download `mastercrm-preview-signed-release`, verify pinned certificate and package/version 16.
3. On an authorized connected device inspect installed package/version/certificate. Never downgrade, replace a mismatched signature, or remove the app.
4. Install only the signed release using `adb -s SERIAL install -r /path/app-release.apk`. Debug APKs and synthetic instrumentation must not be installed on a user's application.
5. Record release run URLs, artifact URL and device installation result here. Extensive real-photo databases and exhaustive real-device accessibility/rotation checks remain separate verification work.
