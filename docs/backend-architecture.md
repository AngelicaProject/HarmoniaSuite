# Canonical backend architecture

New product capabilities are bounded contexts (`source`, `workspace`, `translation`,
`rebase`, `pack`, `jobs`, and `ai`). They get their own package-by-feature tree; new
global `service/` or `repository/` buckets are not canonical architecture.

The canonical source context uses four explicit layers:

```text
com.harmoniasuite.source.api
    -> com.harmoniasuite.source.application
        -> com.harmoniasuite.source.domain
com.harmoniasuite.source.infrastructure -> application/domain
```

The API layer owns HTTP controllers, request/response DTOs, validation, and MapStruct
mapping. Application services own use cases, commands, models, exceptions, and ports.
Domain records and value types contain business invariants and have no Spring, Jackson,
JDBC, or servlet dependency. Infrastructure owns Spring wiring, JDBC repositories,
filesystem storage, HXS reading, Atlas integration, and scheduling adapters.

The role vocabulary is intentional:

* Controllers and API DTOs are transport adapters.
* Mappers use MapStruct at API object boundaries; JDBC and binary streaming mappings stay explicit.
* Services coordinate application use cases and policy.
* Repositories persist structured SQL projections.
* Storage persists opaque files or blobs.
* Atlas clients/inspectors cross external verification boundaries.
* Processors execute one work item; dispatchers submit work; maintenance services recover and clean up.
* Domain values such as `Sha256Id`, `Sha256Digest`, enums, `UUID`, `Instant`, and `Clock` carry technical semantics explicitly.

Dependencies are named by role (`snapshotService`, `snapshotRepository`, `artifactStorage`,
`atlasInspector`). Ambiguous names such as `store`, `registry`, `manager`, and `helper` are
not used for new boundaries.

Validation is split by responsibility: Jakarta Validation checks API shape and static
cross-field rules, domain values enforce invariants, application services enforce runtime
policy, and SQL constraints remain database defense in depth. Application services accept
commands and domain values, never REST DTOs.

Source ingestion is a single application flow: the upload service stages an HXS file,
the Atlas-backed import service verifies its metadata once, and the materializer imports
that verified file into the canonical database. Filesystem and JDBC access are exposed to
application code only through ports. Upload lifecycle timestamps come from an injected
`Clock`, and source identities use typed SHA-256 values.

Materialization owns its one-database-transaction semantics. A filesystem operation and a
SQL operation are never described as one atomic transaction; artifact moves and SQL
registration retain their existing restart-safe/idempotent behavior separately.

Production objects have one constructor with all required collaborators. There are no null
fallback dependencies or production fake implementations; tests provide fakes explicitly.

Canonical schema migrations live under `src/main/resources/db/core/**`. The historical
migrations under `src/main/resources/db/migration/**` are compatibility specifications
for a future offline importer; the running application does not execute them. The legacy
`data/harmonia.db` database is likewise not opened by the canonical runtime.

The canonical runtime has no dependency on the old global `controller`, `service`,
`dto`, or source `store`/`artifact`/`upload` buckets. System endpoints live under
`com.harmoniasuite.system`, while source endpoints live under `com.harmoniasuite.source`.
