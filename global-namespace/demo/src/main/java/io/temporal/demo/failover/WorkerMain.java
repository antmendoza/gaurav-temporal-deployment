package io.temporal.demo.failover;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class WorkerMain {

  public static void main(String[] args) throws UnknownHostException {
    String address = env("TEMPORAL_ADDRESS", "localhost:7233");
    String namespace = env("TEMPORAL_NAMESPACE", "replicated");
    String taskQueue = env("TASK_QUEUE", "replicated-demo");
    String cloud = env("CLOUD", "local");

    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
    WorkflowClient client =
        WorkflowClient.newInstance(
            service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(FailoverWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new StepActivitiesImpl(cloud, InetAddress.getLocalHost().getHostName()));
    factory.start();

    System.out.printf(
        "worker started: cloud=%s address=%s namespace=%s taskQueue=%s%n",
        cloud, address, namespace, taskQueue);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
