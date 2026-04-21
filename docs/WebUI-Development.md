# WebUI Development Guide

This document covers the local development loop for the integrated Suwayomi-WebUI fork that lives in the `webUI/` git submodule.

## Prerequisites

| Tool | Minimum version |
|------|-----------------|
| Node.js | 22 |
| npm | 10 (bundled with Node 22) |
| Java | 21 |
| Git | 2.x (submodule support) |

---

## First-time setup

### 1. Clone the server repo with the submodule

```bash
git clone --recurse-submodules https://github.com/MMajor87/Suwayomi-Server-Multi-User.git
cd Suwayomi-Server-Multi-User
```

If you already have the repo cloned without the submodule:

```bash
git submodule update --init --recursive
```

### 2. Verify the submodule

```bash
ls webUI/src   # should list the React source tree
git submodule status
```

The submodule is pinned to tag **`v20251230.01`** of the `MMajor87/Suwayomi-WebUI-MultiUser` fork. To update it to a newer commit see [Upgrading the submodule](#upgrading-the-submodule).

---

## Running the dev server

### Step 1 — Start the Suwayomi backend

From the repo root:

```bash
./gradlew :server:run
```

The server starts on **`http://localhost:4567`** by default.

### Step 2 — Start the React dev server

In a separate terminal:

```bash
cd webUI
npm install          # first time only (or after dependency changes)
npm run dev
```

The dev server starts on **`http://localhost:3000`** with hot-module replacement.

The WebUI connects to the backend using the `VITE_SERVER_URL_DEFAULT` variable defined in `webUI/.env` (auto-created from `.env.template` on first `npm run dev`). The default is `http://localhost:4567`, which is correct for local development — no further configuration needed.

If you change the server port, update `VITE_SERVER_URL_DEFAULT` in `webUI/.env` accordingly. Do not commit `.env` — it is gitignored.

---

## Building for production (embedded in the jar)

The Gradle `bundleWebUI` task builds the React app and zips it into `server/src/main/resources/WebUI.zip` so it is bundled inside the server jar.

```bash
# Build the WebUI and the full server jar
./gradlew :server:bundleWebUI :server:shadowJar
```

Or separately:

```bash
./gradlew :server:bundleWebUI   # produces WebUI.zip
./gradlew :server:shadowJar     # picks up WebUI.zip via processResources
```

The CI pipeline (`build_push.yml`, `build_pull_request.yml`, `publish.yml`) runs `bundleWebUI` automatically before `shadowJar` with `--recurse-submodules` checkout.

---

## Making changes to the WebUI

The `webUI/` directory is a git submodule pointing to `MMajor87/Suwayomi-WebUI-MultiUser`. Commit your WebUI changes to that fork, then update the submodule pointer in the server repo.

### Workflow

```bash
# 1. Make changes inside the submodule
cd webUI
git checkout -b my-feature
# ... edit files ...
git add .
git commit -m "feat: my change"
git push origin my-feature

# 2. After merging to the fork's main branch, update the pointer in the server repo
cd ..
git add webUI
git commit -m "chore: bump webUI submodule to include my change"
git push fork multi-user
```

---

## Upgrading the submodule

To pull a newer commit from the upstream WebUI fork:

```bash
cd webUI
git fetch origin
git checkout v20260101.01   # replace with desired tag or commit
cd ..
git add webUI
git commit -m "chore: upgrade webUI submodule to v20260101.01"
```

To also pull upstream changes from the original `Suwayomi/Suwayomi-WebUI`:

```bash
cd webUI
git remote add upstream https://github.com/Suwayomi/Suwayomi-WebUI.git
git fetch upstream
git merge upstream/master
# resolve any conflicts, then push to the fork and update the pointer
```

---

## Environment variables reference

These live in `webUI/.env` (gitignored, generated from `.env.template`):

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `3000` | Dev server port |
| `ALLOWED_HOSTS` | *(empty)* | Extra hostnames the dev server accepts |
| `VITE_SERVER_URL_DEFAULT` | `http://localhost:4567` | Backend URL the React app connects to |
| `VITE_SUBPATH` | *(unset)* | Optional URL subpath for development (mirrors `webUISubpath` server setting) |
| `CODEGEN_SERVER_URL_GQL` | `http://localhost:4567/api/graphql` | GraphQL endpoint used by codegen |

---

## GraphQL codegen

The WebUI uses generated TypeScript types from the server's GraphQL schema. If you add or change GraphQL queries/mutations on the server side, regenerate the types:

```bash
cd webUI
# ensure the server is running on localhost:4567
npm run gql:codegen
```

Commit the generated files (they live in `webUI/src/lib/graphql/generated/`).
