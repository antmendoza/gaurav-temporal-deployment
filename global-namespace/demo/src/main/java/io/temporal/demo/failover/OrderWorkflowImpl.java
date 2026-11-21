package io.temporal.demo.failover;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities =
            Workflow.newActivityStub(
                    OrderActivities.class,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public List<String> run(String orderId) {
        return List.of(
                activities.reserve(orderId),
                activities.charge(orderId),
                activities.ship(orderId));
    }
}
