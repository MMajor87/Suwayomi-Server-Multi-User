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
- [x] Performance sanity tests for user-scoped queries and indexes — `UserScopedQueryPerformanceTest` covers `getAccessibleMangaIds`, `getAccessibleChapterIds`, and `getUserChapterStateMap` under 500-row candidate sets with 5 s budget
- [x] Per-user tracker secret isolation tests — `TrackerSecretIsolationTest` exercises credential/token isolation, legacy-key fallback, and `migrateLegacySecretsToUser` using a stub `Tracker` subclass against the real SharedPreferences singleton

## Phase 6: WebUI Admin UX

- [x] Add an admin-only Settings menu entry: `Settings -> User Management` — implemented as server-rendered page at `/admin/users.html`; the main React SPA is a separately-deployed project and cannot be modified here
- [x] Build User Management view: list users, create user, update role/active status, deactivate/reactivate, force sign-out, delete — served at `/admin/users.html` via `AdminUsers.kte` using GraphQL API
- [x] Gate User Management route for non-admin users: server-side check in `JavalinSetup.kt` — non-admins get 403, unauthenticated SIMPLE_LOGIN users redirected to `/login.html`
- [x] Add WebUI auth-flow handling for first-run `UI_LOGIN` bootstrap/login states: `/setup.html` (`Setup.kte`) — detects zero users via `needsSetup` GraphQL query and creates initial admin; `/admin/users.html` redirects to `/setup.html` when no users exist

## Phase 7: Integrated React WebUI with Multi-User Support

Goal: fork Suwayomi-WebUI into this project, add user-aware UI features (login,
user settings, admin user management), build it as part of the Gradle pipeline, and
make it the new default bundled web UI — while keeping WEBUI / VUI / CUSTOM selectable.

### 7.1 Repository and Build Setup

- [ ] Fork `Suwayomi/Suwayomi-WebUI` to `MMajor87/Suwayomi-WebUI-MultiUser` — **manual step**: go to https://github.com/Suwayomi/Suwayomi-WebUI and click Fork; no `gh` CLI available
- [ ] Add the fork as a git submodule at `webUI/` and pin to tag `v20251230.01`: `git submodule add https://github.com/MMajor87/Suwayomi-WebUI-MultiUser.git webUI && cd webUI && git checkout v20251230.01 && cd .. && git add .gitmodules webUI && git commit -m "chore: add WebUI submodule at v20251230.01"`
- [x] Add Gradle tasks `buildWebUIApp` (runs `npm ci && npm run build` in `webUI/`) and `bundleWebUI` (zips `webUI/build/` to `server/src/main/resources/WebUI.zip`); both gracefully skip when the submodule is not initialised (`server/build.gradle.kts`)
- [x] `processResources` updated with `mustRunAfter("bundleWebUI")` — downstream tasks unchanged
- [x] CI updated (`build_pull_request.yml`, `build_push.yml`, `publish.yml`): `submodules: recursive` added to checkout; `actions/setup-node@v4` (Node 22) added; build command changed to `:server:bundleWebUI :server:shadowJar`
- [x] `Constants.kt` — added `getWebUIBuildCommit` (reads submodule HEAD SHA via `git rev-parse HEAD:webUI`, falls back to `"unknown"`); `BuildConfig` gains `WEBUI_BUILD_COMMIT` field
- [x] Dev loop documented in `docs/WebUI-Development.md`: the existing WebUI uses `VITE_SERVER_URL_DEFAULT=http://localhost:4567` for direct API access — no proxy config needed; start server → `npm run dev` in `webUI/` → hot reload on `localhost:3000`

### 7.2 Auth Layer in the React App

The existing server exposes JWT access tokens + refresh tokens via GraphQL. The React app needs to carry them.

- [ ] Add an `AuthContext` (React context + provider) that holds `accessToken`, `userId`, `role`, and `isLoggedIn`; wrap the app root with it
- [ ] Implement `POST /api/v1/auth/login` call (or existing GraphQL `login` mutation) in an `authService`; store access token in memory (not localStorage) and refresh token in an `httpOnly`-safe mechanism consistent with what the server already sets
- [ ] Add a `LoginScreen` component that is rendered when `!isLoggedIn` in place of the main app; redirect back to the originally requested route after successful login
- [ ] Add a silent token-refresh flow: intercept 401 responses from the GraphQL client, call the refresh endpoint, retry the original request once; redirect to `LoginScreen` on double-401
- [ ] Add a `LogoutButton` (header or profile menu) that calls the revoke mutation, clears the in-memory token, and navigates to `LoginScreen`
- [ ] Gate all existing API calls behind the `AuthContext` — no anonymous access to library/chapter/tracker data; existing unauthenticated flows that the current WebUI uses must be updated to pass the bearer token

### 7.3 User Settings Panel (all authenticated users)

- [ ] Add a "My Account" or "Profile" entry to the existing Settings sidebar/drawer
- [ ] Implement a `UserSettingsPanel` component with:
  - [ ] Display current username and role (read-only)
  - [ ] Change password form (current password + new password + confirm; validates policy client-side before submit; calls `updateUserAccount` mutation)
  - [ ] "Sign out of all devices" button → calls `invalidateUserSessions` mutation, then logs out locally
  - [ ] "Sign out" button → calls revoke-token mutation and redirects to LoginScreen
- [ ] Show a non-dismissible banner when the account is deactivated and the JWT is still valid (server returns `DEACTIVATED` error); force logout in that case

### 7.4 Admin User Management Panel (ADMIN role only)

Replaces / supplements the existing server-rendered `/admin/users.html` page — the React version lives inside the SPA and uses the same GraphQL API.

- [ ] Gate the "User Management" settings entry behind `role === 'ADMIN'`; non-admin users never see the menu item
- [ ] Implement a `UserManagementPanel` component with a paginated user list table showing: username, role, active status, created date
- [ ] Add "Create User" modal/drawer: username, password, role selector (ADMIN / USER), active toggle; calls `createUserAccount` mutation; validates password policy client-side
- [ ] Add inline edit row actions:
  - [ ] Edit username and role (calls `updateUserAccount` mutation)
  - [ ] Deactivate / Reactivate toggle (calls `deactivateUserAccount` / `reactivateUserAccount` mutation)
  - [ ] Force sign-out (calls `invalidateUserSessions` mutation for that user)
  - [ ] Delete (calls `deleteUserAccount` mutation; confirmation dialog; disabled when only active admin remains)
- [ ] Disable destructive actions (delete / deactivate / role-downgrade) on the currently authenticated admin's own account when they are the last active admin; mirror the server-side guard in the UI
- [ ] Display server-side error messages (last-admin guard, duplicate username, password policy) as form-level validation feedback

### 7.5 First-Run Setup Flow in the React App

The existing `/setup.html` JTE page handles first-run. The React app should handle the same flow natively.

- [ ] On app load, call the `needsSetup` GraphQL query (no auth required); if `true`, redirect to a `SetupScreen` component before showing `LoginScreen`
- [ ] `SetupScreen` renders a single-step form: username + password + confirm for the initial admin account; calls `setupInitialAdminUser` mutation; on success, transitions to `LoginScreen`
- [ ] After the React setup flow is in place, decide whether to keep the JTE `/setup.html` page as a fallback for non-SPA clients or remove it; document the decision

### 7.6 WebUIFlavor Registration and Default Change

- [ ] Add a new `WebUIFlavor` enum value `BUNDLED` (or rename the existing embedded path) that points to the locally-built WebUI rather than the GitHub-release download path
- [ ] Change the default value of `serverConfig.webUIFlavor` from `WEBUI` (GitHub download) to `BUNDLED` (local build) so the multi-user WebUI is served out of the box with no extra download
- [ ] Keep `WEBUI`, `VUI`, and `CUSTOM` selectable; when any of those is chosen, the download-from-GitHub path in `WebInterfaceManager` is unchanged
- [ ] Update `WebInterfaceManager.extractBundledWebUI()` to serve the `BUNDLED` flavor's zip and skip the GitHub update-check for it (version is pinned at build time via `WEBUI_BUILD_COMMIT`)
- [ ] Add a migration: if an existing install has `webUIFlavor = WEBUI` and the user has never changed it from default, migrate it to `BUNDLED` on first startup with the new build; otherwise leave it unchanged (respects explicit user choice)
- [ ] Expose the active `webUIFlavor` and the embedded build commit in the server's `about` GraphQL query so the UI can display "Built-in (multi-user)" vs. "Suwayomi-WebUI r2643" etc.

### 7.7 Keep JTE Admin Pages as Fallback

The server-rendered `/admin/users.html`, `/setup.html`, and `/login.html` JTE pages remain in place.

- [ ] Confirm JTE pages still work correctly when `BUNDLED` flavor is active (they are served at distinct routes and are independent of the SPA)
- [ ] Add a link/banner in `/admin/users.html` pointing to the new React "User Management" panel for users who navigate there directly
- [ ] Evaluate whether `/login.html` is still needed once the React `LoginScreen` is in place; keep it for `SIMPLE_LOGIN` mode where the React app may not intercept the auth flow

### 7.8 Testing

- [ ] Unit tests for `AuthContext`: initial state (unauthenticated), login success/failure, silent refresh on 401, logout
- [ ] Unit tests for `UserSettingsPanel`: password change submission, "sign out all devices" button, deactivated-account banner
- [ ] Unit tests for `UserManagementPanel`: user list renders, create modal validates policy, delete confirmation, last-admin guard disables delete button
- [ ] Unit tests for `SetupScreen`: renders when `needsSetup = true`, hides when `needsSetup = false`, form submission calls correct mutation
- [ ] E2E test (Playwright or Cypress): full login → user settings → password change → re-login with new password
- [ ] E2E test: admin creates a user, deactivates them, verifies deactivated user cannot log in
- [ ] E2E test: first-run setup flow (no users → SetupScreen → create admin → LoginScreen → library)

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
