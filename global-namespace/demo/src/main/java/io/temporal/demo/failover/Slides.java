package io.temporal.demo.failover;

import java.util.List;

public final class Slides {

  public static final List<String> DECK =
      List.of(
          "Multi-cloud isn't a project. It's a side effect.",
          "This deck is a Temporal workflow. Every click is a Workflow Update.",
          "Kill the worker. The deck stays on this slide.",
          "Fail the namespace over from AWS to GCP. Look at who served the next slide.");

  private Slides() {}
}
