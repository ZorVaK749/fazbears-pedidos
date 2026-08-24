package com.fazbear.pedidos.service;

import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.Pedido;

import java.util.List;
import java.util.Optional;

/**
 * Interfaz del servicio de negocio para Pedidos.
 */
public interface PedidoService {

    /** Lista todos los pedidos (admin) */
    List<Pedido> findAll();

    /** Busca un pedido por ID */
    Optional<Pedido> findById(Long id);

    /** Retorna todos los pedidos de un usuario */
    List<Pedido> findByUsuarioId(String usuarioId);

    /** Crea un nuevo pedido */
    Pedido save(Pedido pedido);

    /** Actualiza el estado de un pedido */
    Optional<Pedido> updateEstado(Long id, EstadoPedido nuevoEstado);

    /** Elimina un pedido */
    void deleteById(Long id);
}
