# F1.4 — propiedad, identidad y cierre administrativo de Pedidos

Fecha: 12/11/2026. Base `ev3/ci-pedidos`:
`e4438d1b6ba2c7e7eb6b622728e7d9abec1a7a88`.
Rama de trabajo: `ev3/identidad-seguridad-pedidos-f1-4`.
Sin merge, imágenes, AWS ni eventos. Fuentes EV2 preservadas en checkout original.

## Identidad y matriz efectiva

Misma política de Carrito F1.2/F1.3: JWT validado por Nimbus (firma/JWKS, issuer,
audience, vigencia), tid exactamente igual al tenant permitido y oid GUID de usuario
delegado con scope exacto access_as_user. No sub/email/localAccountId ni admin por correo.
Clave lógica `(tenant fijo, oid)`, persistencia conserva usuario_id=oid. Cambiar
tenant sobre datos compartidos requiere aislamiento/migración, no whitelist abierta.
Sin token/validación JWT fallida → 401; oid/scope inválidos → 403.

| Ruta | Política implementada |
|---|---|
| GET `/api/pedidos` | 403: administración cerrada hasta política Azure verificada |
| GET `/api/pedidos/{id}` | Propietario 200; ajeno/inexistente 404 sin revelar existencia |
| GET `/api/pedidos/usuario/{usuarioId}` | Path == oid → solo propios; otro ID → 403 |
| POST `/api/pedidos` | Propietario derivado del principal; body usuarioId ausente se deriva, si existe debe coincidir; ajeno → 403 |
| PUT `/api/pedidos/{id}/estado` | Solo cancelación propia PENDIENTE→CANCELADO; repetición propia CANCELADO idempotente; PREPARANDO/COMPLETADO→409, ajeno/inexistente→404 |
| DELETE `/api/pedidos/{id}` | 403: administración cerrada, incluso para el propietario |

PUT a otro estado y POST declarando estado administrativo/terminal → 403. Estado
inválido o ausente en PUT → 400. Se restaura CANCELADO, perdido en EV3 respecto EV2.
Cancelación usa UPDATE condicional por id/owner/estado dentro de transacción;
dos cancelaciones propias simultáneas convergen sin sobrescribir otro estado.
No publica eventos, restaura stock, reembolsa dinero ni revierte reportes.

DTO de creación copia campos de negocio a nuevas entidades, nunca IDs de pedido/
ítems/relaciones del request (previene mass assignment/merge ajeno). Fuerza estado
PENDIENTE. El total/precio/contacto anteriores se conservan por compatibilidad,
**no están certificados como catálogo/precio oficial/email del JWT/pago**.

## Administración: bloqueo explícito de esta fase

No hay evidencia suministrada/verificable de app roles y asignaciones Azure reales;
no se inspeccionó Azure y no se afirma que estén ausentes en el tenant.
Por instrucción del usuario, no se inventó rol ni bypass. requireAdministration
siempre deniega después de validar identidad. Se mantienen rutas/JSON, pero no
se habilita un contrato positivo administrativo no comprobable. Ni roles simulados
Admin/Pedidos.Admin ni el email histórico conceden acceso.

Antes de habilitar administración, usuario/ChatGPT debe aportar/validar:

1. App Registration de API y tenant/audience/issuer efectivos, scope delegado y
   consentimiento; definir permisos administrativos granulares por operación.
2. Aprobar nombres/valores reales de app roles (no se prescribe uno como existente),
   allowedMemberTypes, usuarios/grupos asignados mediante Enterprise Application,
   mínimo privilegio y separación de roles de usuario frente a app-only.
3. Confirmar emisión de roles en **access token de esta API**, no solo ID token,
   y pruebas positivas/negativas de cuentas asignadas/no asignadas. No compartir
   tokens completos; evidencia saneada sin Authorization/cookies/secretos.
4. Autorizar otro bloque de código/pruebas para mapear esos valores verificados,
   validar transiciones/operaciones y exponer capacidades UI comprobadas por backend.
   Configurar el portal por sí solo **no cambia el cierre de este commit**.

Se detiene la habilitación administrativa aquí. Este bloqueo es deliberado y
debe aprobarse como condición de entrega; no anunciar panel admin funcionando.

## CORS

Spring Security central para /api/pedidos/**: frontend
`https://p5gsy16utd.execute-api.us-east-1.amazonaws.com` y `http://localhost:4200`;
GET/POST/PUT/DELETE/OPTIONS; Authorization/Content-Type, sin wildcard/cookies.
Preflight sin JWT antes de negocio; requests reales mantienen controles de JWT/
propiedad. Origen EV2 y ajenos no permitidos. Gateway/ALB aún no verificados.

## Orden: compatible en forma, no certificado E2E

Inspección solo lectura del snapshot EV3 de Orden cda0597f...:
OrdenService copia request.usuarioId/emailUsuario/ítems, calcula total a partir de
precios del request, añade PENDIENTE y POST a `${msPedidosUrl}/pedidos` con el Bearer
original delegado (base debe terminar en /api). Espera 201 con id numérico y después
publica RabbitMQ y vacía Carrito con ese mismo token.

Con frontend F1.3/F1.4 usuarioId=oid, scope access_as_user y tenant/audience correctos,
el POST sigue funcionando sin token interno nuevo ni Origin. Si request.usuarioId
difiere, se deniega antes de persistir: no corregir silenciosamente una operación
que Orden podría después publicar con identidad incorrecta. No se invocó Orden real.

Plan posterior separado: validar owner antes de cualquier efecto en Orden, cotizar
contra catálogo/precios backend y moneda/redondeo/cantidad/stock, no confiar en
total cliente, idempotencia de orden y coordinación de publicación/limpieza. Un
usuario autenticado aún puede crear pedidos propios directamente; no se garantiza
que solo ms-orden origine POST ni se interpreta existencia de pedido como pago.
No modificar Orden/Productos/bus en esta fase.

## Pruebas ejecutadas

`mvn -B clean verify`: **45 tests**, cero fallos/errores/omitidos, BUILD SUCCESS,
JAR ejecutable. 12/11/2026 10:07 UTC-03. JWT RS256 efímeros firmados, JWKS público
loopback y filtros/decoder/controlador/servicio/JPA/H2 reales; sin Azure ni brokers.
Incluye A/B, scopes/identidad, firma/iss/aud/tid/exp/nbf, 401 en seis rutas,
admin siempre denegado, JSON Orden, inyección IDs, estados y concurrencia de cancelación,
CORS. Warnings H2Dialect/open-in-view/Mockito visibles. H2 no prueba PostgreSQL/AWS.
Sin cambios pom/dependencias, Dockerfile/workflow. Docker histórico omite tests;
estos resultados se ejecutaron por separado y no se publicó imagen.

## Riesgos/despliegue futuro (NO ejecutado)

- Datos históricos con sub/alias no se migran automáticamente; demostrar propiedad
  antes de reconciliar claves. H2 mem/create-drop pierde pedidos al reiniciar;
  planificar exportación/migración antes de swap. Rollback de imagen no restaura BD.
- H2 console histórica permitida y precios/total cliente permanecen pendientes.
- Backend Reportes/otros servicios no asegurados por este cambio; bloqueo UI no
  equivale a autorización de su backend. Administración de Pedidos permanece 403.
- Acordar cierre admin y política Azure antes de publicar/desplegar. Usuario/ChatGPT
  recopila por SSM digest/config/TG/rutas/puertos sin reutilizar IDs EV2 ni secretos.
- Despliegue coordinado posterior: Carrito con /me (F1.3) y Pedidos F1.4 primero,
  luego frontend F1.4; comprobar audience/issuer/tenant/scope, rutas /api y CORS.
- Validar A/B y cancelación en registros desechables/autorizados, sin compras reales,
  SMTP o eventos. Mantener admin 403 hasta otro bloque de roles aprobado. Registrar
  digests/config/evidencia de ambos componentes antes de declarar VALIDADO E2E.

IMPLEMENTADO / PROBADO LOCALMENTE; publicación registrada en informe acumulativo.
NO DESPLEGADO / NO VALIDADO E2E. PAUSADO — esperando autorización.
