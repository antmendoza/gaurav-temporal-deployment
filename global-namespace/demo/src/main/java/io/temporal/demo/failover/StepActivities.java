package io.temporal.demo.failover;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface StepActivities {

  String recordStep(int step);
}
