# Harmonia Suite desktop shell

Phase 1 introduces the Electron boundary without moving `frontend/` or the Spring backend into
this package. The renderer only sees relative `/api/**` requests. The main process serves the
compiled Vue app through `harmonia://app/` and proxies its API requests to the active gateway.

## Manual development run

Build the frontend and backend from the repository root first:

```powershell
cd frontend
npm ci
npm run build
cd ..
.\mvnw.cmd -DskipTests package
```

Then start the desktop shell:

```powershell
cd apps/desktop
npm ci
npm run dev
```

The shell finds `target/harmonia-suite.jar` automatically. Set `HARMONIA_GATEWAY_JAR` when using a
different backend artifact, and `HARMONIA_WORKSPACE` to point at an existing development workspace.
The shell starts Spring with `--server.port=0`, consumes an identity-bound readiness marker with
the actual loopback port, waits for `/api/status`, and stops that child when the window closes.

## Installed runtime

The packaged user-facing executable is the real Electron runtime: `HarmoniaSuite.exe` on Windows
and `harmonia-suite` on Linux. It is launched directly through the installer-managed `current`
pointer (`current/desktop/...`). Electron derives the installed runtime
context from canonicalized `process.resourcesPath` and the active version's `metadata.json`; the
backend JAR, managed Java, installer helper, state root, and user-data root therefore do not require
launcher-provided `HARMONIA_*` variables. Dev-only overrides remain available for local development
and tests. Update handoff may still pass operation-scoped launch acknowledgement variables.

Supported installed installations use this direct layout with installer engine 0.1.2 or newer.
Rolling manifests requiring a newer engine are rejected through the installer's
`UpdaterUpgradeRequired` contract before an update can be staged.

## Gateway profiles

An optional `desktop.json` is read from the Electron user-data directory. Missing configuration
defaults to local mode:

```json
{
  "activeGatewayId": "local",
  "profiles": [
    { "id": "local", "mode": "local" },
    { "id": "team", "mode": "remote", "url": "https://gateway.example.test", "credentialRef": "team" }
  ]
}
```

Only `credentialRef` is accepted; tokens, passwords and authorization headers are rejected. Native
Credential Manager/Secret Service adapters are intentionally deferred to the security phase. HTTPS
is required for non-loopback remote gateways. `HARMONIA_ALLOW_INSECURE_REMOTE=1` is a development-only
override for explicit HTTP testing.

## Verification

```powershell
npm run check
```

The tests cover profile parsing, remote URL policy, the port-0 readiness handshake and failed-bind
cleanup, SPA protocol mapping and path traversal protection.
