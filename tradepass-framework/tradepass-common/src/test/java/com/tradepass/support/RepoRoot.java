package com.tradepass.support;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the monorepo root from any Maven module working directory. */
public final class RepoRoot {
    private RepoRoot() {
    }

    public static Path find() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 12; depth++) {
            if (Files.isDirectory(dir.resolve("sql/mysql"))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        throw new IllegalStateException("Cannot locate repository root (expected sql/mysql directory)");
    }

    public static Path legacyMysqlMigrations() {
        return find().resolve("sql/mysql");
    }
}
