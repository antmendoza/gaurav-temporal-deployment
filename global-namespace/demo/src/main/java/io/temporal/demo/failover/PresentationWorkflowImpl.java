package io.temporal.demo.failover;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

public class PresentationWorkflowImpl implements PresentationWorkflow {

    private final SlideActivities activities =
            Workflow.newActivityStub(
                    SlideActivities.class,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    private Logger logger = Workflow.getLogger(PresentationWorkflowImpl.class);


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
        int slide = Math.min(index + 1, Slides.DECK.size() - 1);
        return show(slide);
    }

    @Override
    public Slide previous() {
        int slide = Math.max(index - 1, 0);
        return show(slide);
    }

    @Override
    public Slide current() {
        return current;
    }

    private Slide show(int target) {
        index = target;
        logger.info("Showing slide {}/{}", target+1, Slides.DECK.size());
        current =
                new Slide(target + 1, Slides.DECK.size(), Slides.DECK.get(target), activities.whereAmI());
        return current;
    }
}
