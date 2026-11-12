package com.fazbear.pedidos.controller;

import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.model.Pedido;
import com.fazbear.pedidos.service.PedidoService;
import com.fazbear.pedidos.security.PedidoAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Controlador REST del Motor de Pedidos — Freddy Fazbear's Pizza.
 * Expone endpoints bajo /api/pedidos
 */
@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final PedidoService pedidoService;
    private final PedidoAuthorization authorization;

    public PedidoController(PedidoService pedidoService, PedidoAuthorization authorization) {
        this.pedidoService = pedidoService;
        this.authorization = authorization;
    }

    /**
     * GET /api/pedidos
     * Lista todos los pedidos (uso interno / admin).
     */
    @GetMapping
    public ResponseEntity<List<Pedido>> getAll(@AuthenticationPrincipal Jwt jwt) {
        authorization.requireAdministration(jwt);
        return ResponseEntity.ok(pedidoService.findAll());
    }

    /**
     * GET /api/pedidos/{id}
     * Retorna un pedido específico por ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Pedido> getById(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String owner = authorization.owner(jwt);
        return pedidoService.findById(id)
                .filter(pedido -> owner.equals(pedido.getUsuarioId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/pedidos/usuario/{usuarioId}
     * Lista solo pedidos del oid verificado del tenant permitido.
     */
    @GetMapping("/usuario/{usuarioId}")
    public ResponseEntity<List<Pedido>> getByUsuario(@PathVariable String usuarioId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(pedidoService.findByUsuarioId(authorization.requireOwner(jwt, usuarioId)));
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
    public ResponseEntity<Pedido> create(@RequestBody CrearPedidoRequest request, @AuthenticationPrincipal Jwt jwt) {
        String owner = authorization.owner(jwt);
        if (request.usuarioId() != null) authorization.requireOwner(jwt, request.usuarioId());
        // Orden crea PENDIENTE. No permitir que un usuario declare estados administrativos.
        if (request.estado() != null && request.estado() != EstadoPedido.PENDIENTE) {
            authorization.requireAdministration(jwt);
        }
        Pedido saved = pedidoService.save(request.toPedido(owner));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * PUT /api/pedidos/{id}/estado
     * Permite cancelar un pedido propio PENDIENTE. Otras transiciones quedan
     * cerradas hasta verificar/configurar la política administrativa Azure.
     *
     * Body: { "estado": "PREPARANDO" }
     */
    @PutMapping("/{id}/estado")
    public ResponseEntity<Pedido> updateEstado(@PathVariable Long id,
                                               @RequestBody Map<String, String> body,
                                               @AuthenticationPrincipal Jwt jwt) {
        String owner = authorization.owner(jwt);
        String estadoStr = body.get("estado");
        if (estadoStr == null) {
            return ResponseEntity.badRequest().build();
        }
        EstadoPedido nuevoEstado;
        try {
            nuevoEstado = EstadoPedido.valueOf(estadoStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (nuevoEstado != EstadoPedido.CANCELADO) authorization.requireAdministration(jwt);
        return pedidoService.cancelOwned(id, owner)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/pedidos/{id}
     * Elimina un pedido por ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        authorization.requireAdministration(jwt);
        if (pedidoService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        pedidoService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
