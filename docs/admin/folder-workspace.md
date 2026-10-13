# Folder workspace

Sign in through the Gateway. Successful login opens `/home.html`; the root URL also opens Home for signed-in users. Home links to Upload, Browse & download, My activity, and administrator-only Log dashboard and Administration according to the account's current permissions. All pages are in English and share the same 30-minute HttpOnly login cookie. Administration checks the current Oracle role and does not ask for a second login.

## Administrator workflow

1. Open Administration and create a top-level folder such as `SW001` or `semester2`.
2. Search usernames or use the user dropdown. Empty search loads up to 100 accounts; narrow the search if needed.
3. Filter folder names or choose from the folder dropdown.
4. Choose **View and download** (READ) or **Upload, view and download** (WRITE), then save. Revoke removes that folder grant. Administrators already have full access to registered folders; their folder permission controls are disabled.

The S3 bucket remains `media-migration-dev-landing` (or the configured `MMIV_BUCKET_NAME`). The portal creates a zero-byte `<folder>/` marker and registers the name in Oracle `MMIV_FOLDERS`. Existing markers and files are preserved. Creating/registering the name of an existing S3 prefix exposes that prefix only to administrators and explicitly granted users. Folder names use up to 80 ASCII letters, numbers, spaces, dots, underscores or hyphens, starting with a letter or number; trailing spaces/dots and the old `migrations`/`upload` roots are reserved. Folder creation currently supports top-level folders; local folder uploads preserve their nested paths inside one permitted folder.

## User workflow

Upload offers only folders with WRITE permission. Files offers all granted folders and lists their contents, with nested folder navigation, breadcrumbs, separate Preview buttons that open a modal, and download links; users do not enter file IDs. Files in a shared folder are readable by every account with READ for that folder, regardless of uploader. Knowing or changing an encoded file ID cannot bypass the folder check. Grant changes apply to subsequent requests, including existing sessions. Accounts without grants see **No access to folder**.

Metadata assigns `<folder>/migrations/<metadataId>/<relativePath>` inside the fixed bucket. The storage UUID prevents same-name uploads from overwriting one another; browser pages show normal relative filenames and hide bucket/key details. New transfers still require their plan's owner and current WRITE permission. Downloads, metadata reads and reports use current folder READ permission. The detailed Log dashboard is administrator-only, including its folder-specific API. Ordinary users see My activity: only their own uploads, downloads and deletions, without raw events, internal IDs or other users’ activity. Older events without an actor ID cannot be included safely. Folder uploads show bytes sent as a percentage, followed by a processing phase during storage transfer and integrity verification.

## APIs and deployment

Browser operations use `/api/v1/portal/**` at Transfer. Cookie mutations require CSRF protection; Transfer forwards the verified JWT to fixed internal User Access and Reporting routes. Direct User Access administrative APIs continue to require Bearer authentication. The file API checks the decoded key's exact registered top-level folder before contacting S3. File listing uses a fixed `<folder>/` prefix and paginates 100 S3 entries at a time.

Redeploy User Access, Discovery/Metadata, Transfer, Reporting and Gateway together. No new dependency or secret is required. Oracle creates the folder catalog idempotently at startup; existing accounts, password hashes, grants and objects are retained. Existing bucket-level grants are not translated into grants for every folder. Older objects outside registered folder prefixes remain in S3 and are not automatically moved.

Verification generates a reserved `mmiv-verification-<run UUID>` folder per run and grants only the generated test account access. Reserved folders, including legacy `verification`, are excluded from everyone’s browser folder lists and cannot be created or granted through Administration. Run-tagged S3 versions, metadata, reports and migration records are cleaned before removing the temporary folder marker, catalog entry, grant and finally the generated account. Cleanup refuses to remove the folder if unexpected objects or versions remain. Minimal closed-run markers and Mongo tombstones remain to reject late events; cleanup failure fails verification. Supplied test accounts require explicit `userAccessSmoke.destination` (or `MMIV_VERIFICATION_DESTINATION`) and existing WRITE permission to an ordinary folder; that folder and account are preserved.

## Deletion and phone use

Administrators can delete individual files, uploaded nested folders, or a registered top-level folder. The English confirmation identifies the selection. Deletion permanently removes all matching S3 versions and delete markers; root-folder deletion also removes its metadata assignments, Oracle catalog entry and every grant. Nested deletion keeps the registered root and sibling folders. Retained audit records document deletions. S3 failures stop metadata/catalog cleanup and return an error so the same operation can be retried.

Regular users can delete only files assigned to them by Metadata, while they retain WRITE permission. The backend checks both ownership and the exact assigned object key; a guessed file ID cannot bypass this. Legacy objects with no ownership assignment can be deleted only by an administrator. Grants disappearing during an admitted transfer are rechecked before subsequent work. Folder mutations and uploads are coordinated within the single deployed Transfer instance.

The folder list is compact on desktop and uses rows with touch controls on phones. English file-picker labels and validation messages replace browser-localized page controls. The operating system’s file picker and browser PDF/media controls may still follow the device language.

PWA installation and worker registration are currently commented out at the project owner’s request. The normal website remains responsive on phones. Optional PWA assets are retained for future use; no unregister step is needed because this feature was not deployed.

Invitation copying uses the Clipboard API where available, then a selected-text copy fallback on HTTP. If browser restrictions prevent both, the link stays selected with English Ctrl+C/Cmd+C instructions. Searching for an unknown account or inviting a nonexistent administrator recipient shows an account-creation prompt. Failed login advises using Create an account while retaining a generic credential failure.

## Duplicate filenames and visible feedback

New uploads preserve a unique name within their logical directory: `report.pdf`, `report (2).pdf`, `report (3).pdf`. Suffixes use ASCII parentheses and appear before the extension. The same basename in a different subfolder is permitted. Existing objects are preserved. Transfer supplies an inventory of current S3 paths, including legacy objects; Metadata chooses and reserves the final path under an Oracle folder lock. Concurrent allocations cannot choose the same reserved name. UUID storage keys are still assigned exclusively by Metadata, and Transfer validates allowed renamed assignments before writing bytes.

Name reservations are removed with file/folder/verification cleanup. Confirmed integrity-failure deletion releases the failed upload's reservation without deleting its audit/metadata record. Pending or uncertain uploads retain their reservation. Existing historical duplicates are not renamed retroactively.

System Logs uses stacked rows on phones and expandable, wrapping JSON details. File and folder uploads display a modal with actual network progress, then a processing phase for storage/integrity. **Hide progress** keeps the upload running; completion and failures reopen a result dialog. Login, signup, invitations and administrator actions use prominent English feedback. Account and folder deletion actions are separated in Administration; folder deletion still affects all files and grants for that folder.
