package com.fazbear.pedidos.repository;

import com.fazbear.pedidos.model.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para la entidad Pedido.
 */
@Repository
public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    /**
     * Busca todos los pedidos de un usuario por su ID (claim 'sub' del JWT).
     */
    List<Pedido> findByUsuarioId(String usuarioId);

    /**
     * Busca todos los pedidos de un usuario por email.
     */
    List<Pedido> findByEmailUsuario(String emailUsuario);
}
