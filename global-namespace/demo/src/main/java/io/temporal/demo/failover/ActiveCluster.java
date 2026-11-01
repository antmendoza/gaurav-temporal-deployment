package io.temporal.demo.failover;

import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Picks the client for whichever cluster is active for the namespace. Server 1.32 doesn't forward
 * Workflow Updates from a standby cluster, so the deck has to send them to the active one itself.
 */
public class ActiveCluster {

    private final String namespace;
    private final Map<String, WorkflowClient> clients = new LinkedHashMap<>();

    /**
     * {@code clusters} is "aws=host:port,gcp=host:port".
     */
    public ActiveCluster(String namespace, String clusters) {
        this.namespace = namespace;
        for (String entry : clusters.split(",")) {
            String[] nameAndAddress = entry.trim().split("=", 2);
            clients.put(nameAndAddress[0], client(namespace, nameAndAddress[1]));
        }
    }

    public static WorkflowClient client(String namespace, String address) {
        WorkflowServiceStubs service =
                WorkflowServiceStubs.newServiceStubs(
                        WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
        return WorkflowClient.newInstance(
                service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }



    public WorkflowClient get() {
        String active = null;
        long newest = -1;
        for (WorkflowClient client : clients.values()) {
            try {
                DescribeNamespaceResponse ns =
                        client
                                .getWorkflowServiceStubs()
                                .blockingStub()
                                .withDeadlineAfter(2, TimeUnit.SECONDS)
                                .describeNamespace(
                                        DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
                // Right after a failover the clusters disagree until it replicates. Every failover raises
                // the failover version, so the higher one is the newer answer.
                if (ns.getFailoverVersion() > newest) {
                    newest = ns.getFailoverVersion();
                    active = ns.getReplicationConfig().getActiveClusterName();
                }
            } catch (StatusRuntimeException e) {
                // An unreachable cluster can't be the one to talk to; ask the others.
            }
        }
        WorkflowClient client = clients.get(active);
        if (client == null) {
            throw new IllegalStateException("no reachable cluster in " + clients.keySet() + " is active");
        }
        return client;
    }
}
