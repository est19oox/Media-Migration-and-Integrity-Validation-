# Folder workspace

Sign in through the Gateway. Successful login opens `/home.html`; the root URL also opens Home for signed-in users. Home links to Upload, Browse & download, Log dashboard and Administration according to the account's current permissions. All pages are in English and share the same 30-minute HttpOnly login cookie. Administration checks the current Oracle role and does not ask for a second login.

## Administrator workflow

1. Open Administration and create a top-level folder such as `SW001` or `semester2`.
2. Search usernames or use the user dropdown. Empty search loads up to 100 accounts; narrow the search if needed.
3. Filter folder names or choose from the folder dropdown.
4. Choose **View and download** (READ) or **Upload, view and download** (WRITE), then save. Revoke removes that folder grant. Administrators already have full access to registered folders; their folder permission controls are disabled.

The S3 bucket remains `media-migration-dev-landing` (or the configured `MMIV_BUCKET_NAME`). The portal creates a zero-byte `<folder>/` marker and registers the name in Oracle `MMIV_FOLDERS`. Existing markers and files are preserved. Creating/registering the name of an existing S3 prefix exposes that prefix only to administrators and explicitly granted users. Folder names use up to 80 ASCII letters, numbers, spaces, dots, underscores or hyphens, starting with a letter or number; trailing spaces/dots and the old `migrations`/`upload` roots are reserved. Folder creation currently supports top-level folders; local folder uploads preserve their nested paths inside one permitted folder.

## User workflow

Upload offers only folders with WRITE permission. Files offers all granted folders and lists their contents, with clickable previews and download links; users do not enter file IDs. Files in a shared folder are readable by every account with READ for that folder, regardless of uploader. Knowing or changing an encoded file ID cannot bypass the folder check. Grant changes apply to subsequent requests, including existing sessions. Accounts without grants see **No access to folder**.

Metadata assigns `<folder>/migrations/<metadataId>/<relativePath>` inside the fixed bucket. The storage UUID prevents same-name uploads from overwriting one another; browser pages show normal relative filenames and hide bucket/key details. New transfers still require their plan's owner and current WRITE permission. Downloads, metadata reads and reports use current folder READ permission. The Log dashboard shows migration reports/events for permitted folders; only current active administrators may query system-wide service logs.

## APIs and deployment

Browser operations use `/api/v1/portal/**` at Transfer. Cookie mutations require CSRF protection; Transfer forwards the verified JWT to fixed internal User Access and Reporting routes. Direct User Access administrative APIs continue to require Bearer authentication. The file API checks the decoded key's exact registered top-level folder before contacting S3. File listing uses a fixed `<folder>/` prefix and paginates 100 S3 entries at a time.

Redeploy User Access, Discovery/Metadata, Transfer, Reporting and Gateway together. No new dependency or secret is required. Oracle creates the folder catalog idempotently at startup; existing accounts, password hashes, grants and objects are retained. Existing bucket-level grants are not translated into grants for every folder. Older objects outside registered folder prefixes remain in S3 and are not automatically moved.

Verification uses the registered `verification` folder by default (`MMIV_VERIFICATION_DESTINATION` may override it). Managed verification registers it and grants only the generated account access. Supplied test accounts must already have WRITE to the selected folder. Run-tagged objects, plans and migration records are cleaned before account deletion; the reusable folder catalog entry and minimal closed-run markers remain.
