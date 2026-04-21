# Migration Guide: Single-User to Multi-User

Date: 2026-04-20

This guide walks through upgrading an existing single-user Suwayomi Server database to the multi-user branch.

---

## What Happens Automatically

The server runs all migrations on startup. For an existing single-user database the migration sequence is:

| Migration | What it does |
|---|---|
| `M0055` | Adds `USER_ACCOUNT` table |
| `M0056` | Adds `USER_MANGA_LIBRARY` table |
| `M0057` | Adds `USER_CHAPTER` table |
| `M0058` | Adds `user_id` column to `CATEGORY` |
| `M0059` | Adds `user_id` column to `TRACKRECORD` |
| `M0060` | Seeds first admin from config credentials, backfills ownership of categories and tracker records |
| `M0061` | Adds session table and Phase 1 indexes |
| `M0062` | Copies existing `inLibrary` manga into `USER_MANGA_LIBRARY` for first admin |
| `M0063` | Migrates `CATEGORY_MANGA` links to user-aware ownership for first admin |
| `M0064` | Copies existing chapter read/progress state into `USER_CHAPTER` for first admin |
| `M0065` | Adds user-scoped uniqueness constraint on `TRACKRECORD` |

All migrations are idempotent. Restarting mid-migration is safe — already-applied steps are skipped.

---

## Pre-Migration Checklist

1. **Back up your data directory** before upgrading. Copy the full directory including the database file (`tachidesk.mv.db` for H2 or your PostgreSQL schema).
2. Note your current `authMode` from `server.conf`. If it is `NONE`, the server will migrate it automatically to `UI_LOGIN` on first startup.
3. Note your current `authUsername` and `authPassword` from `server.conf`. These are used to seed the first admin account in `M0060`.

---

## Step-by-Step Upgrade

### Step 1 — Back up

```bash
cp -r /path/to/data-directory /path/to/data-directory.bak
```

For PostgreSQL:
```bash
pg_dump -Fc suwayomi > suwayomi_pre_multiuser.dump
```

### Step 2 — Replace the server JAR

Stop the running server, then replace the JAR with the multi-user build.

### Step 3 — Start the server

Start the server normally. Watch the log for migration output:

```
[Migration] Running M0055_AddUserAccountTable
...
[Migration] Running M0060_SeedFirstAdminAndBackfillOwnership
[Migration] Seeded first admin from config credentials.
[Migration] Backfilled N category rows to first admin (id=1).
[Migration] Backfilled N track record rows to first admin (id=1).
...
[Bootstrap] authMode was NONE, migrated to UI_LOGIN.
```

If you see errors, see the [Runbook](Multi-User-Runbook.md).

### Step 4 — Log in

Open the web UI. You will be prompted to log in. Use the credentials that were in `authUsername`/`authPassword` before upgrade — these are now the first admin account.

### Step 5 — Verify data

After login, confirm:

- Library manga are visible.
- Chapter read/bookmark states are intact.
- Categories are present with correct assignments.
- Tracker records appear for tracked manga.

### Step 6 — Create additional users (optional)

Use the admin UI or GraphQL to create USER-role accounts for other people on your network.

---

## What Carries Over

| Data | Migrated to first admin? | Notes |
|---|---|---|
| Library manga (`inLibrary`) | Yes | Copied into `USER_MANGA_LIBRARY` |
| Chapter read/progress/bookmark | Yes | Copied into `USER_CHAPTER` |
| Categories | Yes | `user_id` assigned to first admin |
| Category-manga links | Yes | Ownership scoped to first admin |
| Tracker records | Yes | `user_id` assigned to first admin |
| Manga metadata | Shared | Source metadata is not user-scoped |
| Extension/source config | Shared | Global, not user-scoped |
| Server settings | Unchanged | Per-server settings stay as-is |

---

## What Does Not Carry Over

- `authUsername`/`authPassword` from `server.conf` are used only for the first-admin seed and become irrelevant after `M0060` runs. They are not the live credentials — the seeded user account is.
- `AuthMode.NONE` is replaced automatically and cannot be restored while `multiUserBootstrapEnabled=true`.

---

## Rollback

If you need to revert to the pre-upgrade backup:

1. Stop the server.
2. Restore the data directory backup.
3. Replace the JAR with the previous single-user build.
4. Start the server.

The migrations added new tables and columns without dropping old ones. The pre-migration backup is a complete and safe restore point. Do not attempt to rollback by selectively dropping new tables on a live running database.

---

## Verifying Migration Integrity

After upgrade, you can run these queries directly against the database to verify:

```sql
-- First admin exists
SELECT id, username, role, is_active FROM USER_ACCOUNT WHERE role = 'ADMIN' ORDER BY id LIMIT 1;

-- Library backfill count matches legacy inLibrary count
SELECT COUNT(*) FROM USER_MANGA_LIBRARY;
SELECT COUNT(*) FROM MANGA WHERE in_library = true;

-- Chapter backfill count
SELECT COUNT(*) FROM USER_CHAPTER;
SELECT COUNT(*) FROM CHAPTER WHERE (is_read = true OR is_bookmarked = true OR last_page_read > 0);

-- Category ownership backfill
SELECT COUNT(*) FROM CATEGORY WHERE user_id IS NULL;  -- should be 0 if backfill succeeded
```

For H2, connect via the H2 console or a JDBC tool. For PostgreSQL, use `psql`.
