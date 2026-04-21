# Multi-User Backfill Strategy (Category + TrackRecord)

Date: 2026-04-20

## Current State

- `CATEGORY.user_id` and `TRACKRECORD.user_id` are now present as nullable ownership columns.
- Foreign keys point to `USER_ACCOUNT(id)` with `ON DELETE SET NULL`.
- Indexes exist for both new ownership columns.
- Backfill now runs in migration `M0060_SeedFirstAdminAndBackfillOwnership`.

## Backfill Trigger

Backfill executes immediately after first-admin seed logic in `M0060`.

## Backfill SQL (idempotent pattern)

```sql
-- Resolve first admin id (lowest id admin row)
-- and assign only rows that are still unowned.
UPDATE CATEGORY
SET USER_ID = (
    SELECT ID
    FROM USER_ACCOUNT
    WHERE UPPER(ROLE) = 'ADMIN'
    ORDER BY ID
    LIMIT 1
)
WHERE USER_ID IS NULL
  AND EXISTS (
    SELECT 1
    FROM USER_ACCOUNT
    WHERE UPPER(ROLE) = 'ADMIN'
);

UPDATE TRACKRECORD
SET USER_ID = (
    SELECT ID
    FROM USER_ACCOUNT
    WHERE UPPER(ROLE) = 'ADMIN'
    ORDER BY ID
    LIMIT 1
)
WHERE USER_ID IS NULL
  AND EXISTS (
    SELECT 1
    FROM USER_ACCOUNT
    WHERE UPPER(ROLE) = 'ADMIN'
);
```

## Safety Notes

- Keep `user_id` nullable until first-admin backfill migration is proven on upgrade fixtures.
- Do not make `CATEGORY.user_id`/`TRACKRECORD.user_id` non-null until all legacy rows are guaranteed owned.
- Preserve a rollback window where reads can tolerate null ownership for legacy rows.
