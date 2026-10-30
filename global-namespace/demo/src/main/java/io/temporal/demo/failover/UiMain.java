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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

public class UiMain {

  private static final ObjectMapper json = new ObjectMapper();

  public static void main(String[] args) throws IOException {
    String address = env("TEMPORAL_ADDRESS", "localhost:7233");
    String namespace = env("TEMPORAL_NAMESPACE", "replicated");
    String workflowId = env("WORKFLOW_ID", "presentation");
    int port = Integer.parseInt(env("PORT", "8080"));

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
    server.start();

    System.out.printf(
        "ui on http://localhost:%d  address=%s namespace=%s workflowId=%s%n",
        port, address, namespace, workflowId);
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
