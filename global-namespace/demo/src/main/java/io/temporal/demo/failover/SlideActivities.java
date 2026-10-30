package io.temporal.demo.failover;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface SlideActivities {

  String whereAmI();
}
