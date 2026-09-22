package com.tradepass.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Enforces API-only communication between the five business modules. */
class ModuleBoundaryTest {
    private static final Set<String> ROLES = Set.of("identity", "contract", "trade", "settlement", "file");
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.tradepass");

    @Test void commonAndFileStorageDoNotDependOnBusinessImplementations() {
        noClasses().that().resideInAnyPackage("com.tradepass.framework.common..", "com.tradepass.framework.web..",
                        "com.tradepass.framework.audit..", "com.tradepass.framework.cache..", "com.tradepass.framework.fadada..",
                        "com.tradepass.framework.mybatis..", "com.tradepass.framework.flyway..", "com.tradepass.framework.storage..",
                        "com.tradepass.module.file..")
                .should().dependOnClassesThat().resideInAnyPackage("com.tradepass.module.identity..",
                        "com.tradepass.module.contract..", "com.tradepass.module.trade..", "com.tradepass.module.settlement..")
                .check(CLASSES);
    }

    @Test void dataAccessDoesNotDependOnServicesOrControllers() {
        noClasses().that().resideInAPackage("..dal..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..").check(CLASSES);
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller").check(CLASSES);
    }

    @Test void businessModulesDependOnlyOnForeignApis() {
        for (String owner : ROLES) for (String other : ROLES) {
            if (owner.equals(other)) continue;
            noClasses().that().resideInAPackage("com.tradepass.module." + owner + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tradepass.module." + other + ".dal..",
                            "com.tradepass.module." + other + ".service..",
                            "com.tradepass.module." + other + ".controller..",
                            "com.tradepass.module." + other + ".framework..",
                            "com.tradepass.module." + other + ".convert..")
                    .check(CLASSES);
        }
    }

    @Test void transportDtosHaveNoPersistenceOrWebLayerDependencies() {
        noClasses().that().resideInAPackage("..api..dto..")
                .should().dependOnClassesThat().resideInAnyPackage("..dal..", "..service..", "..controller..",
                        "com.baomidou..", "org.apache.ibatis..").check(CLASSES);
    }
}
