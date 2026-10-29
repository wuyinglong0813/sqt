package com.tradepass.tools.migration;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewedMigrationVersionsTest {
    @Test void flatSourceRequiresExactlyReviewedV51WithoutFailedMigrations() {
        for (int version = 0; version <= 60; version++) {
            assertEquals(version == 51, SplitDatabaseMigration.isReviewedSourceVersion(version, 0),
                    "Unexpected flat source version: " + version);
            assertFalse(SplitDatabaseMigration.isReviewedSourceVersion(version, 1));
        }
    }

    @Test void ownedSourcesAcceptOnlyExplicitlyReviewedVersionsAndCompleteSuccessfulHistory() {
        for (String role : new String[]{"trade", "contract", "settlement", "unknown"}) {
            for (int version = 0; version <= 10; version++) {
                boolean expected = switch (role) {
                    case "trade" -> version == 3 || version == 4 || version == 5;
                    case "contract" -> version == 4 || version == 5 || version == 7 || version == 8;
                    case "settlement" -> version == 1 || version == 2;
                    default -> false;
                };
                assertEquals(expected, MergeBusinessMigration.isReviewedOwnedHistory(role, version, version, 0),
                        "Unexpected owned source version: " + role + " V" + version);
                assertFalse(MergeBusinessMigration.isReviewedOwnedHistory(role, version, version, 1));
                assertFalse(MergeBusinessMigration.isReviewedOwnedHistory(role, Math.max(0, version - 1), version, 0));
            }
        }
    }

    @Test void unsignedCancellationIntentsAreCopiedOnlyFromSourcesWhoseReviewedSchemaHasTheTable() {
        var tables = List.of("trade_contract", "fadada_contract_sign_task", "fadada_signing_cancellation_intent");
        for (int old : new int[]{4, 5, 7}) {
            assertEquals(List.of("trade_contract", "fadada_contract_sign_task"),
                    MergeBusinessMigration.ownedTablesForVersion("contract", old, tables));
        }
        assertEquals(tables, MergeBusinessMigration.ownedTablesForVersion("contract", 8, tables));
        assertEquals(tables, MergeBusinessMigration.ownedTablesForVersion("trade", 4, tables));
        assertEquals(3, tables.size(), "Reviewed ownership must not be mutated for the next source");
    }
}
