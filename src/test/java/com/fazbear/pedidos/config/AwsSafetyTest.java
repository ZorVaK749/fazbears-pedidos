package com.fazbear.pedidos.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsSafetyTest {

    private static final String TEST_KEY = java.util.UUID.randomUUID().toString();

    private MockEnvironment safe() {
        MockEnvironment e = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.invalid/test")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("INTERNAL_SERVICE_KEY", TEST_KEY)
                .withProperty("checkout.service-key", TEST_KEY);
        e.setActiveProfiles("ev3-aws");
        return e;
    }

    @Test
    void permitsAwsWithoutLabIdentity() {
        assertDoesNotThrow(() -> new AwsSafety(safe()).afterPropertiesSet());
    }

    @Test
    void permitsPersistentH2FileWithUpdate() {
        MockEnvironment e = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:file:/var/lib/fazbear/pedidos/pedidosdb")
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("INTERNAL_SERVICE_KEY", TEST_KEY)
                .withProperty("checkout.service-key", TEST_KEY);
        e.setActiveProfiles("ev3-aws");
        assertDoesNotThrow(() -> new AwsSafety(e).afterPropertiesSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "ev3-local", "test"})
    void rejectsMixedProfiles(String p) {
        MockEnvironment e = safe();
        e.setActiveProfiles("ev3-aws", p);
        assertThrows(IllegalStateException.class, () -> new AwsSafety(e).afterPropertiesSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCAL_JWT_JWK_SET_URI", "spring.security.oauth2.resourceserver.jwt.jwk-set-uri"})
    void rejectsJwksOverride(String key) {
        MockEnvironment e = safe().withProperty(key, "http://lab-auth/jwks");
        assertThrows(IllegalStateException.class, () -> new AwsSafety(e).afterPropertiesSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "create-drop"})
    void rejectsImplicitSchemaMutation(String ddl) {
        MockEnvironment e = safe().withProperty("spring.jpa.hibernate.ddl-auto", ddl);
        assertThrows(IllegalStateException.class, () -> new AwsSafety(e).afterPropertiesSet());
    }

    @Test
    void rejectsH2Mem() {
        MockEnvironment e = safe().withProperty("spring.datasource.url", "jdbc:h2:mem:unsafe");
        assertThrows(IllegalStateException.class, () -> new AwsSafety(e).afterPropertiesSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short-key", "synthetic-internal-service-key-32"})
    void rejectsInvalidInternalServiceKey(String key) {
        MockEnvironment e = safe().withProperty("INTERNAL_SERVICE_KEY", key)
                .withProperty("checkout.service-key", key);
        assertThrows(IllegalStateException.class, () -> new AwsSafety(e).afterPropertiesSet());
    }

    @Test
    void rejectsAnOverrideOfTheEffectiveKey() {
        MockEnvironment e = safe().withProperty("checkout.service-key", java.util.UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> new AwsSafety(e).afterPropertiesSet());
    }

    @Test
    void awsEnablesPaidOrders() {
        var profile = com.fazbear.pedidos.controller.InternalPaidPedidoController.class.getAnnotation(org.springframework.context.annotation.Profile.class);
        assertTrue(java.util.Arrays.asList(profile.value()).contains("ev3-aws"));
    }
}
