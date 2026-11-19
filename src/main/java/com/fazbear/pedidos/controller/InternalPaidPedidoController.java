package com.fazbear.pedidos.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Registers confirmed simulated payments using a trusted service credential. */
@RestController
@Profile({"ev3-local", "ev3-aws"})
public class InternalPaidPedidoController {
    public record Item(Long productoId,String nombreProducto,Integer cantidad,BigDecimal precioUnitario) {}
    public record Paid(UUID operacionId,UUID pagoId,UUID tenantId,UUID ownerId,BigDecimal importe,
                       String moneda,Boolean simulacion,List<Item> items) {}
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final String key,tenant;
    public InternalPaidPedidoController(JdbcTemplate db, PlatformTransactionManager transactionManager,
            @Value("${checkout.service-key}") String key,
            @Value("${spring.security.oauth2.resourceserver.jwt.tenant-id}") String tenant) {
        if (key.isBlank()) throw new IllegalArgumentException("INTERNAL_SERVICE_KEY required");
        this.db=db; this.key=key; this.tenant=tenant;
        this.transaction = new TransactionTemplate(transactionManager);
    }
    @PostMapping("/internal/pedidos/pagados")
    public Map<String,Object> create(@RequestHeader(value="X-Service-Key",required=false) String supplied,@RequestBody Paid p) {
        if (supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw error(HttpStatus.UNAUTHORIZED,"INVALID_SERVICE_KEY");
        if (p==null || p.operacionId()==null || p.pagoId()==null || p.ownerId()==null || p.tenantId()==null
                || !tenant.equals(p.tenantId().toString()) || p.simulacion()==null || !"CLP".equals(p.moneda())
                || p.importe()==null || p.importe().signum()<=0 || p.items()==null || p.items().isEmpty() || p.items().size()>100)
            throw error(HttpStatus.BAD_REQUEST,"INVALID_PAID_ORDER");
        BigDecimal calculated=BigDecimal.ZERO; Set<Long> ids=new HashSet<>();
        for (Item i:p.items()) {
            if (i==null || i.productoId()==null || i.productoId()<1 || !ids.add(i.productoId())
                    || i.cantidad()==null || i.cantidad()<1 || i.cantidad()>10000 || i.precioUnitario()==null
                    || i.precioUnitario().signum()<=0 || i.precioUnitario().stripTrailingZeros().scale()>2
                    || i.nombreProducto()==null || i.nombreProducto().isBlank() || i.nombreProducto().length()>120)
                throw error(HttpStatus.BAD_REQUEST,"INVALID_ITEM");
            calculated=calculated.add(i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())));
        }
        if (calculated.compareTo(p.importe())!=0 || calculated.compareTo(new BigDecimal("99999999.99"))>0)
            throw error(HttpStatus.CONFLICT,"TOTAL_MISMATCH");
        String fingerprint;
        try { fingerprint=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p); }
        catch (Exception e) { throw new IllegalStateException(e); }
        try {
            return transaction.execute(status -> result(insert(p, fingerprint), p));
        } catch (DuplicateKeyException duplicate) {
            // Both databases enforce operation/payment uniqueness. Resolve the conflict
            // after rollback, since PostgreSQL rejects further SQL in the failed transaction.
            var previous = db.query("select id,checkout_payload from pedidos where operacion_id=?",
                    (rs, row) -> new SavedOperation(rs.getLong("id"), rs.getString("checkout_payload")),
                    p.operacionId());
            if (!previous.isEmpty()) {
                SavedOperation saved = previous.get(0);
                if (!fingerprint.equals(saved.payload())) {
                    throw error(HttpStatus.CONFLICT,"OPERATION_CONFLICT");
                }
                return result(saved.id(), p);
            }
            if (!db.queryForList("select id from pedidos where pago_id=?", p.pagoId()).isEmpty()) {
                throw error(HttpStatus.CONFLICT,"PAYMENT_ALREADY_USED");
            }
            throw duplicate;
        }
    }

    private record SavedOperation(long id, String payload) {}

    private long insert(Paid p, String fingerprint) {
        var keyHolder = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into pedidos(usuario_id,email_usuario,fecha_creacion,total,estado,operacion_id,
                                    pago_id,tenant_id,payment_status,simulacion,checkout_payload)
                values(?,'',localtimestamp,?,'PENDIENTE',?,?,?,?,?,?)
                """, new String[]{"id"});
            statement.setString(1, p.ownerId().toString());
            statement.setBigDecimal(2, p.importe());
            statement.setObject(3, p.operacionId());
            statement.setObject(4, p.pagoId());
            statement.setObject(5, p.tenantId());
            statement.setString(6, paymentStatus(p));
            statement.setBoolean(7, p.simulacion());
            statement.setString(8, fingerprint);
            return statement;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        for (Item i:p.items()) db.update("insert into item_pedido(pedido_id,producto_id,nombre_producto,cantidad,precio_unitario) values(?,?,?,?,?)",
            id,i.productoId(),i.nombreProducto(),i.cantidad(),i.precioUnitario());
        return id;
    }
    private Map<String,Object> result(Object id,Paid p) {
        return Map.of("pedidoId",id,"pagoId",p.pagoId(),"total",p.importe(),"estado","PENDIENTE",
            "paymentStatus",paymentStatus(p),"simulacion",p.simulacion());
    }
    private String paymentStatus(Paid paid) {
        return paid.simulacion() ? "APPROVED_SIMULATED" : "APPROVED_WEBPAY_INTEGRATION";
    }
    private ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
