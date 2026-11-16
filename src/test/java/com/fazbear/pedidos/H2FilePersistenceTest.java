package com.fazbear.pedidos;

import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.Pedido;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba que el mecanismo JPA funciona con H2 en memoria para tests.
 *
 * NOTA TÉCNICA - Persistencia H2 file en AWS:
 * En producción/AWS la URL es jdbc:h2:file:/var/lib/fazbear/pedidos/pedidosdb
 * con ddl-auto=update. Los datos persisten entre reinicios de la aplicación
 * mientras el volumen EBS esté montado en el mismo path.
 *
 * RIESGO: Si el contenedor ECS se reemplaza apuntando a un nuevo volumen EBS
 * (o sin montar el volumen correcto), los datos se pierden. El directorio
 * /var/lib/fazbear/pedidos/ DEBE estar montado en el mismo EBS entre deploys.
 */
@DataJpaTest
@ActiveProfiles("test")
class H2FilePersistenceTest {

    @Autowired
    private EntityManager em;

    @Test
    void pedidoPersisteEntreOperacionesJpa() {
        // Arrange: crear y persistir pedido
        Pedido pedido = new Pedido();
        pedido.setUsuarioId("test-oid-" + System.currentTimeMillis());
        pedido.setEmailUsuario("test@fazbear.com");
        pedido.setTotal(new BigDecimal("99.99"));
        pedido.setEstado(EstadoPedido.PENDIENTE);
        em.persist(pedido);
        em.flush();

        Long id = pedido.getId();
        assertThat(id).isNotNull();

        // Clear the first-level cache (simula nueva sesión JPA)
        em.clear();

        // Assert: releer desde "BD" (H2 mem en tests, H2 file en producción)
        Pedido reloaded = em.find(Pedido.class, id);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getId()).isEqualTo(id);
        assertThat(reloaded.getFechaCreacion()).isNotNull();
        assertThat(reloaded.getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
        assertThat(reloaded.getTotal()).isEqualByComparingTo(new BigDecimal("99.99"));
    }
}
