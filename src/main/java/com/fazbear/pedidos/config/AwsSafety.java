package com.fazbear.pedidos.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("ev3-aws")
public class AwsSafety implements InitializingBean {
    private final Environment env;
    public AwsSafety(Environment env) { this.env = env; }
    @Override public void afterPropertiesSet() {
        String url = env.getProperty("spring.datasource.url", "");
        String ddl = env.getProperty("spring.jpa.hibernate.ddl-auto", "");
        boolean isPostgres = url.startsWith("jdbc:postgresql:");
        boolean isPersistentH2 = url.startsWith("jdbc:h2:file:");
        boolean isMemH2 = url.startsWith("jdbc:h2:mem:");
        boolean isPostgresSafe = isPostgres && "validate".equals(ddl);
        boolean isH2Safe = isPersistentH2 && ("update".equals(ddl) || "validate".equals(ddl));

        if (env.matchesProfiles("local", "ev3-local", "test")
                || !env.getProperty("LOCAL_JWT_JWK_SET_URI", "").isBlank()
                || !env.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "").isBlank()
                || (!isPostgres && !isPersistentH2)
                || isMemH2
                || (!isPostgresSafe && !isH2Safe)
                || env.getProperty("spring.h2.console.enabled", Boolean.class, false)
                || env.getProperty("INTERNAL_SERVICE_KEY", "").length() < 32)
            throw new IllegalStateException("UNSAFE_EV3_AWS_CONFIGURATION");
    }
}
