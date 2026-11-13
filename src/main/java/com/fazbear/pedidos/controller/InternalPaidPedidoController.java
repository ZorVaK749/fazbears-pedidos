package com.fazbear.pedidos.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Service credential only; never an owner/admin JWT bypass. Active only in isolated local integration. */
@RestController
@Profile("ev3-local")
public class InternalPaidPedidoController {
    public record Item(Long productoId,String nombreProducto,Integer cantidad,BigDecimal precioUnitario) {}
    public record Paid(UUID operacionId,UUID pagoId,UUID tenantId,UUID ownerId,BigDecimal importe,
                       String moneda,boolean simulacion,List<Item> items) {}
    private final JdbcTemplate db;
    private final String key,tenant;
    public InternalPaidPedidoController(JdbcTemplate db,@Value("${checkout.service-key}") String key,
            @Value("${spring.security.oauth2.resourceserver.jwt.tenant-id}") String tenant) {
        if (key.isBlank()) throw new IllegalArgumentException("INTERNAL_SERVICE_KEY required");
        this.db=db; this.key=key; this.tenant=tenant;
    }
    @PostMapping("/internal/pedidos/pagados")
    @Transactional
    public Map<String,Object> create(@RequestHeader(value="X-Service-Key",required=false) String supplied,@RequestBody Paid p) {
        if (supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw error(HttpStatus.UNAUTHORIZED,"INVALID_SERVICE_KEY");
        if (p==null || p.operacionId()==null || p.pagoId()==null || p.ownerId()==null || p.tenantId()==null
                || !tenant.equals(p.tenantId().toString()) || !p.simulacion() || !"CLP".equals(p.moneda())
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
        db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",p.operacionId().toString());
        var rows=db.queryForList("select id,checkout_payload from pedidos where operacion_id=?",p.operacionId());
        if (!rows.isEmpty()) {
            if (!fingerprint.equals(rows.get(0).get("checkout_payload"))) throw error(HttpStatus.CONFLICT,"OPERATION_CONFLICT");
            return result(rows.get(0).get("id"),p);
        }
        db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,1))",p.pagoId().toString());
        if (!db.queryForList("select id from pedidos where pago_id=?",p.pagoId()).isEmpty()) throw error(HttpStatus.CONFLICT,"PAYMENT_ALREADY_USED");
        Long id=db.queryForObject("insert into pedidos(usuario_id,email_usuario,fecha_creacion,total,estado,operacion_id,pago_id,tenant_id,payment_status,simulacion,checkout_payload) "
            +"values(?,'',localtimestamp,?,'PENDIENTE',?,?,?,'APPROVED_SIMULATED',true,?) returning id",Long.class,
            p.ownerId().toString(),p.importe(),p.operacionId(),p.pagoId(),p.tenantId(),fingerprint);
        for (Item i:p.items()) db.update("insert into item_pedido(pedido_id,producto_id,nombre_producto,cantidad,precio_unitario) values(?,?,?,?,?)",
            id,i.productoId(),i.nombreProducto(),i.cantidad(),i.precioUnitario());
        return result(id,p);
    }
    private Map<String,Object> result(Object id,Paid p) {
        return Map.of("pedidoId",id,"pagoId",p.pagoId(),"total",p.importe(),"estado","PENDIENTE",
            "paymentStatus","APPROVED_SIMULATED","simulacion",true);
    }
    private ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
