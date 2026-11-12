package com.fazbear.pedidos.security;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class PedidoAuthorization {
    private static final Pattern OID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final String tenantId;

    public PedidoAuthorization(@Value("${spring.security.oauth2.resourceserver.jwt.tenant-id}") String tenantId) {
        this.tenantId = tenantId;
    }

    public String owner(Jwt jwt) {
        if (jwt == null || !tenantId.equals(jwt.getClaims().get("tid"))) throw denied();
        Object oid = jwt.getClaims().get("oid");
        Object scp = jwt.getClaims().get("scp");
        if (!(oid instanceof String id) || !OID.matcher(id).matches()
                || !(scp instanceof String scopes)
                || Arrays.stream(scopes.split("\\s+")).noneMatch("access_as_user"::equals)) throw denied();
        return id;
    }

    public String requireOwner(Jwt jwt, String requested) {
        String owner = owner(jwt);
        if (!owner.equals(requested)) throw denied();
        return owner;
    }

    /** Cerrado hasta verificar/configurar roles Entra: no email, rol inventado o bypass. */
    public void requireAdministration(Jwt jwt) {
        owner(jwt);
        throw new AccessDeniedException("Administración pendiente de configuración y aprobación Azure");
    }

    private AccessDeniedException denied() {
        return new AccessDeniedException("No autorizado para estos pedidos");
    }
}
