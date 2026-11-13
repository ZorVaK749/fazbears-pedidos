package com.fazbear.pedidos;

import com.fazbear.pedidos.controller.InternalPaidPedidoController;
import com.fazbear.pedidos.controller.InternalPaidPedidoController.*;
import java.math.BigDecimal;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches="jdbc:postgresql:.*")
class InternalPaidPostgresTest {
    String tenant="ee0d2c5b-e60b-4c57-9b30-8a38a520c430",schema;
    JdbcTemplate db; TransactionTemplate tx; InternalPaidPedidoController controller;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),System.getenv("TEST_DATABASE_USER"),System.getenv("TEST_DATABASE_PASSWORD"));
        schema="pedidos_test_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(ds).execute("create schema "+schema);
        ds.setUrl(System.getenv("TEST_DATABASE_URL")+(System.getenv("TEST_DATABASE_URL").contains("?")?"&":"?")+"currentSchema="+schema);
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Flyway.configure().dataSource(ds).schemas(schema).load().migrate();
        controller=new InternalPaidPedidoController(db,"test-service-key",tenant);
    }
    @AfterEach void cleanup() { if(db!=null) db.execute("drop schema "+schema+" cascade"); }
    Paid request(UUID operation,UUID payment,UUID owner,String total) {
        return new Paid(operation,payment,UUID.fromString(tenant),owner,new BigDecimal(total),"CLP",true,
            List.of(new Item(1L,"Pizza",2,new BigDecimal("1250.00"))));
    }
    Map<String,Object> create(Paid p) { return tx.execute(s -> controller.create("test-service-key",p)); }
    @Test void serviceKeyTotalAndIdempotentReferences() {
        var op=UUID.randomUUID(); var payment=UUID.randomUUID(); var owner=UUID.randomUUID(); var p=request(op,payment,owner,"2500.00");
        assertThrows(ResponseStatusException.class,() -> tx.execute(s -> controller.create("wrong",p)));
        assertThrows(ResponseStatusException.class,() -> create(request(op,payment,owner,"1")));
        var result=create(p); assertEquals(result,create(p));
        assertThrows(ResponseStatusException.class,() -> create(request(op,payment,UUID.randomUUID(),"2500.00")));
        assertThrows(ResponseStatusException.class,() -> create(request(UUID.randomUUID(),payment,owner,"2500.00")));
        assertEquals(1,db.queryForObject("select count(*) from pedidos",Integer.class));
        assertEquals("APPROVED_SIMULATED",db.queryForObject("select payment_status from pedidos",String.class));
        assertEquals(owner.toString(),db.queryForObject("select usuario_id from pedidos",String.class));
        db.update("update pedidos set estado='CANCELADO'");
        assertEquals("APPROVED_SIMULATED",db.queryForObject("select payment_status from pedidos",String.class));
    }
    @Test void concurrentRetriesCreateSingleOrder() throws Exception {
        var p=request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"2500.00");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<java.util.concurrent.Callable<Map<String,Object>>>();
            for(int i=0;i<8;i++) tasks.add(() -> create(p));
            var results=pool.invokeAll(tasks); Object id=results.get(0).get().get("pedidoId");
            for(var f:results) assertEquals(id,f.get().get("pedidoId"));
            assertEquals(1,db.queryForObject("select count(*) from pedidos",Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
