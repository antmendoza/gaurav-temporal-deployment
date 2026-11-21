package io.temporal.demo.failover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/** Each step takes about 2 s (1.5–2.5 s) and reports which cloud and pod ran it. */
public class OrderActivitiesImpl implements OrderActivities {

  private static final Logger log = LoggerFactory.getLogger(OrderActivitiesImpl.class);

  private final String servedBy;

  public OrderActivitiesImpl(String cloud, String host) {
    this.servedBy = String.format("%s (%s)", cloud, host);
  }

  @Override
  public String reserve(String orderId) {
    return step("reserve", orderId);
  }

  @Override
  public String charge(String orderId) {
    return step("charge", orderId);
  }

  @Override
  public String ship(String orderId) {
    return step("ship", orderId);
  }

  private String step(String name, String orderId) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1500, 2500));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    log.info("{} {} served by {}", name, orderId, servedBy);
    return name + ": " + servedBy;
  }
}
