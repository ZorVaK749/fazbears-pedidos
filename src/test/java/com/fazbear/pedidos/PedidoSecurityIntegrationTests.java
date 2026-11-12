package com.fazbear.pedidos;

import com.fazbear.pedidos.model.EstadoPedido;
import com.fazbear.pedidos.repository.PedidoRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PedidoSecurityIntegrationTests extends LocalJwtTestSupport {
    static final String BASE = "/api/pedidos";
    static final String FRONT = "https://p5gsy16utd.execute-api.us-east-1.amazonaws.com";
    @Autowired MockMvc mvc;
    @Autowired PedidoRepository repo;
    @BeforeEach void clean() { repo.deleteAll(); }

    String body(String owner) {
        return "{\"usuarioId\":\"" + owner + "\",\"emailUsuario\":\"fixture@example.com\","
            + "\"estado\":\"PENDIENTE\",\"total\":25.98,\"items\":[{\"productoId\":7,"
            + "\"nombreProducto\":\"Pizza\",\"cantidad\":2,\"precioUnitario\":12.99}]}";
    }
    long create(String owner) throws Exception {
        String json = mvc.perform(post(BASE).header("Authorization", "Bearer " + token(owner))
            .contentType("application/json").content(body(owner))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.usuarioId").value(owner)).andExpect(jsonPath("$.estado").value("PENDIENTE"))
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test void ownQueriesNeverExposeOtherOrders() throws Exception {
        long a = create(A), b = create(B);
        mvc.perform(get(BASE + "/usuario/" + A).header("Authorization", "Bearer " + token(A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(a));
        mvc.perform(get(BASE + "/" + a).header("Authorization", "Bearer " + token(A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].cantidad").value(2));
        mvc.perform(get(BASE + "/" + a).header("Authorization", "Bearer " + token(B)))
            .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/usuario/" + A).header("Authorization", "Bearer " + token(B)))
            .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/usuario/" + B).header("Authorization", "Bearer " + token(B)))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(b));
    }

    @Test void forgedBodyOwnerRejectedAndMissingOwnerDerivedFromJwt() throws Exception {
        mvc.perform(post(BASE).header("Authorization", "Bearer " + token(A))
            .contentType("application/json").content(body(B))).andExpect(status().isForbidden());
        assertEquals(0, repo.count());
        mvc.perform(post(BASE).header("Authorization", "Bearer " + token(A))
            .contentType("application/json").content(body(A).replace("\"usuarioId\":\"" + A + "\",", "")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.usuarioId").value(A));
    }

    @Test void bodyCannotOverwriteExistingOrderOrItemIds() throws Exception {
        long original = create(B);
        String before = mvc.perform(get(BASE + "/" + original).header("Authorization", "Bearer " + token(B)))
            .andReturn().getResponse().getContentAsString();
        Number itemId = JsonPath.read(before, "$.items[0].id");
        String malicious = body(A).replaceFirst("\\{", "{\"id\":" + original + ",")
            .replace("\"productoId\":7", "\"id\":" + itemId + ",\"productoId\":7");
        mvc.perform(post(BASE).header("Authorization", "Bearer " + token(A))
            .contentType("application/json").content(malicious)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.usuarioId").value(A));
        assertEquals(2, repo.count());
        mvc.perform(get(BASE + "/" + original).header("Authorization", "Bearer " + token(B)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(itemId));
    }

    @ParameterizedTest @ValueSource(strings = {"PREPARANDO", "COMPLETADO", "CANCELADO"})
    void cannotDeclareAdministrativeOrTerminalStateOnCreate(String state) throws Exception {
        mvc.perform(post(BASE).header("Authorization", "Bearer " + token(A)).contentType("application/json")
            .content(body(A).replace("PENDIENTE", state))).andExpect(status().isForbidden());
        assertEquals(0, repo.count());
    }

    @Test void ordenDelegatedRequestContractStillCreatesPendingOrderWithoutOrigin() throws Exception {
        // Misma ruta, JSON y Bearer delegado que el snapshot de Orden, sin invocarlo.
        long id = create(A);
        mvc.perform(get(BASE + "/" + id).header("Authorization", "Bearer " + token(A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(25.98))
            .andExpect(jsonPath("$.emailUsuario").value("fixture@example.com"));
    }

    @Test void ownerCanCancelPendingOnceAndReplayIsIdempotent() throws Exception {
        long id = create(A);
        for (int i = 0; i < 2; i++) mvc.perform(put(BASE + "/" + id + "/estado")
            .header("Authorization", "Bearer " + token(A)).contentType("application/json")
            .content("{\"estado\":\"CANCELADO\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.estado").value("CANCELADO"));
        assertEquals(EstadoPedido.CANCELADO, repo.findById(id).orElseThrow().getEstado());
    }

    @Test void foreignCancellationDoesNotChangeOrder() throws Exception {
        long id = create(A);
        mvc.perform(put(BASE + "/" + id + "/estado").header("Authorization", "Bearer " + token(B))
            .contentType("application/json").content("{\"estado\":\"CANCELADO\"}"))
            .andExpect(status().isNotFound());
        assertEquals(EstadoPedido.PENDIENTE, repo.findById(id).orElseThrow().getEstado());
    }

    @ParameterizedTest @ValueSource(strings = {"PREPARANDO", "COMPLETADO"})
    void cancellationIsNotAllowedAfterPending(String state) throws Exception {
        long id = create(A);
        var p = repo.findById(id).orElseThrow(); p.setEstado(EstadoPedido.valueOf(state)); repo.save(p);
        mvc.perform(put(BASE + "/" + id + "/estado").header("Authorization", "Bearer " + token(A))
            .contentType("application/json").content("{\"estado\":\"CANCELADO\"}"))
            .andExpect(status().isConflict());
        assertEquals(EstadoPedido.valueOf(state), repo.findById(id).orElseThrow().getEstado());
    }

    @Test void concurrentCancellationsRemainIdempotent() throws Exception {
        long id = create(A);
        var one = CompletableFuture.supplyAsync(() -> cancelStatus(id));
        var two = CompletableFuture.supplyAsync(() -> cancelStatus(id));
        assertEquals(200, one.join()); assertEquals(200, two.join());
        assertEquals(EstadoPedido.CANCELADO, repo.findById(id).orElseThrow().getEstado());
    }
    int cancelStatus(long id) {
        try { return mvc.perform(put(BASE + "/" + id + "/estado").header("Authorization", "Bearer " + token(A))
            .contentType("application/json").content("{\"estado\":\"CANCELADO\"}"))
            .andReturn().getResponse().getStatus(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @ParameterizedTest @ValueSource(strings = {"PREPARANDO", "COMPLETADO", "PENDIENTE"})
    void ordinaryUserCannotAdvanceOrResetState(String state) throws Exception {
        long id = create(A);
        mvc.perform(put(BASE + "/" + id + "/estado").header("Authorization", "Bearer " + token(A))
            .contentType("application/json").content("{\"estado\":\"" + state + "\"}"))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"", "Admin", "Pedidos.Admin"})
    void unverifiedRolesAndKnownEmailNeverGrantAdministration(String role) throws Exception {
        long id = create(A);
        String jwt = token(A, Map.of("roles", new String[]{role}, "preferred_username", "vic.placencia@duocuc.cl"));
        mvc.perform(get(BASE).header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/" + id).header("Authorization", "Bearer " + jwt))
            .andExpect(status().isForbidden());
        assertEquals(1, repo.count());
    }

    static Stream<Arguments> routes() { return Stream.of(
        Arguments.of("GET", ""), Arguments.of("GET", "/1"), Arguments.of("GET", "/usuario/" + A),
        Arguments.of("POST", ""), Arguments.of("PUT", "/1/estado"), Arguments.of("DELETE", "/1")); }
    @ParameterizedTest @MethodSource("routes")
    void noTokenIs401ForEveryRoute(String method, String path) throws Exception {
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(method), BASE + path);
        if (method.equals("POST")) req.contentType("application/json").content(body(A));
        if (method.equals("PUT")) req.contentType("application/json").content("{\"estado\":\"CANCELADO\"}");
        mvc.perform(req).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"oid", "invalid-oid", "scope", "wrong-scope", "sub", "app-only"})
    void invalidDelegatedIdentityIs403(String variant) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        switch (variant) {
            case "oid" -> claims.put("oid", null);
            case "invalid-oid" -> claims.put("oid", 42);
            case "scope" -> claims.put("scp", null);
            case "wrong-scope" -> claims.put("scp", "not_access_as_user");
            case "sub" -> { claims.put("oid", null); claims.put("sub", A); }
            case "app-only" -> { claims.put("scp", null); claims.put("roles", new String[]{"Admin"}); }
        }
        mvc.perform(get(BASE + "/usuario/" + A).header("Authorization", "Bearer " + token(A, claims)))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"issuer", "aud", "tenant", "missing-tenant", "numeric-tenant", "expired", "future", "signature", "malformed"})
    void invalidJwtIs401(String variant) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        switch (variant) {
            case "issuer" -> claims.put("iss", "https://wrong.example");
            case "aud" -> claims.put("aud", "other-api");
            case "tenant" -> claims.put("tid", "33333333-3333-4333-8333-333333333333");
            case "missing-tenant" -> claims.put("tid", null);
            case "numeric-tenant" -> claims.put("tid", 42);
            case "expired" -> claims.put("exp", Date.from(Instant.now().minusSeconds(300)));
            case "future" -> claims.put("nbf", Date.from(Instant.now().plusSeconds(300)));
        }
        String jwt = variant.equals("signature") ? token(A, claims, key()) : token(A, claims);
        if (variant.equals("malformed")) jwt = "invalid";
        mvc.perform(get(BASE + "/usuario/" + A).header("Authorization", "Bearer " + jwt))
            .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {FRONT, "http://localhost:4200"})
    void allowedCorsPreflightAndJwtErrors(String origin) throws Exception {
        for (String method : new String[]{"GET", "POST", "PUT", "DELETE"}) {
            mvc.perform(options(BASE + "/1/estado").header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
        mvc.perform(get(BASE).header("Origin", origin)).andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }
    @ParameterizedTest @ValueSource(strings = {"https://evil.example", "https://w8xu8o4pd7.execute-api.us-east-1.amazonaws.com"})
    void foreignCorsRejected(String origin) throws Exception {
        mvc.perform(options(BASE).header("Origin", origin).header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void invalidStateAndMissingResourcesDoNotChangeData() throws Exception {
        long id = create(A);
        for (String json : new String[]{"{}", "{\"estado\":\"UNKNOWN\"}"}) mvc.perform(put(BASE + "/" + id + "/estado")
            .header("Authorization", "Bearer " + token(A)).contentType("application/json").content(json))
            .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/999999").header("Authorization", "Bearer " + token(A)))
            .andExpect(status().isNotFound());
        assertEquals(EstadoPedido.PENDIENTE, repo.findById(id).orElseThrow().getEstado());
    }
}
