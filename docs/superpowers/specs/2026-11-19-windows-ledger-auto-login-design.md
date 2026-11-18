# Windows ledger auto-open and sign-in

## Goal

After a successful deployment, a user-triggered repair, or a successful in-place installer upgrade, open the local Wallos ledger and sign in with the administrator account already provided to the setup wizard. Do not open the ledger after a failed operation, a dry run, or a silent watchdog repair.

## Selected credential model

Store the Wallos username and password as a generic credential in Windows Credential Manager under the original installing user's Windows profile. Replace the entry only after a real deployment has created and verified the Wallos owner. A later deployment may rotate the entry after it succeeds. The setup engine must never write this credential to state JSON, job parameter/result files, environment variables, command-line arguments, URLs, or logs.

The setup process can be elevated under a different administrator account. When its Windows SID differs from `OriginalUserSid`, it must delegate Credential Manager reads and writes to a short-lived bridge started under the original user's unelevated logon. The bridge uses a random per-launch named pipe with an ACL granting the original user's SID and local Administrators access. If the SIDs match, the setup process may use Credential Manager directly. The bridge must stop when the setup server exits and must not retain plaintext credentials between requests.

## User flows

### Deployment

The deploy button opens a temporary tab synchronously to satisfy browser popup rules. After a real deployment succeeds and its verification checks pass, the wizard stores the credentials and points that tab at a local one-time login endpoint. The endpoint retrieves the credential and returns a no-store HTML form that POSTs to Wallos `login.php`; credentials never appear in the URL. Wallos redirects the tab to its local home page. Dry runs and unsuccessful deployments close the temporary tab and never save credentials.

If Credential Manager storage or retrieval fails, deployment remains complete and the wizard opens the ordinary Wallos page with a visible message. It does not claim that sign-in succeeded.

### User-triggered repair

Both the status-page repair and QR recovery repair open a temporary tab when clicked. Once the repair job succeeds, the wizard waits for the local Wallos endpoint to respond and sends the tab through the one-time login endpoint. Failed repairs close the temporary tab. Watchdog repairs stay silent and never launch a browser.

### Installer upgrade

The installer marks only an in-place upgrade of an already completed deployment for ledger handoff. After files are replaced, the launcher starts the existing watchdog task, waits for the local API and Wallos endpoints to become ready, then opens the ledger login endpoint in the user's browser. A fresh installation does not open Wallos before deployment. If services fail to recover before the timeout, the setup wizard remains available and reports the recovery failure.

## Login endpoint and secret handling

The local setup server exposes a same-origin, setup-token-protected endpoint for the browser handoff. It HTML-escapes form fields, sets `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, and a restrictive content security policy, and auto-submits a POST to `http://127.0.0.1:<wallos-port>/login.php`. Only username, password, and Wallos's `remember` field are submitted. The endpoint uses the configured Wallos port and falls back to the ordinary local home page if no stored credential is available.

Credential Manager errors are returned as generic status codes; native error detail and credential material are not logged. The setup page clears its password inputs after successful persistence. A later manual deployment replaces the saved credential only after owner creation succeeds.

## Scope and verification

No Android, sync protocol, or database changes. No background watchdog browser popups. Add Windows PowerShell 5.1 tests for Credential Manager wrappers, original-user bridge selection and ACL construction, protected one-time login response, installer upgrade gating, and deploy/repair UI handoff on success versus failure. Run the Windows CI suite and build the installer when Inno Setup is available.

## Known boundaries

Wallos local password login may be disabled by a later SSO/TOTP configuration. In that case the one-time handoff must leave the user at Wallos's ordinary login flow and show a clear setup message; the deployment itself remains healthy. Windows Credential Manager entries are controlled by the original Windows user and can be removed there.
