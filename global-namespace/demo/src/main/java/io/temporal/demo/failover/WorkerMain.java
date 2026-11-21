package io.temporal.demo.failover;

import com.sun.net.httpserver.HttpServer;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class WorkerMain {


    public static void main(String[] args) throws IOException {
        String address = env("TEMPORAL_ADDRESS", "localhost:7233");
        String namespace = env("TEMPORAL_NAMESPACE", "replicated");
        String taskQueue = env("TASK_QUEUE", "replicated-demo");
        String cloud = env("CLOUD", "local");
        int metricsPort = Integer.parseInt(env("METRICS_PORT", "8077"));

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        Scope metricsScope =
                new RootScopeBuilder()
                        .reporter(new MicrometerClientStatsReporter(registry))
                        .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
        serveMetrics(registry, metricsPort);

        WorkflowServiceStubs service =
                WorkflowServiceStubs.newServiceStubs(
                        WorkflowServiceStubsOptions.newBuilder()
//                                .setRpcLongPollTimeout(Duration.ofSeconds(11))
                                .setMetricsScope(metricsScope)
                                .setTarget(address).build());
        WorkflowClient client =
                WorkflowClient.newInstance(
                        service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(taskQueue,
                WorkerOptions.newBuilder()
                        .setStickyQueueScheduleToStartTimeout(Duration.ofSeconds(1))
                        .build());
        String host = InetAddress.getLocalHost().getHostName();
        worker.registerWorkflowImplementationTypes(PresentationWorkflowImpl.class, OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new SlideActivitiesImpl(cloud, host), new OrderActivitiesImpl(cloud, host));
        factory.start();

        System.out.printf(
                "worker started: cloud=%s address=%s namespace=%s taskQueue=%s metrics=:%d/metrics%n",
                cloud, address, namespace, taskQueue, metricsPort);
    }

    // Prometheus pulls; this is the endpoint the prometheus.io/* pod annotations point it at.
    private static void serveMetrics(PrometheusMeterRegistry registry, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
