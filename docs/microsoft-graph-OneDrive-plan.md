# Microsoft Graph Personal-Account Authentication Plan

Last updated: 2026-09-06

Status: In Progress. Authentication, read-only OneDrive verification, and the restart-safe paired receipt-image/Version 2 JSON export are implemented with focused unit coverage. An Entra application registration and physical-device export verification remain required before this workstream can be marked ✅ Done.

## Goal

Authenticate the Android app to Microsoft Graph with one personal Microsoft account, verify the existing OneDrive handoff folder, and export each confirmed receipt as an image-first Version 2 pair.

Success requires:

- Explicit interactive connection to a personal Microsoft account.
- Silent account restoration and token renewal through MSAL.
- Read-only verification of `Documents/2_Others/Expenses_finance/logs`.
- Read-only verification or actionable missing-folder handling for `Documents/2_Others/Expenses_finance/receipt_images`.
- Explicit disconnect that clears Microsoft credentials without deleting local receipt or export data.
- Actionable, non-sensitive errors for cancellation, expired sessions, network failures, consent failures, missing folders, rate limits, and configuration errors.

## Microsoft Entra Registration

Create an Android public-client registration in the Microsoft Entra admin center with these settings:

1. Select **Personal Microsoft accounts only** as the supported account type.
2. Add the Android platform with package name `com.hugo.smartexpense.app` and the debug signing-certificate hash used to build the Pixel test APK.
3. Copy the portal-generated redirect URI into `android-app/src/main/res/raw/auth_config_single_account.json`.
4. Copy the unencoded signature hash into the `BrowserTabActivity` path in `android-app/src/main/AndroidManifest.xml`. The JSON redirect path must use the portal-provided URL encoding; the manifest path must not be URL encoded.
5. Enable public-client flows under Authentication advanced settings.
6. Add only the delegated Microsoft Graph permission `Files.ReadWrite`. Do not add application permissions, `Files.ReadWrite.All`, or a client secret.
7. Before a release or Google Play build, add a separate Android platform redirect for every production signing certificate. Do not replace the debug redirect.

The client ID and redirect URI are public application configuration, not credentials. The checked-in all-zero client ID and `REPLACE_WITH_...` redirect values intentionally keep authentication disabled until the real registration values are supplied.

## Android Implementation

- Pin `com.microsoft.identity.client:msal:8.4.2` and configure MSAL with `account_mode: SINGLE`, the `PersonalMicrosoftAccount` audience, PII logging disabled, and the delegated `Files.ReadWrite` scope.
- Keep MSAL behind `GraphAuthenticationClient`. UI and future upload code must use this boundary rather than MSAL callbacks directly.
- Represent the account with a stable MSAL identifier and safe display name. Never persist access tokens, refresh tokens, authorization headers, or exception text containing account data.
- Restore the current account at ViewModel startup. Before each Graph operation, request a token silently for the cached account and its authority; MSAL owns token caching and refresh.
- Interactive authentication occurs only after **Connect** or **Reconnect**. A silent `MsalUiRequiredException` changes the state to interaction-required and never launches UI automatically.
- Display initializing, signed-out, signed-in, interaction-required, and failure states in the Compose Microsoft account panel. Disable duplicate actions while an operation is active.
- **Disconnect** calls the MSAL single-account sign-out API and resets only authentication UI state.

## Read-Only OneDrive Verification

After silent token acquisition, call:

```text
GET https://graph.microsoft.com/v1.0/me/drive/root:/Documents/2_Others/Expenses_finance/logs?$select=id,name,folder,parentReference
```

Encode each path segment independently. Treat the request as successful only when Graph returns a drive item containing the `folder` facet. Do not create folders or upload content in this step.

Failure behavior:

| Result | User recovery |
| --- | --- |
| `401` or silent interaction required | Reconnect explicitly, then retry. |
| `403` | Reconnect and approve `Files.ReadWrite`. |
| `404` | Confirm the folder exists at the configured OneDrive-relative path. |
| `429` | Honor numeric `Retry-After` guidance before retrying. |
| Network failure | Preserve local data and retry when connected. |
| Invalid payload or other service failure | Report a safe generic failure and allow retry. |

## Paired Receipt Export

Implemented on 2026-09-06 in the Android app. The app retains a local normalized JPEG and Room-backed export record so retry reuses the original identity and paths after a process restart.

For each confirmed expense, create one stable expense ID and export timestamp in the Android device's local timezone. Derive and persist these paths before starting either upload:

```text
Image: Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg
JSON:  Documents/2_Others/Expenses_finance/logs/expense_YYYYMMDD_HHmmss_<shortExpenseId>.json
```

The image shall be a normalized JPEG strictly below 200 KB. The Version 2 JSON shall contain the exact OneDrive-root-relative image path in required field `receiptImageRelativePath`.

Publication order and retry rules:

1. Persist the expense ID, export timestamp, both paths, and pending export status locally.
2. Resolve or idempotently create the `receipt_images/YYYY-MM` folder hierarchy.
3. Upload the receipt JPEG first. Repeating this step for the same expense targets the same path.
4. After image success, upload the JSON under a non-final temporary name such as `.uploading`.
5. Commit the JSON to its final `.json` name only after the complete payload is present.
6. Treat the final JSON as the commit marker consumed by Hermes.
7. On interruption or Graph failure, retain the same local export identity and paths for retry; never generate a replacement expense ID or timestamp.
8. If the image succeeds but JSON publication fails, retry the JSON against the same path. An unreferenced deterministic image may remain temporarily, but retry must reuse it rather than create a duplicate.

The implementation must map authentication failures, rate limits, network failures, insufficient storage, missing folders, and name/path conflicts to recoverable export states without exposing a final JSON prematurely.

## Test and Acceptance Checklist

- ✅ Done: Unit tests cover cached-account restoration, connect/disconnect, silent verification, interaction-required recovery, URL/path encoding, folder-facet validation, Graph status mapping, numeric `Retry-After`, and token redaction from returned errors.
- ✅ Done: `:android-app:testDebugUnitTest` passes.
- ✅ Done: `:android-app:assembleDebug` passes with MSAL `8.4.2`.
- Pending: Replace the placeholder client ID and redirect values with the Entra-generated debug registration.
- Pending: On the physical Pixel 7, connect a personal Microsoft account and approve delegated file access.
- Pending: Verify the existing `logs` folder without creating or modifying OneDrive content.
- Pending: Kill and restart the app, then verify silent account restoration and folder access without another prompt.
- Pending: Disconnect and reconnect, confirming local receipt/review data remains intact.
- Pending: Add and validate the production or Google Play signing redirect before release.
- ✅ Done: Implemented and unit-tested normalized JPEG generation, deterministic paired paths, idempotent folder creation, image-first upload, temporary Version 2 JSON upload, final-name commit, recoverable status mapping, and retry identity preservation.
- Pending: Verify one complete paired export and an interrupted retry on the physical Pixel 7 with the real Entra registration.

Requirements coverage:

- REQ-A-008: Uses the confirmed OneDrive-root-relative destination; user configuration beyond the confirmed MVP path remains part of the upload/export step.
- REQ-A-010: Code path is implemented; completion awaits real app registration and device validation.
- REQ-A-006, REQ-A-007, REQ-A-009, and REQ-A-011 through REQ-A-017: Implemented in the confirmed-receipt export pipeline; physical-device acceptance remains pending.
- REQ-H-006 and REQ-H-007: Implemented through required Version 2 image-path correlation and image-first temporary/final JSON publication.
- NFR-002: Tokens, authorization headers, and PII-bearing authentication errors are not logged or retained in UI state.
- NFR-004: Rate limiting and OneDrive availability are treated as retryable.
- NFR-008: Export identity and paths remain stable across restart and retry.
- NFR-007: Authentication and Graph failures provide explicit recovery actions.

## References

- [MSAL Android overview](https://learn.microsoft.com/en-us/entra/msal/android/)
- [Configure an MSAL Android app](https://learn.microsoft.com/en-us/entra/msal/android/configure-your-app)
- [Acquire tokens with MSAL Android](https://learn.microsoft.com/en-us/entra/msal/android/acquire-tokens)
- [Microsoft Graph permissions reference](https://learn.microsoft.com/en-us/graph/permissions-reference)
- [Address OneDrive items by path](https://learn.microsoft.com/en-us/graph/onedrive-addressing-driveitems)
