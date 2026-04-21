# Multi-User Phase 0 Baseline

Date: 2026-04-20

## 1) Auth Flow Inventory

Current auth behavior is mode-driven and still single-user in core identity handling.

- `AuthMode.NONE` and `AuthMode.BASIC_AUTH` resolve to `UserType.Admin(1)`.
- `AuthMode.SIMPLE_LOGIN` uses a session-cookie check and resolves to `UserType.Admin(1)` when valid.
- `AuthMode.UI_LOGIN` uses JWT (cookie/header/query token), but successful verification still resolves to `UserType.Admin(1)`.
- GraphQL login/refresh endpoints issue JWTs from global `authUsername/authPassword`, not user-table credentials.

Primary files:

- `server/src/main/kotlin/suwayomi/tachidesk/server/user/UserType.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/global/impl/util/Jwt.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/graphql/mutations/UserMutation.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/server/JavalinSetup.kt`

## 2) DB and User-Coupled Field Inventory

The current schema stores user-like state globally.

| Table | Current user-coupled state | Multi-user issue |
|---|---|---|
| `MangaTable` | `inLibrary`, `inLibraryAt` | Library membership is global |
| `ChapterTable` | `isRead`, `isBookmarked`, `lastPageRead`, progress fields | Read/progress/bookmark state is global |
| `CategoryTable` | category definitions | No `user_id` ownership |
| `CategoryMangaTable` | category links | Links are not user-scoped |
| `TrackRecordTable` | tracker progress/status | Tracking rows are not user-scoped |
| `TrackSearchTable` | tracker search cache | Shared cache needs boundary rules |

Primary files:

- `server/src/main/kotlin/suwayomi/tachidesk/manga/model/table/MangaTable.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/model/table/ChapterTable.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/model/table/CategoryTable.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/model/table/CategoryMangaTable.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/model/table/TrackRecordTable.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/model/table/TrackSearchTable.kt`

## 3) MVP Touch Points (Exact Files/Controllers)

Auth/session/bootstrap:

- `server/server-config/src/main/kotlin/suwayomi/tachidesk/server/ServerConfig.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/server/Migration.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/server/user/UserType.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/global/impl/util/Jwt.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/graphql/mutations/UserMutation.kt`

Library/progress/categories/tracking:

- `server/src/main/kotlin/suwayomi/tachidesk/manga/controller/MangaController.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/controller/CategoryController.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/controller/TrackController.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/impl/*` (services/repositories using current global fields)

Non-REST surfaces:

- `server/src/main/kotlin/suwayomi/tachidesk/opds/OpdsAPI.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/opds/controller/OpdsV1Controller.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/graphql/GraphQL.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/graphql/controller/GraphQLController.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/graphql/server/TachideskGraphQLContextFactory.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/graphql/server/subscriptions/ApolloSubscriptionProtocolHandler.kt`

Queues/events:

- `server/src/main/kotlin/suwayomi/tachidesk/manga/impl/download/DownloadManager.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/controller/DownloadController.kt`
- `server/src/main/kotlin/suwayomi/tachidesk/manga/controller/UpdateController.kt`

## 4) Migration Plan, Ordering, and Rollback Notes

Planned order:

1. Startup bootstrap guard (AuthMode hardening + first-run multi-user gate).
2. Introduce `UserTable` and seed first admin from existing single-user config.
3. Introduce `UserMangaLibraryTable` and `UserChapterTable`.
4. Add `user_id` ownership to categories and tracker records.
5. Backfill old global state to first admin user.
6. Cut service/controller reads/writes over to user-scoped tables.
7. Remove or deprecate global user-coupled columns after stability window.

Rollback strategy:

- Keep additive schema migrations first (new tables/columns before removal).
- Run backfills idempotently with uniqueness constraints and upsert-safe logic.
- Preserve old columns until cross-user tests pass and migration verification is complete.
- Keep startup guard independent from schema changes so security hardening is not rollback-coupled to DB moves.

## 5) Phase 0 Gate Implemented

Startup gate and secure-mode migration are now wired in config + migration startup path.

- `server.multiUserBootstrapEnabled` (default: `true`)
- `server.authModeNoneMigrationTarget` (default: `UI_LOGIN`)
- If `authMode` is `NONE` at startup and gate is enabled, it is migrated to a secure mode.
- Allowed secure targets are `UI_LOGIN` and `SIMPLE_LOGIN`; invalid target values fall back to `UI_LOGIN`.
- A first-run bootstrap marker is persisted in `SharedPreferences` key `multi_user_bootstrap.version`.

## 6) Global vs User Boundary Matrix

| Component | Scope | Notes |
|---|---|---|
| Downloader queue (`DownloadManager`) | Global | Keep global queue; enforce user permissions on queue actions/output |
| Updater scheduling/execution | Global | Keep global scheduler; scope visible events/results |
| Manga metadata/source cache | Global | Shared canonical metadata |
| Library membership | User | Move to user-manga relation table |
| Chapter read/bookmark/progress | User | Move to user-chapter relation table |
| Categories + category links | User | Add ownership and user-scoped mappings |
| Tracker records/secrets | User | User-scoped records and secret storage |
| Auth credentials/session identity | User | Real user IDs in token/session context |
| Websocket/GraphQL subscription payloads | User-filtered | Broadcast model may remain global, payloads must be filtered |
| OPDS catalog views | User-filtered | Enforce identity and user-scoped views |

## 7) Non-REST Auth/Isolation Audit

- OPDS endpoints already require user context, but currently resolve through single-user assumptions.
- Websocket endpoints set user attributes, but event payload scoping is still global in queue/update flows.
- GraphQL HTTP and WS have user context injection, but identity currently maps to `Admin(1)` and needs real user IDs.
- Subscription connection-init token handling exists and is a key path for user-id propagation work.

## 8) User Lifecycle Policy (Draft)

- Deactivate:
  - User cannot authenticate or refresh tokens.
  - Existing sessions/tokens should be invalidated.
  - Data retained by default.
- Reactivate:
  - Authentication re-enabled.
  - User data remains intact.
- Delete:
  - Soft-delete preferred first for admin recovery and auditability.
  - Hard-delete requires explicit admin action and must define cascade behavior for user-owned rows.
- Retention:
  - User-owned operational data (library/progress/categories/tracking secrets) follows deletion policy.
  - Shared global content metadata should not be deleted when one user is removed.
