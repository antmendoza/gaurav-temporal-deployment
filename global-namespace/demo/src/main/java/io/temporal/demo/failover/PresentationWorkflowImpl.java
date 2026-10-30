package io.temporal.demo.failover;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class PresentationWorkflowImpl implements PresentationWorkflow {

  private final SlideActivities activities =
      Workflow.newActivityStub(
          SlideActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

  private int index = 0;
  private Slide current;

  @Override
  public void run() {
    current = show(0);
    // The talk ends when we terminate the workflow.
    Workflow.await(() -> false);
  }

  @Override
  public Slide next() {
    return show(Math.min(index + 1, Slides.DECK.size() - 1));
  }

  @Override
  public Slide previous() {
    return show(Math.max(index - 1, 0));
  }

  @Override
  public Slide current() {
    return current;
  }

  private Slide show(int target) {
    index = target;
    current =
        new Slide(target + 1, Slides.DECK.size(), Slides.DECK.get(target), activities.whereAmI());
    return current;
  }
}
