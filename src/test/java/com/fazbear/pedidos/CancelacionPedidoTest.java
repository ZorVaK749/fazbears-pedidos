package com.fazbear.pedidos;

import com.fazbear.pedidos.controller.PedidoController;
import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.Pedido;
import com.fazbear.pedidos.repository.PedidoRepository;
import com.fazbear.pedidos.service.PedidoService;
import com.fazbear.pedidos.service.PedidoServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CancelacionPedidoTest {
    @Test
    void endpointAceptaCanceladoSinPreparar() {
        PedidoService service = mock(PedidoService.class);
        Pedido pedido = new Pedido();
        pedido.setEstado(EstadoPedido.CANCELADO);
        when(service.updateEstado(3L, EstadoPedido.CANCELADO)).thenReturn(Optional.of(pedido));
        var response = new PedidoController(service).updateEstado(3L, Map.of("estado", "CANCELADO"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(EstadoPedido.CANCELADO, response.getBody().getEstado());
    }

    @Test
    void persisteCancelacionDelPendienteSinEliminarPedido() {
        PedidoRepository repository = mock(PedidoRepository.class);
        Pedido pedido = new Pedido();
        pedido.setEstado(EstadoPedido.PENDIENTE);
        when(repository.findById(3L)).thenReturn(Optional.of(pedido));
        when(repository.save(pedido)).thenReturn(pedido);
        var result = new PedidoServiceImpl(repository).updateEstado(3L, EstadoPedido.CANCELADO);
        assertEquals(EstadoPedido.CANCELADO, result.orElseThrow().getEstado());
        verify(repository).save(pedido);
        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void estadoDesconocidoSigueSiendo400() {
        PedidoService service = mock(PedidoService.class);
        var response = new PedidoController(service).updateEstado(3L, Map.of("estado", "INVALIDO"));
        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
