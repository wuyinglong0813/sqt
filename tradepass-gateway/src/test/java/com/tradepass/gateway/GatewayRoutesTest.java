package com.tradepass.gateway;

import com.tradepass.framework.runtime.core.RouteOwnership;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.filter.factory.RemoveRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GatewayRoutesTest {
    @Test void eachActualControllerMappingReachesItsAssignedService() throws Exception {
        try (var context = new GenericApplicationContext(); var mvc = new StaticWebApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(RemoveRequestHeaderGatewayFilterFactory.class);
            context.refresh();
            mvc.refresh();
            var introspector = new MappingIntrospector();
            introspector.setApplicationContext(mvc);
            introspector.afterPropertiesSet();
            List<Route> routes = new GatewayApplication().publicRoutes(new RouteLocatorBuilder(context), new MockEnvironment())
                    .getRoutes().collectList().block();
            assertNotNull(routes);
            int count = 0;
            boolean sealGrantStatusMapped = false;
            boolean signingReadinessMapped = false;
            var scanner = new ClassPathScanningCandidateComponentProvider(true);
            for (var candidate : scanner.findCandidateComponents("com.tradepass")) {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                if (!type.isAnnotationPresent(RestController.class)) continue;
                for (Method method : type.getDeclaredMethods()) {
                    var mapping = introspector.mapping(method, type);
                    if (mapping == null || mapping.getPatternValues().stream().anyMatch(path -> path.startsWith("/internal/"))) continue;
                    for (String path : mapping.getPatternValues()) {
                        String concrete = path.replaceAll("\\{[^}]+}", "123");
                        String expected = RouteOwnership.owner(type, method);
                        if (path.equals("/api/fadada/companies/{companyId}/members/{userId}/seal-grant-status")) {
                            assertEquals(Set.of(RequestMethod.POST), mapping.getMethodsCondition().getMethods());
                            assertEquals("identity", expected);
                            sealGrantStatusMapped = true;
                        }
                        if (path.equals("/api/fadada/companies/{companyId}/signing-readiness")) {
                            assertEquals(Set.of(RequestMethod.GET), mapping.getMethodsCondition().getMethods());
                            assertEquals("identity", expected);
                            signingReadinessMapped = true;
                        }
                        assertEquals(expected.equals("all") ? "identity" : expected, owner(routes, concrete), mapping.toString());
                    }
                    count++;
                }
            }
            // Includes signing prerequisites, member preparation, seal grants/status, preview manifests and invitation previews.
            // Every mapping above must retain its assigned service owner.
            assertEquals(189, count, "Update the original HTTP contract deliberately when adding APIs");
            assertTrue(sealGrantStatusMapped, "The member seal-grant status API must remain available via POST in identity");
            assertTrue(signingReadinessMapped, "Signing readiness must be a read-only GET in identity");
            assertNull(owner(routes, "/internal/identity/resolve"));
            assertNull(owner(routes, "/internal/storage/put"));
            assertNull(owner(routes, "/actuator/prometheus"));
        }
    }

    static String owner(List<Route> routes, String path) {
        for (Route route : routes) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            if (Boolean.TRUE.equals(reactor.core.publisher.Mono.from(route.getPredicate().apply(exchange)).block())) return route.getId();
        }
        return null;
    }

    static class MappingIntrospector extends RequestMappingHandlerMapping {
        RequestMappingInfo mapping(Method method, Class<?> type) { return getMappingForMethod(method, type); }
    }
}
