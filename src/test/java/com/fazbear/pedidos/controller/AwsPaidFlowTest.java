package com.fazbear.pedidos.controller;

import com.fazbear.pedidos.service.PedidoService;
import com.fazbear.pedidos.security.PedidoAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AwsPaidFlowTest {
    @Test void awsCannotBypassPaymentByPostingDirectlyToPedidos() {
        var service=mock(PedidoService.class);
        var auth=mock(PedidoAuthorization.class);
        var controller=new PedidoController(service,auth);
        var env=new MockEnvironment(); env.setActiveProfiles("ev3-aws");
        ReflectionTestUtils.setField(controller,"environment",env);
        var error=assertThrows(ResponseStatusException.class, () -> controller.create(null,null));
        assertEquals(409,error.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
