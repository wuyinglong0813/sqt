package com.tradepass.framework.runtime.core;

import com.tradepass.module.file.api.file.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;

/** Assembles owned endpoints and required local domain collaborators without changing transaction boundaries. */
public final class DomainComponentRegistrar implements BeanDefinitionRegistryPostProcessor {
    private final Environment environment;
    public DomainComponentRegistrar(Environment environment) { this.environment = environment; }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        String role = environment.getRequiredProperty("tradepass.runtime.role");
        boolean split = environment.getProperty("tradepass.services.split", Boolean.class, false);
        Map<Class<?>, org.springframework.beans.factory.config.BeanDefinition> candidates = new HashMap<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(true, environment);
        scanner.findCandidateComponents("com.tradepass").forEach(definition -> {
            try {
                Class<?> candidate = Class.forName(definition.getBeanClassName());
                if (!split || belongsTo(candidate, role)) candidates.put(candidate, definition);
            }
            catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
        });
        Set<Class<?>> selected = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        if (split) candidates.keySet().stream()
                .filter(type -> type.isAnnotationPresent(org.springframework.stereotype.Service.class))
                .forEach(pending::add);
        candidates.keySet().stream().filter(type -> type.isAnnotationPresent(RestController.class))
                .filter(type -> !type.getName().startsWith("com.tradepass.framework.rpc.") && !type.getName().contains(".controller.internal."))
                .filter(type -> Arrays.stream(type.getDeclaredMethods())
                        .anyMatch(method -> Modifier.isPublic(method.getModifiers()) && RouteOwnership.serves(role, type, method)))
                .forEach(pending::add);
        List<String> roots = new ArrayList<>(List.of(
                "com.tradepass.framework.web.core.handler.GlobalExceptionHandler", "com.tradepass.framework.web.core.interceptor.DevModeInterceptor",
                "com.tradepass.framework.mybatis.config.IdentifierConfiguration", "com.tradepass.framework.storage.config.StorageProperties",
                "com.tradepass.framework.fadada.config.FadadaProperties", "com.tradepass.framework.flyway.config.FlywaySafetyConfig"));
        if (role.equals("identity")) roots.addAll(List.of("com.tradepass.module.identity.framework.config.AuthInterceptor",
                "com.tradepass.module.identity.framework.config.SystemPermissionInitializer", "com.tradepass.module.identity.framework.config.DatabaseInitializer"));
        if (role.equals("contract")) roots.addAll(List.of("com.tradepass.module.contract.framework.callback.FadadaCallbackRecovery",
                "com.tradepass.module.contract.job.LocalCallbackRecoveryScheduler"));
        candidates.keySet().stream().filter(type -> roots.contains(type.getName())).forEach(pending::add);
        while (!pending.isEmpty()) {
            Class<?> type = pending.remove();
            if (!selected.add(type)) continue;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                if (constructors.length == 1 || constructor.isAnnotationPresent(Autowired.class)) {
                    Arrays.stream(constructor.getParameters()).filter(p -> !p.isAnnotationPresent(Value.class))
                            .forEach(p -> enqueue(p.getType(), candidates, pending));
                }
            }
            Arrays.stream(type.getDeclaredFields()).filter(f -> f.isAnnotationPresent(Autowired.class))
                    .forEach(f -> enqueue(f.getType(), candidates, pending));
            Arrays.stream(type.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Autowired.class))
                    .forEach(m -> Arrays.stream(m.getParameterTypes()).forEach(t -> enqueue(t, candidates, pending)));
        }
        selected.forEach(type -> {
            if (type.isAnnotationPresent(org.springframework.context.annotation.Configuration.class)) return;
            if (Arrays.stream(registry.getBeanDefinitionNames()).anyMatch(name ->
                    type.getName().equals(registry.getBeanDefinition(name).getBeanClassName()))) return;
            var definition = new AnnotatedGenericBeanDefinition(type);
            org.springframework.context.annotation.AnnotationConfigUtils.processCommonDefinitionAnnotations(definition);
            String name = AnnotationBeanNameGenerator.INSTANCE.generateBeanName(definition, registry);
            registry.registerBeanDefinition(name, definition);
        });
    }

    private void enqueue(Class<?> dependency, Map<Class<?>, ?> candidates, Deque<Class<?>> pending) {
        // Storage is supplied by the remote adapter, never by a local COS instance in business processes.
        if (ObjectStorageService.class.isAssignableFrom(dependency)) return;
        candidates.keySet().stream().filter(dependency::isAssignableFrom).forEach(pending::add);
    }

    private boolean belongsTo(Class<?> type, String role) {
        String name = type.getName();
        if (name.startsWith("com.tradepass.module.contract.framework.callback.")) return role.equals("contract");
        for (String domain : List.of("identity", "contract", "trade", "settlement", "file")) {
            if (name.startsWith("com.tradepass.module." + domain + ".")) return domain.equals(role);
        }
        return true;
    }

    @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) { }
}
