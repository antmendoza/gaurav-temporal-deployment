package io.temporal.demo.failover;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;

@WorkflowInterface
public interface FailoverWorkflow {

  @WorkflowMethod
  List<String> run(int steps);

  @QueryMethod
  List<String> progress();
}
