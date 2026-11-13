# Paid simulated orders — EV3 local

`Dockerfile.local`, `SPRING_PROFILES_ACTIVE=ev3-local`, port **8080**.
Use a NEW dedicated PostgreSQL database: `PEDIDOS_DB_URL`, `PEDIDOS_DB_USER`, `PEDIDOS_DB_PASSWORD`.
Flyway migrates, Hibernate validates, no automatic SQL seeds or H2 console. Historical defaults remain untouched.
`INTERNAL_SERVICE_KEY` is mandatory and shared only by trusted local services; never expose it to frontend.
Optional `LOCAL_JWT_JWK_SET_URI` is mapped only by this profile; issuer/audience/tenant stay Azure-compatible.

`POST /internal/pedidos/pagados` requires **X-Service-Key**, not an owner JWT or fabricated admin role.
Active only under `ev3-local` (404 outside it). Body:

```json
{"operacionId":"uuid","pagoId":"uuid","tenantId":"uuid","ownerId":"uuid",
 "importe":2500.00,"moneda":"CLP","simulacion":true,
 "items":[{"productoId":1,"nombreProducto":"Pizza","cantidad":2,"precioUnitario":1250.00}]}
```

This is a trust-boundary endpoint: Orden must verify Pago first. Pedidos checks secret, configured tenant,
UUID references, positive prices/quantities, unique product IDs, and computed total equality. Persists unique
operation/payment references; identical replay returns original `pedidoId`, changed operation payload or reused
payment returns 409. Response includes `paymentStatus:"APPROVED_SIMULATED"`, `simulacion:true`, operational
`estado:"PENDIENTE"`. Owner GETs expose the separate payment metadata. No payment status inferred from order state.
F1.4 delegated APIs keep strict oid owner and fail-closed administration. Cancellation changes only operational state;
it does not refund and does not change payment status. Pago is authoritative for later refunds; local paymentStatus is
a creation-time snapshot, not synchronized refund accounting. No direct bank or AWS calls.

Startup requires dedicated PostgreSQL; JWT JWKS server must be reachable when validating bearer tokens.
Real PG tests: `TEST_DATABASE_URL`, `TEST_DATABASE_USER`, `TEST_DATABASE_PASSWORD`, then `mvn verify`.
Only randomly named `pedidos_test_*` schemas are created/dropped. Existing F1.4 tests use disposable in-memory H2.
CI starts isolated PostgreSQL, runs validation and builds `Dockerfile.local`; no registry publication.
