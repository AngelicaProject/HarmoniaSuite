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
the operation fails closed.

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

Release CI targets Windows x64 and Linux x64 only. It builds from an exact commit, runs the full
Rust/desktop verification matrix, produces `HarmoniaSetup.exe`/`harmonia-setup` and the stable
launchers, hashes every published artifact, and creates the signed manifest only after artifact
publication inputs are complete. Missing signing credentials or failed signature verification are
fatal; no unsigned manifest is published. Portable ZIP/MSI and macOS artifacts are intentionally
out of scope.
