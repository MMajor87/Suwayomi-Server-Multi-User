# Multi-User Operational Runbook

Date: 2026-04-20

This runbook covers diagnosis and recovery for migration failures and multi-user incidents.

---

## Migration Failures

### Symptom: Server fails to start — migration error in log

**Common causes and fixes:**

#### Cause: `USER_ACCOUNT` already exists with partial schema
A previous run created the table but did not complete. Migrations are idempotent — restart the server. If the error repeats:
1. Stop the server.
2. Connect to the database.
3. Check `FLYWAY_SCHEMA_HISTORY` (or equivalent) for the failed version.
4. If the failed migration left a partial table, drop it manually and let the migration re-create it.
5. Restart the server.

#### Cause: First-admin seed fails because `authUsername`/`authPassword` is blank
`M0060` attempts to seed the first admin from the config credentials. If these are blank, the seed is skipped and the server enters first-run setup mode instead.
- Open the web UI and complete the setup wizard to create the first admin manually.

#### Cause: Backfill finds no admin user in `USER_ACCOUNT`
The backfill in `M0060` only runs when at least one ADMIN row exists. If seed was skipped and setup was not completed, categories and tracker records remain unowned (nullable `user_id`).
- Complete the setup wizard to create the first admin.
- Restart the server — `M0060` is idempotent and will re-attempt the backfill.

#### Cause: Unique constraint violation during `M0062` (library backfill)
A duplicate `(user_id, manga_id)` already exists in `USER_MANGA_LIBRARY`. This should not happen on a clean upgrade but can occur if the server was started and stopped mid-migration.
- Check `USER_MANGA_LIBRARY` for duplicate rows and remove the extras.
- Restart the server.

#### Cause: Unique constraint violation during `M0064` (chapter backfill)
Same pattern as above for `USER_CHAPTER`. Remove duplicate `(user_id, chapter_id)` rows and restart.

---

### Symptom: Server starts but library/categories/progress are empty after migration

**Diagnosis:**
```sql
-- Check if backfill ran
SELECT COUNT(*) FROM USER_MANGA_LIBRARY;
SELECT COUNT(*) FROM USER_CHAPTER;
SELECT COUNT(*) FROM CATEGORY WHERE user_id IS NOT NULL;
```

**Recovery:** If counts are zero but legacy data exists in `MANGA.in_library` or `CHAPTER`:
1. Confirm the first admin user exists: `SELECT id FROM USER_ACCOUNT WHERE role='ADMIN' LIMIT 1;`
2. Run the backfill SQL manually (from `Multi-User-Backfill-Strategy.md`).
3. For library and chapter tables, run equivalent manual inserts from legacy columns.
4. Restart the server to allow migrations to re-verify.

---

### Symptom: Migration interrupted mid-run (power loss, OOM kill, etc.)

All migrations are idempotent. Simply restart the server. The migration framework tracks completed versions and will resume from the last successful step.

If the database file is corrupt after a hard crash (H2 only):
1. Restore from the pre-upgrade backup.
2. Restart cleanly.

---

## Auth and Login Incidents

### Symptom: No one can log in — "Incorrect username or password"

1. Check that the user account exists and is active:
   ```sql
   SELECT id, username, role, is_active FROM USER_ACCOUNT;
   ```
2. If all users are inactive or the table is empty, connect via database tooling and set one admin active:
   ```sql
   UPDATE USER_ACCOUNT SET is_active = true WHERE role = 'ADMIN' ORDER BY id LIMIT 1;
   ```
3. If you need to reset a password without the web UI, hash a new password using the same PBKDF2-SHA256 scheme or temporarily set a plaintext value — the rehash path will upgrade it on next login.
4. Restart the server after any direct DB changes.

---

### Symptom: Admin locked out — last active admin was accidentally deactivated via DB

The application prevents this via `enforceAdminSafety`, but a direct DB edit can bypass it.

**Recovery:**
```sql
UPDATE USER_ACCOUNT SET is_active = true, token_version = token_version + 1
WHERE role = 'ADMIN'
ORDER BY id
LIMIT 1;
```

Increment `token_version` to invalidate any stale tokens, then log in with the recovered admin.

---

### Symptom: User reports they cannot log in after a password change or admin deactivation

This is expected behavior. When a password is changed or a user is deactivated, all refresh tokens and sessions are immediately invalidated. The user must log in again with the new password.

If the user was erroneously deactivated, reactivate via GraphQL:
```graphql
mutation {
  reactivateUser(input: { userId: <id> }) {
    user { id isActive }
  }
}
```

---

### Symptom: "Too many login attempts" error

The login endpoint is rate-limited to 12 attempts per minute per IP+username combination. The window resets after 60 seconds. Instruct the user to wait 60 seconds and try again.

If legitimate traffic is being rate-limited (e.g., behind a proxy with a shared IP), check whether the server can see real client IPs through the proxy's forwarded headers.

---

### Symptom: Stale tokens after user deletion

If a user was deleted directly in the database (bypassing the application), their tokens may appear valid until expiry. To invalidate:
1. Increment `token_version` for any surviving tokens — the user row is gone, so JWT validation will fail at the user lookup step anyway.
2. Ensure the JWT secret has not been compromised. If it has, rotate the secret in `server.conf` and restart — all outstanding tokens will immediately become invalid.

---

## Cross-User Data Isolation Incident

### Symptom: User A can see User B's library/progress

**Immediate steps:**
1. Check the GraphQL query context resolution — confirm the user context is being read from the JWT, not from a fallback path.
2. Check for any data that was backfilled to the wrong user ID in `USER_MANGA_LIBRARY` or `USER_CHAPTER`.
3. If a query is returning unscoped results, check the GraphQL resolver and service layer for missing `userId` filter on the relevant table.

**Diagnosis queries:**
```sql
-- Check library ownership distribution
SELECT user_id, COUNT(*) FROM USER_MANGA_LIBRARY GROUP BY user_id;

-- Check chapter ownership distribution
SELECT user_id, COUNT(*) FROM USER_CHAPTER GROUP BY user_id;
```

If all rows have the same `user_id` after a multi-user deployment (not just a fresh single-user migration), data was likely backfilled incorrectly. Reassign rows as appropriate per user.

---

## Data Retention After User Deletion

When a user is deleted via the application, cascade rules apply:

- `USER_MANGA_LIBRARY` rows for that user are deleted.
- `USER_CHAPTER` rows for that user are deleted.
- `CATEGORY` rows owned by that user have `user_id` set to NULL (foreign key `ON DELETE SET NULL`).
- `TRACKRECORD` rows owned by that user have `user_id` set to NULL.
- Shared manga metadata (`MANGA`, `CHAPTER` source data) is not affected.

**Orphaned category/trackrecord rows** (those with `user_id IS NULL` after deletion) are retained in the database. An admin can re-assign or clean them up as needed:

```sql
-- View orphaned categories
SELECT id, name FROM CATEGORY WHERE user_id IS NULL;

-- Re-assign to a specific user
UPDATE CATEGORY SET user_id = <target_user_id> WHERE user_id IS NULL;

-- Or delete orphaned categories
DELETE FROM CATEGORY WHERE user_id IS NULL;
```

---

## Download/Update Queue Incidents

The download and update queues are globally shared. If a user reports their downloads are not running:

1. Check if the queue is paused globally (admin can pause/resume via the UI).
2. Check if the user has permission to enqueue — only active users with valid sessions can submit to the queue.
3. Check the server log for download errors specific to the manga/chapter.

Queue event payloads are user-filtered — a regular user will only see events for manga in their own library. If events appear to be missing for a specific user, verify their library membership:
```sql
SELECT manga_id FROM USER_MANGA_LIBRARY WHERE user_id = <user_id>;
```

---

## Checking Audit Logs

Audit events are in the application log under the `SecurityAudit` logger. To find them:

```bash
grep "SecurityAudit" /path/to/server.log
# or filter by event type
grep "login_attempt success=false" /path/to/server.log
grep "unauthorized_access" /path/to/server.log
grep "admin_action" /path/to/server.log
```

Audit log fields:
- `login_attempt`: `success`, `username`, `sourceIp`, `reason`
- `admin_action`: `adminUserId`, `action`, `details`
- `unauthorized_access`: `method`, `path`, `sourceIp`, `userId`, `reason`

---

## Emergency Procedures

### Full reset to single-user (rollback)

1. Stop the server.
2. Restore the pre-upgrade backup (data directory or database dump).
3. Roll back to the previous server JAR.
4. Start the server.

### Rotating the JWT secret

1. Update the JWT secret in `server.conf`.
2. Restart the server.
3. All outstanding access tokens and refresh tokens become invalid immediately. All users must log in again.

### Resetting the bootstrap gate

If `multiUserBootstrapEnabled` was accidentally set to `false` and `AuthMode.NONE` is live:
1. Set `server.multiUserBootstrapEnabled=true` in `server.conf`.
2. Restart the server — the startup guard will run and migrate `AuthMode.NONE` to a secure mode.
