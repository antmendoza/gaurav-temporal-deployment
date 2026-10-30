package io.temporal.demo.failover;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PresentationWorkflow {

  @WorkflowMethod
  void run();

  @UpdateMethod
  Slide next();

  @UpdateMethod
  Slide previous();

  @QueryMethod
  Slide current();
}
