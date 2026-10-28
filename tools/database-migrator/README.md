# Reviewed migration inputs

The split tool accepts only a successful flat source at **V49**. Upgrade older sources through the normal Flyway migrations first; V48 and older layouts are no longer compatible with the current ownership manifest. Future flat versions remain rejected until their schema and data migrations are reviewed.

Reviewed additions map as follows:

| Flat migration | Owned / merged migration |
| --- | --- |
| V44 local contract void | business V7 |
| V45 onboarding attempts | identity V5 |
| V46 counterparty visibility | contract V6 / business V8 |
| V47 frozen provider initiator | contract V7 / business V9 |
| V48 inventory model | trade V4 / business V10 |
| V49 unsigned signing cancellation recovery | contract V8 / business V11 |

The merge tool retains its prior reviewed inputs and adds contract V8: trade V3/V4, contract V4/V5/V7/V8, settlement V1/V2. Each accepted owned history must have the expected migration count and no failures. Older reviewed contract inputs must lack the new cancellation-intent table; the target initializes that table empty. Contract V8 must contain it, and all of its rows are copied and verified. Other versions, including contract V6, require a separate review.

The table inventory, empty-target requirement, paused-writer gate, read-only source connections, row/content verification, and undo-log checks remain unchanged. Version acceptance does not permit an online migration or reuse of an existing target database.

## Historical local-void data

The business V7 migration runs while initializing the empty target, before copying source rows. Merging an old owned source therefore does **not** retroactively cancel copied legacy pending `RESUME` requests or finish copied approved `VOID` requests. Operators must separately review these historical records before a real cutover. This compatibility change does not alter that data-handling behavior.

The isolated core smoke path first migrates its flat fixture through V49, including V44, then splits and merges it. Its historical void data has already passed the flat V44 migration. No production migration is performed by this test or this version-guard change.

`ReviewedMigrationVersionsTest` verifies exact supported versions, rejection of older/future versions and failed or incomplete owned histories. The real split/merge copy and database-isolation checks remain in `IndependentServicesIT`.
