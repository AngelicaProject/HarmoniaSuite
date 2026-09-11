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
The shell chooses a free loopback port, starts Spring with explicit address/port arguments, waits
for `/api/status`, and stops that child when the window closes.

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

The tests cover profile parsing, remote URL policy, dynamic port allocation, SPA protocol mapping
and path traversal protection.
