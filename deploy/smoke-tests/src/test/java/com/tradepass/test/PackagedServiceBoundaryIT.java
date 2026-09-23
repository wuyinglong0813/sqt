package com.tradepass.test;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;

/** Verifies actual boot archives, including their transitive dependencies. */
class PackagedServiceBoundaryIT {
    private static final List<String> ROLES = List.of("identity", "contract", "trade", "settlement", "file");

    @Test void servicesContainOnlyTheirOwnImplementation() throws Exception {
        for (String role : ROLES) {
            String artifact = "tradepass-module-" + role + "-server";
            Path path = com.tradepass.support.RepoRoot.find()
                    .resolve("tradepass-module-" + role + "/" + artifact + "/target/" + artifact + "-0.1.0-SNAPSHOT.jar");
            try (var archive = new ZipFile(path.toFile())) {
                var names = archive.stream().map(e -> e.getName()).toList();
                assertThat(names).anyMatch(name -> name.startsWith("BOOT-INF/classes/com/tradepass/module/" + role + "/controller/app/"));
                assertThat(names).noneMatch(name -> name.startsWith("BOOT-INF/lib/tradepass-")
                        && (name.contains("-domain-") || name.contains("-model-") || name.contains("-kernel.jar")));
                for (String other : ROLES) if (!other.equals(role)) {
                    assertThat(names).noneMatch(name -> name.startsWith("BOOT-INF/classes/com/tradepass/module/" + other + "/")
                            || name.startsWith("BOOT-INF/lib/tradepass-module-" + other + "-server-"));
                    if (role.equals("file")) assertThat(names).noneMatch(name -> name.startsWith("BOOT-INF/lib/tradepass-module-" + other + "-"));
                }
            }
        }
    }

    @Test void businessHostsCoreImplementationsAndKeepsIdentityRemote() throws Exception {
        Path path = com.tradepass.support.RepoRoot.find().resolve("tradepass-business/target/tradepass-business-0.1.0-SNAPSHOT.jar");
        try (var archive = new ZipFile(path.toFile())) {
            var names = archive.stream().map(e -> e.getName()).toList();
            for (String role : List.of("contract", "trade", "settlement", "file")) {
                assertThat(names).anyMatch(name -> name.startsWith("BOOT-INF/lib/tradepass-module-" + role + "-server-"));
            }
            assertThat(names).noneMatch(name -> name.startsWith("BOOT-INF/lib/tradepass-module-identity-server-"));
            assertThat(names).contains("BOOT-INF/classes/db/business/V1__owned_baseline.sql");
            assertThat(names).noneMatch(name -> name.endsWith("/HostedDomainConfiguration.class") || name.endsWith("/DomainContext.class"));
        }
    }
}
