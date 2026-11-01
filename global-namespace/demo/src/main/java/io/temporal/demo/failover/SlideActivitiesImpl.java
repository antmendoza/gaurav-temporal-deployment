package io.temporal.demo.failover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlideActivitiesImpl implements SlideActivities {

  private static final Logger log = LoggerFactory.getLogger(SlideActivitiesImpl.class);

  private final String cloud;
  private final String host;

  public SlideActivitiesImpl(String cloud, String host) {
    this.cloud = cloud;
    this.host = host;
  }

  @Override
  public String whereAmI() {
    String servedBy = String.format("%s (%s)", cloud, host);
    log.info("Slide served by {}", servedBy);
    return servedBy;
  }
}
