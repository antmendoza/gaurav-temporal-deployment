package io.temporal.demo.failover;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface OrderActivities {

  String reserve(String orderId);

  String charge(String orderId);

  String ship(String orderId);
}
