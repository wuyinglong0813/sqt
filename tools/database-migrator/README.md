# Reviewed migration inputs

The split tool accepts a successful flat source at **V52 or V53**. Upgrade older sources through the normal Flyway migrations first, including the approval processor history backfill, verified-phone targeted member invitations, and open invitations with application identity gates. Future flat versions remain rejected until their schema and data migrations are reviewed.

Reviewed additions map as follows:

| Flat migration | Owned / merged migration |
| --- | --- |
| V44 local contract void | business V7 |
| V45 onboarding attempts | identity V5 |
| V46 counterparty visibility | contract V6 / business V8 |
| V47 frozen provider initiator | contract V7 / business V9 |
| V48 inventory model | trade V4 / business V10 |
| V49 unsigned signing cancellation recovery | contract V8 / business V11 |
| V50 approval processor history | trade V5 / business V12 |
| V51 targeted member invites and verified phone marker | identity V6 |
| V52 explicit open invitations and pending application provenance | identity V7 |
| V53 standalone purchase direction | trade V6 / business V13 |

The merge tool accepts trade V3/V4/V5/V6, contract V4/V5/V7/V8, settlement V1/V2. Each accepted owned history must have the expected migration count and no failures. Older reviewed contract inputs must lack the new cancellation-intent table; the target initializes that table empty. Contract V8 must contain it, and all of its rows are copied and verified. Other versions, including contract V6, require a separate review.

The copy preserves the direction of each standalone supplier or customer from flat V53 or trade V6. For older reviewed inputs the target defaults those records to `SALE`.

Approval history uses the existing notification table and preserves every source row. V50/trade V5/business V12 copy only recorded counterparty decisions into the processor company's history, using deterministic negative IDs and marking historical mirrors as read. Repeating the backfill adds no rows and does not mirror its own output. Merging trade V3/V4 retains its exact source rows: the empty target's V12 does not backfill rows copied later; apply the reviewed idempotent V50 SQL separately after an old-source merge if those historical processor records are needed.

The table inventory, empty-target requirement, paused-writer gate, read-only source connections, row/content verification, and undo-log checks remain unchanged. Version acceptance does not permit an online migration or reuse of an existing target database.

## Historical local-void data

The business V7 migration runs while initializing the empty target, before copying source rows. Merging an old owned source therefore does **not** retroactively cancel copied legacy pending `RESUME` requests or finish copied approved `VOID` requests. Operators must separately review these historical records before a real cutover. This compatibility change does not alter that data-handling behavior.

The isolated core smoke path first migrates its flat fixture through V53, including V44, then splits and merges it. Its historical void and approval data has already passed those flat migrations. No production migration is performed by this test or this version-guard change.

`ReviewedMigrationVersionsTest` verifies exact supported versions, rejection of older/future versions and failed or incomplete owned histories. The real split/merge copy and database-isolation checks remain in `IndependentServicesIT`.
