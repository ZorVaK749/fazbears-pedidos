package com.fazbear.pedidos;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

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
    "INTERNAL_SERVICE_KEY=synthetic-internal-service-key-32",
    "spring.main.allow-bean-definition-overriding=true"
})
@Transactional
class PedidoEffectiveProfileStartupTest {

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

    @Test
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
}
