package com.fazbear.pedidos.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.ItemPedido;
import com.fazbear.pedidos.model.Pedido;
import java.math.BigDecimal;
import java.util.List;

/** Contrato de Orden sin IDs/relaciones persistentes asignables por el cliente. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CrearPedidoRequest(String usuarioId, String emailUsuario, BigDecimal total,
                                  EstadoPedido estado, List<ItemRequest> items) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemRequest(Long productoId, String nombreProducto, Integer cantidad, BigDecimal precioUnitario) { }

    public Pedido toPedido(String verifiedOwner) {
        Pedido pedido = new Pedido();
        pedido.setUsuarioId(verifiedOwner);
        pedido.setEmailUsuario(emailUsuario);
        pedido.setTotal(total);
        pedido.setEstado(EstadoPedido.PENDIENTE);
        if (items != null) {
            for (ItemRequest dto : items) {
                ItemPedido item = new ItemPedido();
                item.setProductoId(dto.productoId());
                item.setNombreProducto(dto.nombreProducto());
                item.setCantidad(dto.cantidad());
                item.setPrecioUnitario(dto.precioUnitario());
                pedido.getItems().add(item);
            }
        }
        return pedido;
    }
}
