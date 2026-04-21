# Multi-User Operator Guide

Date: 2026-04-20

This guide covers deploying, configuring, and administering a Suwayomi Server instance in multi-user mode for a local/private network.

---

## Prerequisites

- Suwayomi Server built from the `multi-user` branch.
- An existing data directory (or a fresh one for new installs).
- Network access limited to trusted users — multi-user mode is designed for local/private deployments only.

---

## First-Run Bootstrap

On first startup after upgrade, the server performs automatic bootstrap:

1. **AuthMode hardening** — if `authMode` is `NONE`, the server migrates it to the configured secure target (default: `UI_LOGIN`). This cannot be bypassed.
2. **First-admin setup** — if the user table is empty, the server waits for an admin account to be created via the setup endpoint before accepting regular traffic.
3. **Data backfill** — existing library, chapter progress, categories, and tracker records are assigned to the first admin account.

The bootstrap gate is controlled by `server.multiUserBootstrapEnabled` (default `true`). Do not disable this in production.

---

## Configuration Reference

These settings live in `server.conf` (or environment overrides):

| Setting | Default | Description |
|---|---|---|
| `server.authMode` | `UI_LOGIN` | Active auth mode. Set automatically on first run. |
| `server.multiUserBootstrapEnabled` | `true` | Enable/disable the startup bootstrap gate. |
| `server.authModeNoneMigrationTarget` | `UI_LOGIN` | Secure mode to migrate to when `authMode=NONE`. Accepted values: `UI_LOGIN`, `SIMPLE_LOGIN`. Invalid values fall back to `UI_LOGIN`. |

### Auth Modes

| Mode | Description |
|---|---|
| `UI_LOGIN` | JWT-based login via the web UI. Recommended for multi-user. |
| `SIMPLE_LOGIN` | Cookie session login. Simpler but fewer token lifecycle controls. |
| `BASIC_AUTH` | HTTP Basic Auth. Single-user compatible only. |
| `NONE` | No auth. Automatically migrated away at startup. |

---

## Creating the Initial Admin Account

If no users exist, call the GraphQL setup mutation before any other operations:

```graphql
mutation {
  setupInitialAdmin(input: { username: "admin", password: "YourStr0ngPass!" }) {
    user {
      id
      username
      role
    }
  }
}
```

Password policy requires at least 10 characters, one uppercase letter, one lowercase letter, and one digit.

The setup endpoint is rate-limited to 5 attempts per minute per IP.

---

## Managing Users (Admin Operations)

All user management goes through GraphQL mutations. Only ADMIN-role users may call these.

### Create a user

```graphql
mutation {
  createUser(input: { username: "alice", password: "Al1cePass99!", role: USER, isActive: true }) {
    user { id username role isActive }
  }
}
```

### List users

```graphql
query {
  users {
    id
    username
    role
    isActive
    createdAt
  }
}
```

### Update a user (rename, change role, reset password)

```graphql
mutation {
  updateUser(input: { userId: 2, username: "alice2", password: "NewPass2026!" }) {
    user { id username role }
  }
}
```

Changing a password or deactivating a user immediately invalidates all active sessions and refresh tokens for that user.

### Deactivate a user

```graphql
mutation {
  deactivateUser(input: { userId: 2 }) {
    user { id isActive }
  }
}
```

A deactivated user cannot log in. Their data is preserved.

### Reactivate a user

```graphql
mutation {
  reactivateUser(input: { userId: 2 }) {
    user { id isActive }
  }
}
```

### Delete a user

```graphql
mutation {
  deleteUser(input: { userId: 2 }) {
    success
  }
}
```

Deletion is permanent. User-owned data (library entries, chapter progress, categories, tracker records) is cascade-deleted. Shared manga metadata is not affected.

### Force sign-out a user

```graphql
mutation {
  invalidateUserSessions(input: { userId: 2 }) {
    success
  }
}
```

This increments the user's token version and revokes all stored refresh tokens, terminating all active sessions immediately.

---

## Roles

| Role | Capabilities |
|---|---|
| `ADMIN` | Full access to all user management and admin operations. Can see/act on any resource. |
| `USER` | Access to their own library, progress, categories, and tracker records only. Cannot manage other users. |

There must always be at least one active ADMIN. The server prevents removal or deactivation of the last active admin.

---

## Rate Limits

| Endpoint | Limit |
|---|---|
| Login | 12 attempts per minute per IP+username |
| Refresh token | 20 attempts per minute per IP |
| Initial setup | 5 attempts per minute per IP |
| Admin mutations | 60 per minute per IP |

Exceeding a limit returns an error. Wait for the window to expire before retrying.

---

## Global Queues

The download queue and update scheduler are global (not per-user). All users can submit downloads/updates subject to their role. Queue event payloads are scoped to what the requesting user is authorized to see.

---

## Audit Logging

Security-relevant events are written to the server log at `WARN` or `INFO` level under the `SecurityAudit` logger:

- `login_attempt` — every login attempt (success and failure), with source IP and reason.
- `admin_action` — user CRUD, session invalidation, role changes.
- `unauthorized_access` — 401/403 hits with path, method, and source IP.

To capture these separately, configure your log appender to filter on logger name `SecurityAudit`.

---

## Backup

Back up the entire data directory. The H2 (or PostgreSQL) database contains all user accounts, library memberships, chapter progress, categories, and tracker records. A consistent backup requires either stopping the server or using a database-level snapshot.

For PostgreSQL deployments, use `pg_dump` with a consistent snapshot option.
