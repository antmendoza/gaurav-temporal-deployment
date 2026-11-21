package io.temporal.demo.failover;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

@WorkflowInterface
public interface OrderWorkflow {

  /** Returns where each step ran, in order. */
  @WorkflowMethod
  List<String> run(String orderId);
}
