package com.fazbear.pedidos;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Firma efímera de tests y JWKS público loopback. Ningún token/clave Azure real. */
public abstract class LocalJwtTestSupport {
    protected static final String TENANT = "11111111-1111-4111-8111-111111111111";
    protected static final String ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";
    protected static final String AUD = "22222222-2222-4222-8222-222222222222";
    protected static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    protected static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    protected static final RSAKey KEY = key();
    private static final HttpServer JWKS = server();

    static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("ephemeral-test").generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
                exchange.close();
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource
    static void config(DynamicPropertyRegistry p) {
        p.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        p.add("spring.security.oauth2.resourceserver.jwt.client-id", () -> AUD);
        p.add("spring.security.oauth2.resourceserver.jwt.tenant-id", () -> TENANT);
        p.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        p.add("spring.datasource.url", () -> "jdbc:h2:mem:pedidos-security-tests;DB_CLOSE_DELAY=-1");
        p.add("spring.jpa.show-sql", () -> "false");
        p.add("spring.h2.console.enabled", () -> "false");
    }
    protected static String token(String owner) { return token(owner, Map.of()); }
    protected static String token(String owner, Map<String, Object> overrides) {
        return token(owner, overrides, KEY);
    }
    protected static String token(String owner, Map<String, Object> overrides, RSAKey key) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", ISSUER); claims.put("aud", AUD); claims.put("tid", TENANT);
        claims.put("oid", owner); claims.put("sub", "pairwise-sub-not-oid");
        claims.put("scp", "access_as_user"); claims.put("iat", Date.from(Instant.now()));
        claims.put("exp", Date.from(Instant.now().plusSeconds(300))); claims.putAll(overrides);
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder(); claims.forEach(builder::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).build(), builder.build());
            jwt.sign(new RSASSASigner(key)); return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
