package io.temporal.demo.failover;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class FailoverWorkflowImpl implements FailoverWorkflow {

  private final StepActivities activities =
      Workflow.newActivityStub(
          StepActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

  private final List<String> log = new ArrayList<>();

  @Override
  public List<String> run(int steps) {
    for (int step = 1; step <= steps; step++) {
      log.add(activities.recordStep(step));
      Workflow.sleep(Duration.ofSeconds(10));
    }
    return log;
  }

  @Override
  public List<String> progress() {
    return log;
  }
}
