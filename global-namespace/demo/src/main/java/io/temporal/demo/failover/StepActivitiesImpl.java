package io.temporal.demo.failover;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StepActivitiesImpl implements StepActivities {

  private static final Logger log = LoggerFactory.getLogger(StepActivitiesImpl.class);

  private final String cloud;
  private final String host;

  public StepActivitiesImpl(String cloud, String host) {
    this.cloud = cloud;
    this.host = host;
  }

  @Override
  public String recordStep(int step) {
    String line = String.format("step %d ran on %s (%s) at %s", step, cloud, host, Instant.now());
    log.info(line);
    return line;
  }
}
