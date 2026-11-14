package com.soumikseth.incidentinvestigator.engine.tools;

import com.soumikseth.incidentinvestigator.contracts.DeploymentView;
import com.soumikseth.incidentinvestigator.contracts.IncidentView;
import com.soumikseth.incidentinvestigator.contracts.LogEntryView;
import com.soumikseth.incidentinvestigator.contracts.MetricSeries;
import com.soumikseth.incidentinvestigator.contracts.ServiceHealth;
import com.soumikseth.incidentinvestigator.contracts.ServiceTopology;
import com.soumikseth.incidentinvestigator.contracts.TraceView;
import com.soumikseth.incidentinvestigator.engine.investigation.IncidentNotFoundException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Component
public class SimulatorGateway implements TelemetryGateway {

    private static final ParameterizedTypeReference<List<MetricSeries>> METRIC_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<LogEntryView>> LOG_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<TraceView>> TRACE_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<DeploymentView>> DEPLOYMENT_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;

    public SimulatorGateway(SimulatorClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public ServiceTopology topology() {
        return client.get().uri("/api/services").retrieve().body(ServiceTopology.class);
    }

    @Override
    public ServiceHealth serviceHealth(String service) {
        return client.get()
                .uri("/api/services/{service}/health", service)
                .retrieve()
                .body(ServiceHealth.class);
    }

    @Override
    public List<MetricSeries> metrics(String service, String metric, int minutes) {
        URI uri = UriComponentsBuilder.fromPath("/api/telemetry/metrics")
                .queryParam("service", service)
                .queryParam("metric", metric)
                .queryParam("minutes", minutes)
                .build()
                .toUri();
        return client.get().uri(uri).retrieve().body(METRIC_LIST);
    }

    @Override
    public List<LogEntryView> logs(String service, String contains, int minutes, int limit) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/api/telemetry/logs")
                .queryParam("service", service)
                .queryParam("minutes", minutes)
                .queryParam("limit", limit);
        if (contains != null && !contains.isBlank()) {
            builder.queryParam("contains", contains);
        }
        return client.get().uri(builder.build().toUri()).retrieve().body(LOG_LIST);
    }

    @Override
    public List<TraceView> slowTraces(String service, long minDurationMs, int minutes, int limit) {
        URI uri = UriComponentsBuilder.fromPath("/api/telemetry/traces")
                .queryParam("service", service)
                .queryParam("minutes", minutes)
                .queryParam("minDurationMs", minDurationMs)
                .queryParam("limit", limit)
                .build()
                .toUri();
        return client.get().uri(uri).retrieve().body(TRACE_LIST);
    }

    @Override
    public List<DeploymentView> deployments(String service, int minutes) {
        URI uri = UriComponentsBuilder.fromPath("/api/telemetry/deployments")
                .queryParam("service", service)
                .queryParam("minutes", minutes)
                .build()
                .toUri();
        return client.get().uri(uri).retrieve().body(DEPLOYMENT_LIST);
    }

    @Override
    public IncidentView incident(String id) {
        try {
            return client.get().uri("/api/incidents/{id}", id).retrieve().body(IncidentView.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new IncidentNotFoundException(id);
        }
    }
}
