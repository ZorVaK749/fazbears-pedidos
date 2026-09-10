package com.fazbear.pedidos.controller;

import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.Pedido;
import com.fazbear.pedidos.service.PedidoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controlador REST del Motor de Pedidos — Freddy Fazbear's Pizza.
 * Expone endpoints bajo /api/pedidos
 */
@RestController
@RequestMapping("/api/pedidos")
@CrossOrigin(origins = "http://35.175.9.254")
public class PedidoController {

    private final PedidoService pedidoService;

    public PedidoController(PedidoService pedidoService) {
        this.pedidoService = pedidoService;
    }

    /**
     * GET /api/pedidos
     * Lista todos los pedidos (uso interno / admin).
     */
    @GetMapping
    public ResponseEntity<List<Pedido>> getAll() {
        return ResponseEntity.ok(pedidoService.findAll());
    }

    /**
     * GET /api/pedidos/{id}
     * Retorna un pedido específico por ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Pedido> getById(@PathVariable Long id) {
        return pedidoService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/pedidos/usuario/{usuarioId}
     * Lista todos los pedidos de un usuario (por ID del JWT claim 'sub').
     * En desarrollo local se puede pasar cualquier string como usuarioId.
     */
    @GetMapping("/usuario/{usuarioId}")
    public ResponseEntity<List<Pedido>> getByUsuario(@PathVariable String usuarioId) {
        return ResponseEntity.ok(pedidoService.findByUsuarioId(usuarioId));
    }

    /**
     * POST /api/pedidos
     * Crea un nuevo pedido con sus ítems.
     *
     * Body de ejemplo:
     * {
     *   "usuarioId": "user-123",
     *   "emailUsuario": "mike@fazbear.com",
     *   "items": [
     *     { "productoId": 1, "nombreProducto": "Pizza Freddy", "cantidad": 2, "precioUnitario": 12.99 }
     *   ]
     * }
     */
    @PostMapping
    public ResponseEntity<Pedido> create(@RequestBody Pedido pedido) {
        Pedido saved = pedidoService.save(pedido);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * PUT /api/pedidos/{id}/estado
     * Actualiza el estado de un pedido (PENDIENTE → PREPARANDO → COMPLETADO).
     *
     * Body: { "estado": "PREPARANDO" }
     */
    @PutMapping("/{id}/estado")
    public ResponseEntity<Pedido> updateEstado(@PathVariable Long id,
                                               @RequestBody Map<String, String> body) {
        String estadoStr = body.get("estado");
        if (estadoStr == null) {
            return ResponseEntity.badRequest().build();
        }
        EstadoPedido nuevoEstado;
        try {
            nuevoEstado = EstadoPedido.valueOf(estadoStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return pedidoService.updateEstado(id, nuevoEstado)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/pedidos/{id}
     * Elimina un pedido por ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (pedidoService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        pedidoService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
