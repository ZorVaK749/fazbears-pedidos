package com.fazbear.pedidos.service;

import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.ItemPedido;
import com.fazbear.pedidos.model.Pedido;
import com.fazbear.pedidos.repository.PedidoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Implementación del servicio de Pedidos.
 * Calcula el total automáticamente a partir de los ítems.
 */
@Service
public class PedidoServiceImpl implements PedidoService {

    private final PedidoRepository pedidoRepository;

    public PedidoServiceImpl(PedidoRepository pedidoRepository) {
        this.pedidoRepository = pedidoRepository;
    }

    @Override
    public List<Pedido> findAll() {
        return pedidoRepository.findAll();
    }

    @Override
    public Optional<Pedido> findById(Long id) {
        return pedidoRepository.findById(id);
    }

    @Override
    public List<Pedido> findByUsuarioId(String usuarioId) {
        return pedidoRepository.findByUsuarioId(usuarioId);
    }

    @Override
    public Pedido save(Pedido pedido) {
        // Enlazar cada ítem al pedido padre
        if (pedido.getItems() != null) {
            for (ItemPedido item : pedido.getItems()) {
                item.setPedido(pedido);
            }
            // Calcular total automáticamente si no viene en el cuerpo
            if (pedido.getTotal() == null || pedido.getTotal().compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal total = pedido.getItems().stream()
                        .map(i -> i.getPrecioUnitario()
                                .multiply(BigDecimal.valueOf(i.getCantidad())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                pedido.setTotal(total);
            }
        }
        return pedidoRepository.save(pedido);
    }

    @Override
    public Optional<Pedido> updateEstado(Long id, EstadoPedido nuevoEstado) {
        return pedidoRepository.findById(id).map(pedido -> {
            pedido.setEstado(nuevoEstado);
            return pedidoRepository.save(pedido);
        });
    }

    @Override
    public void deleteById(Long id) {
        pedidoRepository.deleteById(id);
    }
}
