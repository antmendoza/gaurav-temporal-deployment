package io.temporal.demo.failover;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Starts an {@link OrderWorkflow} every {@code START_EVERY_SECONDS}, so there are always workflows
 * in flight when the namespace fails over. Starts are fire-and-forget; a start that reaches the
 * standby cluster is forwarded by selected-apis-forwarding.
 */
public class LoadMain {

    public static void main(String[] args) {
        String address = env("TEMPORAL_ADDRESS", "localhost:7233");
        String namespace = env("TEMPORAL_NAMESPACE", "replicated");
        String taskQueue = env("TASK_QUEUE", "replicated-demo");
        long everySeconds = Long.parseLong(env("START_EVERY_SECONDS", "10"));

        WorkflowClient client = ActiveCluster.client(namespace, address);
        String prefix = "order-" + System.currentTimeMillis() / 1000;
        AtomicLong counter = new AtomicLong();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            String orderId = prefix + "-" + counter.incrementAndGet();
            try {
                OrderWorkflow workflow =
                        client.newWorkflowStub(
                                OrderWorkflow.class,
                                WorkflowOptions.newBuilder().setTaskQueue(taskQueue).setWorkflowId(orderId).build());
                WorkflowClient.start(workflow::run, orderId);
                System.out.printf("started %s%n", orderId);
            } catch (RuntimeException e) {
                // An uncaught exception would cancel the schedule; keep generating load through a failover.
                System.out.printf("start %s failed: %s%n", orderId, e);
            }
        }, 0, everySeconds, TimeUnit.SECONDS);

        System.out.printf(
                "load started: every=%ds address=%s namespace=%s taskQueue=%s%n",
                everySeconds, address, namespace, taskQueue);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
