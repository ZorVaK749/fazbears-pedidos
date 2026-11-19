package com.fazbear.pedidos;

import com.fazbear.pedidos.controller.InternalPaidPedidoController;
import com.fazbear.pedidos.controller.InternalPaidPedidoController.Item;
import com.fazbear.pedidos.controller.InternalPaidPedidoController.Paid;
import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.Pedido;
import com.fazbear.pedidos.repository.PedidoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueba de arranque real para ms-pedidos utilizando el perfil efectivo del contenedor Docker (ev3-aws)
 * con persistencia H2 en archivo y puerto 8082.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("ev3-aws")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:file:./target/test-data-effective/pedidosdb_effective;AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=update",
    "spring.flyway.enabled=false",
    "spring.main.allow-bean-definition-overriding=true"
})
class PedidoEffectiveProfileStartupTest {
    private static final String TEST_KEY = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void serviceKey(DynamicPropertyRegistry registry) {
        registry.add("INTERNAL_SERVICE_KEY", () -> TEST_KEY);
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {
        @Bean
        @Primary
        public JwtDecoder jwtDecoder() {
            return token -> null;
        }
    }

    @Autowired
    private PedidoRepository pedidoRepository;

    @Autowired
    private InternalPaidPedidoController paidOrders;

    @Test
    @Transactional
    @DisplayName("El perfil efectivo ev3-aws debe inicializar el contexto y permitir operaciones de pedidos")
    void testPedidoStartupUnderEffectiveProfile() {
        Pedido pedido = new Pedido();
        pedido.setUsuarioId("user-startup-test");
        pedido.setEmailUsuario("user@example.com");
        pedido.setTotal(new BigDecimal("49.99"));
        pedido.setEstado(EstadoPedido.PENDIENTE);
        Pedido saved = pedidoRepository.save(pedido);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUsuarioId()).isEqualTo("user-startup-test");

        var found = pedidoRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTotal()).isEqualByComparingTo(new BigDecimal("49.99"));
    }

    @Test
    void confirmedWebpayOrdersRetainTheProviderModeInAwsH2() {
        var request = new Paid(UUID.randomUUID(), UUID.randomUUID(),
                UUID.fromString("ee0d2c5b-e60b-4c57-9b30-8a38a520c430"), UUID.randomUUID(),
                new BigDecimal("1250.00"), "CLP", false, List.of(new Item(1L, "Pizza", 1, new BigDecimal("1250.00"))));
        var first = paidOrders.create(TEST_KEY, request);
        assertThat(paidOrders.create(TEST_KEY, request)).isEqualTo(first);
        assertThat(pedidoRepository.findById((Long) first.get("pedidoId")).orElseThrow().getPaymentStatus())
                .isEqualTo("APPROVED_WEBPAY_INTEGRATION");
        assertThat(first).containsEntry("simulacion", false);
    }

    @Test
    void concurrentPaidRetriesUseTheAwsFileSchemaAndRegisterOneOrder() throws Exception {
        var operation = UUID.randomUUID();
        var payment = UUID.randomUUID();
        var tenant = UUID.fromString("ee0d2c5b-e60b-4c57-9b30-8a38a520c430");
        var owner = UUID.randomUUID();
        var total = new BigDecimal("2500.00");
        var items = List.of(new Item(1L, "Pizza", 2, new BigDecimal("1250.00")));
        var request = new Paid(operation, payment, tenant, owner, total, "CLP", true, items);

        var pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Long> create = () -> (Long) paidOrders.create(TEST_KEY, request).get("pedidoId");
            var results = pool.invokeAll(List.of(create, create, create, create), 15, TimeUnit.SECONDS);
            long id = results.get(0).get();
            for (var result : results) {
                assertThat(result.get()).isEqualTo(id);
            }
            assertThat(pedidoRepository.findById(id).orElseThrow().getPagoId()).isEqualTo(payment);
        } finally {
            pool.shutdownNow();
        }

        var conflictingOperation = new Paid(operation, payment, tenant, UUID.randomUUID(), total, "CLP", true, items);
        var repeatedPayment = new Paid(UUID.randomUUID(), payment, tenant, owner, total, "CLP", true, items);
        assertThatThrownBy(() -> paidOrders.create(TEST_KEY, conflictingOperation))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("OPERATION_CONFLICT");
        assertThatThrownBy(() -> paidOrders.create(TEST_KEY, repeatedPayment))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("PAYMENT_ALREADY_USED");
    }
}
