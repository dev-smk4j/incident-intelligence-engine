package com.soumikseth.incidentinvestigator.engine.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeGatewayConfiguration {

    @Bean
    @Primary
    public FakeTelemetryGateway fakeTelemetryGateway() {
        return new FakeTelemetryGateway();
    }
}
