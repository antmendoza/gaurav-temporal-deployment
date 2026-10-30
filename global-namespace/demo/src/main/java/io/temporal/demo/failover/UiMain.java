package io.temporal.demo.failover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;

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

    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
    WorkflowClient client =
        WorkflowClient.newInstance(
            service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", UiMain::index);
    server.createContext("/api/current", ex -> slide(ex, client, workflowId, PresentationWorkflow::current));
    server.createContext("/api/next", ex -> slide(ex, client, workflowId, PresentationWorkflow::next));
    server.createContext("/api/previous", ex -> slide(ex, client, workflowId, PresentationWorkflow::previous));
    // The Temporal UI sends X-Frame-Options: SAMEORIGIN, so the deck can only iframe it from its
    // own origin. The UI must be served under the same path (--ui-public-path /temporal).
    server.createContext("/temporal", ex -> proxy(ex, temporalUi));
    // The UI holds long polls open on history; the default single thread would stall every click.
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();

    System.out.printf(
        "ui on http://localhost:%d  address=%s namespace=%s workflowId=%s temporalUi=%s%n",
        port, address, namespace, workflowId, temporalUi);
  }

  private static void index(HttpExchange ex) throws IOException {
    try (InputStream in = UiMain.class.getResourceAsStream("/index.html")) {
      send(ex, 200, "text/html", in.readAllBytes());
    }
  }

  private static void slide(
      HttpExchange ex,
      WorkflowClient client,
      String workflowId,
      Function<PresentationWorkflow, Slide> call)
      throws IOException {
    try {
      PresentationWorkflow deck = client.newWorkflowStub(PresentationWorkflow.class, workflowId);
      send(ex, 200, "application/json", json.writeValueAsBytes(call.apply(deck)));
    } catch (RuntimeException e) {
      send(ex, 502, "application/json", json.writeValueAsBytes(Map.of("error", rootMessage(e))));
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
