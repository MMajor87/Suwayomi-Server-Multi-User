# Multi-User Mode Implementation TODO

Source: https://github.com/Yukaii/Suwayomi-Server/pull/1
Phase 0 baseline: `docs/Multi-User-Phase0-Baseline.md`
Backfill strategy: `docs/Multi-User-Backfill-Strategy.md`

## Scope Lock

- [x] Confirm scope remains authenticated users only (no anonymous/public library mode)
- [x] Confirm roles remain `ADMIN` and `USER` only
- [x] Confirm migration policy: existing single-user data maps to first admin account
- [x] Confirm we are targeting local/private deployment only
- [x] Keep downloader/updater queues globally shared at system level
- [x] Auto-migrate `AuthMode.NONE` to a secure mode during startup bootstrap
- [x] Choose exact secure target mode for `AuthMode.NONE` migration (`UI_LOGIN` default, `SIMPLE_LOGIN` optional) and fallback behavior

## Phase 0: Technical Baseline (1 week)

- [x] Inventory current auth flow in this branch (`AuthMode`, `UserType`, login/session/JWT code paths)
- [x] Inventory current DB tables and user-coupled fields (`Manga`, `Chapter`, `Category`, `CategoryManga`, `TrackRecord`)
- [x] Document exact files/controllers to touch for MVP
- [x] Produce a migration plan doc with migration ordering and rollback notes
- [x] Add a feature flag or migration gate for first-run multi-user bootstrap
- [x] Define global-vs-user boundary matrix (queues, schedulers, settings, caches, websocket broadcasts)
- [x] Audit non-REST surfaces for auth/isolation (`OPDS`, websocket endpoints, GraphQL subscriptions)
- [x] Define user lifecycle policy (deactivate, reactivate, delete, and data retention/cascade rules)

## Phase 1: Foundation (3-4 weeks)

- [x] Add `UserTable` migration with username, password hash, role, active flag, timestamps
- [x] Add `UserMangaLibraryTable` migration with unique `(user_id, manga_id)`
- [x] Add `UserChapterTable` migration with unique `(user_id, chapter_id)`
- [x] Add `user_id` to `CategoryTable` and backfill to first admin user
- [x] Add `user_id` to `TrackRecordTable` and backfill ownership to seeded admin/default active user
- [x] Update auth/session/JWT to carry real user IDs (remove hardcoded single-user assumptions)
- [x] Implement startup migration path from `AuthMode.NONE` to secure mode with upgrade-safe defaults
- [x] Implement first-run setup flow for creating initial admin user
- [x] Implement admin user CRUD endpoints (create/list/update/delete)
- [x] Add password hashing + verification and password policy checks (PBKDF2-based)
- [x] Implement token/session lifecycle controls (logout invalidation, refresh rotation/revocation, admin-forced sign-out)
- [x] Implement user lifecycle operations (deactivate/reactivate/delete) with explicit authorization checks
- [x] Make migrations restart-safe and idempotent for Phase 1 additions
- [x] Add indexes/constraints for user-scoped hot query paths and uniqueness guarantees

## Phase 2: Library Isolation (3-4 weeks)

- [x] Refactor library domain/service to use `UserMangaLibraryTable` instead of global `inLibrary`
- [x] Update REST endpoints for user-scoped library operations
- [x] Update GraphQL queries/mutations for user-scoped library operations
- [x] Implement migration from existing global library state into first admin’s user library rows
- [x] Ensure manga metadata stays shared while library membership is user-specific
- [x] Add authorization checks for owner/admin access on library endpoints
- [x] Keep downloader queue globally shared while enforcing per-user permission checks for queue actions
- [x] Scope websocket/GraphQL queue payloads to allowed data for requesting user/admin context

## Phase 3: Reading Progress and Categories (3-4 weeks)

- [x] Refactor chapter read/bookmark/progress logic to `UserChapterTable`
- [x] Update chapter REST endpoints to read/write per-user progress
- [x] Update chapter GraphQL resolvers for per-user progress
- [x] Refactor categories to be user-scoped (`CategoryTable.user_id` + user-scoped mappings)
- [x] Replace or adapt category-manga mapping to user-aware associations
- [x] Implement migration of old chapter progress/bookmarks into first admin user records
- [x] Implement migration of existing categories/category links into first admin user scope

## Phase 4: Tracking and Polish (2-3 weeks)

- [x] Refactor tracker records to be user-scoped in reads/writes and uniqueness rules
- [x] Update tracker controllers and GraphQL to require user context
- [x] Backfill existing tracker rows to first admin user
- [x] Migrate tracker secret storage to user-scoped keys/storage (remove global tracker-token collisions)
- [x] Add audit logging for login attempts, admin actions, and unauthorized access attempts
- [x] Add rate limits for auth-sensitive routes and admin operations
- [x] Write/update operator docs for local multi-user setup and admin lifecycle
- [x] Write migration guide for existing single-user databases
- [x] Add operational runbook for migration failures, recovery, and multi-user incident handling

## Phase 5: Testing

### 5.1 Auth, Users, and Sessions

- [x] Unit tests for password hashing, policy validation, and rehash detection (`PasswordSecurityTest`)
- [x] Unit tests for user CRUD: create, update, deactivate, reactivate, delete, and role guards (`UserCrudAndRoleTest`)
- [x] Unit tests for `authenticateUser`, `requireAdminUser`, and last-active-admin safety guard
- [x] Unit tests for JWT generation, verification, and user-identity resolution (`JwtUserIdentityTest`)
- [x] Tests for `AuthMode.NONE` startup auto-migration and secure bootstrap path (`MigrationBootstrapTest`)
- [x] Tests for bootstrap migration seeding at least one active admin (`UserAccountBootstrapMigrationTest`)
- [x] Schema migration tests: `token_version` column, `UserRefreshTokenTable` and its indexes (`UserSessionSchemaMigrationTest`)
- [x] Schema migration tests: `CategoryTable.user_id` and `TrackRecordTable.user_id` columns, indexes, and FKs (`UserOwnershipSchemaMigrationTest`)
- [x] `invalidateUserSessions` revokes existing access tokens (`UserAccountAuthPhase1Test`)
- [x] Refresh token rotation — new token issued, old token rejected after rotation (`UserAccountAuthPhase1Test`)
- [x] Password change and deactivation increment `tokenVersion` and invalidate existing JWTs (`UserCrudAndRoleTest`)
- [x] Using an access token as a refresh token is rejected
- [x] Explicit token revocation without rotation (revoke without issuing a replacement)

### 5.2 Library Isolation

- [x] Per-user library membership: add, remove, idempotency (`UserScopedLibraryTest`)
- [x] Two-user isolation: user A's library does not affect user B's (`UserScopedLibraryTest`)
- [x] `isMangaInAnyLibrary` reflects aggregate membership across all users (`UserScopedLibraryTest`)
- [x] `getMangaLibraryState` returns correct `(inLibrary, inLibraryAt)` per user (`UserScopedLibraryTest`)
- [x] Legacy `MangaTable.inLibrary` flag synced correctly on add/remove (`UserScopedLibraryTest`)
- [x] `getAccessibleMangaIds`: USER restricted to own library, ADMIN sees all (`UserScopedLibraryTest`)
- [x] `getAccessibleChapterIds`: USER restricted to chapters of library manga, ADMIN sees all (`UserScopedLibraryTest`)
- [x] `requireMangaAccess` / `requireChapterAccess` throw `ForbiddenException` for unauthorized USER (`UserScopedLibraryTest`)
- [x] Download queue filtered to user's accessible manga; ADMIN sees full queue (`UserScopedLibraryTest`)

### 5.3 Chapter Progress and Bookmarks

- [x] `getUserChapterStateMap` returns per-user state; missing chapter returns defaults (false/0)
- [x] `setUserProgress` upserts correctly — second call updates, does not duplicate rows
- [x] `withUserState` overlays per-user read/bookmark/progress onto a chapter data class
- [x] Two-user isolation: user A and user B have independent read/bookmark/progress for the same chapter
- [x] `isRead`, `isBookmarked`, and `lastPageRead` each update independently without clobbering other fields

### 5.4 Categories

- [x] User A's categories are not visible to user B (`Category.getCategoryList(userId)` is user-scoped)
- [x] `getCategoryById` scope: category owned by user A is invisible to user B, visible to user A
- [x] `updateCategory` with wrong `userId` is a no-op (does not modify user A's category)
- [x] `removeCategory` with wrong `userId` is a no-op (does not delete user A's category)
- [x] DEFAULT category (id=0) is visible to all users when it exists
- [x] Resolved gap: M0063 now targets `CATEGORYMANGA` (matching the ORM table), and `UserScopedCategoryTest` covers `createCategory`, `getCategoryList`, `getCategoryById`, and `CategoryMangaTable.userId` isolation directly in H2

### 5.5 Tracker Records

- [x] `getTrackRecordsByMangaId(mangaId, userId=A)` does not return records belonging to user B (`UserScopedTrackRecordTest`)
- [x] Two-user isolation: direct `TrackRecordTable` inserts per user are scoped correctly on read (`UserScopedTrackRecordTest`)
- [x] Note: tracker ops requiring external API calls (login, bind, update) are out of scope for unit tests

### 5.6 User Lifecycle and Data Retention

- [x] Deactivated user JWT rejected (`JwtUserIdentityTest`)
- [x] Deactivated user cannot authenticate (`UserCrudAndRoleTest`)
- [x] Reactivated user can authenticate again (`UserCrudAndRoleTest`)
- [x] Deleting a user cascades to `UserMangaLibraryTable`, `UserChapterTable`, and `UserRefreshTokenTable` rows (`UserCrudAndRoleTest`)

### 5.7 Global Queues and Scoped Payloads

- [x] Download queue filtered to user's library manga; ADMIN receives full queue (`UserScopedLibraryTest`)
- [x] `filterDownloadStatusForUser` scopes the status queue correctly (`UserScopedLibraryTest`)
- [x] `filterUpdateStatusForUser` scopes update status to user's library manga; ADMIN sees all (`UserScopedLibraryTest`)
- [x] `filterUpdateUpdatesForUser` scopes update events to user's library manga; ADMIN sees all (`UserScopedLibraryTest`)

### 5.8 Cross-Domain Integration

- [x] Two users, same manga: independent library membership, chapter progress, and category state verified together in a single scenario (`UserScopedCrossDomainIntegrationTest`)
- [x] Cross-user authorization denial: user B cannot read user A's chapter progress state (returns defaults, not A's values) (`UserScopedCrossDomainIntegrationTest`)
- [x] Cross-user authorization denial: user B's category mutations do not affect user A's categories (`UserScopedCrossDomainIntegrationTest`)
- [x] Admin can manage users and inspect all library-accessible resources (covered by `UserCrudAndRoleTest` + admin-path tests in `UserScopedLibraryTest`)
- [x] H2 test suite seed → migrate → verify constitutes a smoke test on every run

### 5.9 Security Primitives (gaps identified post-Phase 4)

- [x] `ActionRateLimiter` window boundary and threshold tests for login, refresh, and admin mutation routes (`ActionRateLimiterTest` + `UserMutationSecurityAndRateLimitTest`)
- [x] `SecurityAudit` event emission: assert `login_attempt`, `admin_action`, and `unauthorized_access` events are emitted on the correct triggers (`UserMutationSecurityAndRateLimitTest`)

### 5.10 Deferred — Requires Integration Environment

The following require infrastructure beyond the H2 unit test setup and are explicitly deferred:

- [x] Migration tests on sample pre-multi-user DB snapshots (`LegacySnapshotMigrationTest` + `fixtures/pre-multi-user/legacy_full_snapshot.sql`)
- [x] Tests for migration restart safety — partial-migration simulation added in `MigrationRestartSafetyTest` (interrupt/resume around M0058/M0059)
- [x] Non-REST surface isolation (`OPDS`, websocket endpoints, GraphQL subscriptions) — covered by `NonRestSurfaceIsolationIntegrationTest` (HTTP server + WS/GraphQL integration assertions)
- [ ] Performance sanity tests for user-scoped queries and indexes — requires a load framework and stable row-count baselines
- [ ] Per-user tracker secret isolation tests — tracker auth secrets live in Android SharedPreferences (per-userId key); no preference-isolation test infra currently exists

## Phase 6: WebUI Admin UX

- [x] Add an admin-only Settings menu entry: `Settings -> User Management` — implemented as server-rendered page at `/admin/users.html`; the main React SPA is a separately-deployed project and cannot be modified here
- [x] Build User Management view: list users, create user, update role/active status, deactivate/reactivate, force sign-out, delete — served at `/admin/users.html` via `AdminUsers.kte` using GraphQL API
- [x] Gate User Management route for non-admin users: server-side check in `JavalinSetup.kt` — non-admins get 403, unauthenticated SIMPLE_LOGIN users redirected to `/login.html`
- [x] Add WebUI auth-flow handling for first-run `UI_LOGIN` bootstrap/login states: `/setup.html` (`Setup.kte`) — detects zero users via `needsSetup` GraphQL query and creates initial admin; `/admin/users.html` redirects to `/setup.html` when no users exist

## Open Decisions (must close before Phase 2 completion)

- [ ] Setup UX: confirm required setup wizard behavior for first-run migration
- [x] Migration trigger: auto-on-startup/upgrade bootstrap for MVP
- [x] Secure target mode for `AuthMode.NONE` migration (`UI_LOGIN` default; `SIMPLE_LOGIN` configurable) and compatibility fallback
- [ ] Category policy: confirm soft limit threshold and behavior
- [ ] Client compatibility policy: identify which clients must be supported in first release
- [ ] Backup policy: confirm full-instance backup behavior for multi-user data

## Exit Criteria (MVP)

- [ ] Multiple authenticated users can run on one server with isolated library/progress/categories/tracking
- [ ] Admin can create/manage users and perform basic administration safely
- [ ] Existing single-user data migrates without loss into first admin account
- [ ] Core API/GraphQL paths enforce per-user authorization
- [ ] Critical tests pass for isolation, migration, and auth flows
- [ ] `AuthMode.NONE` is automatically migrated to a secure mode at startup with no bypass paths
- [ ] Global downloader/updater queues behave safely under per-user authorization and scoped event output
- [ ] Session/token revocation and user lifecycle controls are validated in automated tests
