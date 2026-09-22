package com.tradepass.architecture;

import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SourceModuleBoundaryTest {
    private static final Path ROOT = com.tradepass.support.RepoRoot.find();
    private static final List<String> ROLES = List.of("identity", "contract", "trade", "settlement", "file");
    private Path module(String role, String layer) {
        return ROOT.resolve("tradepass-module-" + role + "/tradepass-module-" + role + "-" + layer);
    }

    @Test void businessModulesHaveApiAndServerChildren() throws Exception {
        assertThat(Files.exists(ROOT.resolve("tradepass-dependencies/pom.xml"))).isTrue();
        assertThat(Files.exists(ROOT.resolve("tradepass-framework/pom.xml"))).isTrue();
        assertThat(Files.exists(Path.of("src/main/java"))).isFalse();
        for (String role : ROLES) {
            assertThat(Files.exists(ROOT.resolve("tradepass-" + role))).isFalse();
            String pom = Files.readString(ROOT.resolve("tradepass-module-" + role + "/pom.xml"));
            assertThat(pom).contains("<packaging>pom</packaging>", "tradepass-module-" + role + "-api", "tradepass-module-" + role + "-server");
            assertThat(Files.isDirectory(module(role, "server").resolve("src/main/java/com/tradepass/module/" + role + "/controller/app"))).isTrue();
        }
    }

    @Test void apiAndServerNeverCompileAgainstOtherServers() throws Exception {
        for (String role : ROLES) for (String layer : List.of("api", "server")) {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(module(role, layer).resolve("pom.xml").toFile());
            var dependencies = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dependency = (Element) dependencies.item(i);
                var scopeNodes = dependency.getElementsByTagName("scope");
                if (scopeNodes.getLength() > 0 && "test".equals(scopeNodes.item(0).getTextContent())) {
                    continue;
                }
                String artifact = dependency.getElementsByTagName("artifactId").item(0).getTextContent();
                assertThat(artifact).doesNotEndWith("-server").doesNotEndWith("-model").doesNotEndWith("-domain");
                if (layer.equals("api")) assertThat(artifact).doesNotContain("spring-boot-starter-core", "spring-boot-starter-service", "mybatis");
            }
            if (layer.equals("api")) try (var paths = Files.walk(module(role, layer).resolve("src/main/java"))) {
                for (Path source : paths.filter(p -> p.toString().endsWith(".java")).toList())
                    assertThat(Files.readString(source)).doesNotContain(".dal.", ".controller.", ".service.", "com.baomidou", "org.apache.ibatis");
            }
        }
    }
}
