# Phase 9–10 production lifecycle

This checkpoint combines the operator-facing lifecycle and release packaging around the
transaction engines from Phases 2–8. The setup CLI remains a thin orchestrator: repair,
uninstall, stable launch, OS integration, and release publication call the existing
`StateStore`, `InstallationLock`, `BuildPipeline`, `ActivationEngine`, and detached-launch
primitives.

## Operations and safety boundaries

The production commands are `install`, `update`, `check-update`, `repair`, and `uninstall`.
The source repository and product version are product-controlled constants; fixture repositories
are available only through Rust test seams. Every mutating operation acquires the one per-user
installation lock before recovery, detection, or any filesystem mutation.

Repair rebuilds the exact trusted current commit and never follows a moving branch. Uninstall
first requests the current desktop session to stop, waits for the matching session to disappear,
then removes only validated installer-owned roots. User data is preserved by default and can be
removed only with the explicit `--remove-user-data` operation. If a durable review block exists,
update/install/repair fail closed; uninstall may still remove known application roots after
stopping the desktop, but ambiguous user-data removal remains blocked.

The stable launcher is installed as `bin/HarmoniaSuite.exe` on Windows and
`bin/harmonia-suite` on Linux. It resolves `current` through `ActivationEngine`, constructs the
same managed-Java runtime contract as the desktop handoff, and launches the immutable desktop
without inheriting arbitrary environment variables.

## OS integration

Windows integration is per-user: a Start Menu shortcut, optional Desktop shortcut, and HKCU
uninstall registration point to the stable launcher. Linux integration writes an XDG desktop entry
and an application icon below the user's XDG data root. All integration files are derived from
`InstallationPaths` and are removed idempotently during uninstall.

## Release contract

Release CI targets Windows x64 and Linux x64 only. Tagged releases publish only the installer and
stable launcher distribution; they do not drive the rolling application target. Authenticode is
optional and the published Windows metadata says whether binaries are signed. Ed25519 signing of
rolling manifests is mandatory and fail-closed. The separate `workflow_run` rolling publication
uses an exact green commit from `main`, a monotonic immutable generation history, and stable aliases
updated last at `angelicaproject.github.io/HarmoniaSuite/rolling/{windows,linux}`. Each candidate
must remain in `main` history and be equal to or a descendant of the latest published target;
late ancestor completions are no-ops, so the alias never moves backward. Update identity
is the exact commit object ID plus generation; the display product version is only a snapshot
label. Portable ZIP/MSI and macOS artifacts are intentionally out of scope.

The Electron desktop payload is the product runtime. An XivExdUnpacker is not required by this
architecture and is deliberately unsupported; introducing one would require a separately reviewed
source, digest, and format contract.
