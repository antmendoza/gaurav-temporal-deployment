package io.temporal.demo.failover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.client.WorkflowClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

public class UiMain {

    private static final ObjectMapper json = new ObjectMapper();
    private static final HttpClient http = HttpClient.newHttpClient();
    // HttpClient refuses to set these, and the response ones are recomputed by HttpServer.
    private static final Set<String> HOP_BY_HOP =
            Set.of("connection", "content-length", "expect", "host", "upgrade", "transfer-encoding");

    public static void main(String[] args) throws IOException {
        String address = env("TEMPORAL_ADDRESS", "localhost:7233");
        String namespace = env("TEMPORAL_NAMESPACE", "replicated");
        String workflowId = env("WORKFLOW_ID", "presentation");
        int port = Integer.parseInt(env("PORT", "8080"));
        String temporalUi = env("TEMPORAL_UI", "http://localhost:8233");
        // "aws=host:port,gcp=host:port": send every call to whichever cluster is active.
        String clusters = env("TEMPORAL_CLUSTERS", "");
        // "worker-aws,worker-gcp": Compose services whose logs the deck shows, when the Docker socket is mounted.
        List<String> logServices =
                Arrays.stream(env("WORKER_LOGS", "").split(",")).filter(s -> !s.isBlank()).toList();

        Supplier<WorkflowClient> client;
        ActiveCluster router = null;
        if (clusters.isEmpty()) {
            WorkflowClient single = ActiveCluster.client(namespace, address);
            client = () -> single;
        } else {
            router = new ActiveCluster(namespace, clusters);
            client = router::get;
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", UiMain::index);
        server.createContext("/api/current", ex -> slide(ex, client, workflowId, PresentationWorkflow::current));
        server.createContext("/api/next", ex -> slide(ex, client, workflowId, PresentationWorkflow::next));
        server.createContext("/api/previous", ex -> slide(ex, client, workflowId, PresentationWorkflow::previous));
        if (router != null) {
            ActiveCluster clustersRouter = router;
            server.createContext("/api/cluster", ex -> cluster(ex, clustersRouter));
            server.createContext("/api/failover", ex -> failover(ex, clustersRouter));
        }
        if (!logServices.isEmpty() && WorkerLogs.available()) {
            WorkerLogs logs = new WorkerLogs(env("COMPOSE_PROJECT", "failover-demo"));
            server.createContext("/api/logs", ex -> logs(ex, logs, logServices));
        }
        // The Temporal UI sends X-Frame-Options: SAMEORIGIN, so the deck can only iframe it from its
        // own origin. The UI must be served under the same path (--ui-public-path /temporal).
        server.createContext("/temporal", ex -> proxy(ex, temporalUi));
        // The UI holds long polls open on history; the default single thread would stall every click.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.printf(
                "ui on http://localhost:%d  address=%s namespace=%s workflowId=%s temporalUi=%s%n",
                port, clusters.isEmpty() ? address : clusters, namespace, workflowId, temporalUi);
    }

    private static void index(HttpExchange ex) throws IOException {
        try (InputStream in = UiMain.class.getResourceAsStream("/index.html")) {
            send(ex, 200, "text/html", in.readAllBytes());
        }
    }

    private static void slide(
            HttpExchange ex,
            Supplier<WorkflowClient> client,
            String workflowId,
            Function<PresentationWorkflow, Slide> call)
            throws IOException {
        try {
            PresentationWorkflow deck =
                    client.get().newWorkflowStub(PresentationWorkflow.class, workflowId);
            send(ex, 200, "application/json", json.writeValueAsBytes(call.apply(deck)));
        } catch (RuntimeException e) {
            send(ex, 502, "application/json", json.writeValueAsBytes(Map.of("error", rootMessage(e))));
        }
    }

    private static void cluster(HttpExchange ex, ActiveCluster router) throws IOException {
        try {
            send(ex, 200, "application/json",
                    json.writeValueAsBytes(Map.of("active", router.activeName(), "clusters", router.names())));
        } catch (RuntimeException e) {
            send(ex, 502, "application/json", json.writeValueAsBytes(Map.of("error", rootMessage(e))));
        }
    }

    private static void failover(HttpExchange ex, ActiveCluster router) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String to = query != null && query.startsWith("to=") ? query.substring(3) : "";
        try {
            router.failover(to);
            send(ex, 200, "application/json", json.writeValueAsBytes(Map.of("active", to)));
        } catch (RuntimeException e) {
            send(ex, 502, "application/json", json.writeValueAsBytes(Map.of("error", rootMessage(e))));
        }
    }

    /** Server-sent events: one {"service", "line"} event per worker log line, until the page goes away. */
    private static void logs(HttpExchange ex, WorkerLogs logs, List<String> services) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        List<Thread> followers =
                services.stream()
                        .map(service -> Thread.ofVirtual().start(() -> {
                            try {
                                logs.follow(service, 20, line -> event(out, Map.of("service", service, "line", line)));
                            } catch (IOException | UncheckedIOException e) {
                                // The page went away or Docker stopped the stream; the other follower ends the same way.
                            }
                        }))
                        .toList();
        for (Thread follower : followers) {
            try {
                follower.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ex.close();
    }

    private static void event(OutputStream out, Map<String, String> data) {
        try {
            byte[] bytes = ("data: " + json.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void proxy(HttpExchange ex, String upstream) throws IOException {
        HttpRequest.Builder req =
                HttpRequest.newBuilder(URI.create(upstream + ex.getRequestURI()))
                        .method(
                                ex.getRequestMethod(),
                                HttpRequest.BodyPublishers.ofByteArray(ex.getRequestBody().readAllBytes()));
        ex.getRequestHeaders().forEach(
                (name, values) -> {
                    if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                        values.forEach(v -> req.header(name, v));
                    }
                });
        HttpResponse<byte[]> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        res.headers().map().forEach(
                (name, values) -> {
                    if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                        ex.getResponseHeaders().put(name, List.copyOf(values));
                    }
                });
        byte[] body = res.body();
        ex.sendResponseHeaders(res.statusCode(), body.length == 0 ? -1 : body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) {
            e = e.getCause();
        }
        return e.getMessage();
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
