package com.fazbear.pedidos.repository;

import com.fazbear.pedidos.model.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para la entidad Pedido.
 */
@Repository
public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    /**
     * Busca pedidos por oid del tenant único configurado.
     */
    List<Pedido> findByUsuarioId(String usuarioId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pedido p set p.estado = com.fazbear.pedidos.model.EstadoPedido.CANCELADO "
        + "where p.id = :id and p.usuarioId = :owner "
        + "and p.estado = com.fazbear.pedidos.model.EstadoPedido.PENDIENTE")
    int cancelPendingOwned(Long id, String owner);

    /**
     * Busca todos los pedidos de un usuario por email.
     */
    List<Pedido> findByEmailUsuario(String emailUsuario);
}
