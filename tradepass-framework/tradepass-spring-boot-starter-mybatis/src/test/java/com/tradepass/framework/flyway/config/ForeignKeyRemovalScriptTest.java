package com.tradepass.framework.flyway.config;

import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.database.mysql.MySQLParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the actual Flyway MySQL delimiter/block parser without connecting to a database. */
class ForeignKeyRemovalScriptTest {
    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("deploy/database/table-ownership.json"))) {
            path = path.getParent();
        }
        if (path == null) throw new IllegalStateException("Repository root not found");
        return path;
    }

    private static List<String> parse(Path file) throws Exception {
        var statements = new ArrayList<String>();
        var parser = new MySQLParser(new ClassicConfiguration(), new ParsingContext());
        try (var iterator = parser.parse(new StringResource(Files.readString(file)))) {
            while (iterator.hasNext()) statements.add(iterator.next().getSql());
        }
        return statements;
    }

    @Test void everyMigrationKeepsTheWholeProcedureAsOneStatement() throws Exception {
        var paths = new ArrayList<Path>();
        paths.add(root().resolve("sql/mysql/V37__remove_foreign_keys.sql"));
        paths.add(root().resolve("tradepass-business/src/main/resources/db/business/V2__remove_foreign_keys.sql"));
        for (String role : List.of("identity", "contract", "trade", "settlement")) {
            paths.add(root().resolve("tradepass-module-" + role + "/tradepass-module-" + role
                    + "-server/src/main/resources/db/owned/" + role + "/V2__remove_foreign_keys.sql"));
        }
        for (Path file : paths) {
            List<String> statements = parse(file);
            assertThat(statements).as(file.toString()).hasSize(4);
            assertThat(statements.get(1)).contains("CREATE PROCEDURE", "END LOOP", "DROP FOREIGN KEY");
            assertThat(statements.get(2)).startsWith("CALL tradepass_remove_foreign_keys_v2");
            assertThat(statements.get(3)).startsWith("DROP PROCEDURE");
        }
    }

    @Test void manualAndResetScriptsAreAcceptedByTheSameMysqlParser() throws Exception {
        assertThat(parse(root().resolve("scripts/remove-all-foreign-keys.sql"))).hasSize(12);
        List<String> reset = parse(root().resolve("scripts/reset-test-data.sql"));
        assertThat(reset).hasSize(6);
        assertThat(reset.get(3)).contains("CREATE PROCEDURE", "ROLLBACK", "COMMIT");
    }
}
