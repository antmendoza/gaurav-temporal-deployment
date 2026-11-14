# Failover demo: the slide deck is a workflow

The presentation itself runs on Temporal. A `PresentationWorkflow` holds the current slide, and
the browser UI moves through the deck with two **Workflow Updates**, `next` and `previous`. Each
update runs an activity that records **which cloud and pod** served the slide. Fail the
`replicated` namespace over mid-talk and the next click is served by the other cloud, with no
change to the workers or the UI.

```
PresentationWorkflow.run()          started live on stage, waits until terminated
  update next()     ->  index + 1, whereAmI()  ->  Slide(2/4, "…", "gcp (failover-worker-…)")
  update previous() ->  index - 1, whereAmI()
  query  current()
```

| Piece | What it is |
|---|---|
| `src/…/PresentationWorkflow*.java` | Workflow: `next` / `previous` updates, `current` query |
| `src/…/Slides.java` | The deck text (edit the slides here) |
| `src/…/SlideActivities*.java` | Activity that reports `CLOUD` and hostname |
| `src/…/WorkerMain.java` | Worker; configured by env vars (below) |
| `src/…/UiMain.java`, `src/main/resources/index.html` | Browser UI: the slide, Previous / Next buttons (arrow keys work too), and the Temporal UI for the workflow in an iframe (`u` hides it) |
| `Dockerfile` | Multi-stage Maven build → JRE 21 image |
| `k8s/` | Deploy to the EKS and GKE clusters |
| `local/` | Two replicated clusters in Docker Compose |

Worker env vars: `TEMPORAL_ADDRESS` (default `localhost:7233`), `TEMPORAL_NAMESPACE`
(`replicated`), `TASK_QUEUE` (`replicated-demo`), `CLOUD` (`local`). The UI also reads
`WORKFLOW_ID` (`presentation`), `PORT` (`8080`) and `TEMPORAL_UI` (`http://localhost:8233`).

The Temporal UI sends `X-Frame-Options: SAMEORIGIN`, so the deck can't iframe it from another
port. `UiMain` proxies `/temporal/*` to `TEMPORAL_UI`, which puts both on one origin. For that to
work, the Temporal UI must be served under `/temporal` (`--ui-public-path /temporal` for
`start-dev`). The iframe shows the workflow page and updates live as the clicks land.

## Ways to run it

- **EKS + GKE, with failover:** [k8s/README.md](k8s/README.md)
- **Two replicated clusters in Docker Compose, with failover:** [local/README.md](local/README.md)
- **One local dev server, no failover:** below

## Run locally on one cluster

### Build
```sh
cd global-namespace/demo
mvn package
```

### Run the Temporal Server
```sh
temporal server start-dev --namespace replicated --ui-public-path /temporal
```

### (new terminal) Run the worker
```sh
CLOUD=local java -jar target/failover-worker.jar
```

### (new terminal) Start the workflow with the presentation
```sh
temporal workflow start --namespace replicated --task-queue replicated-demo \
  --type PresentationWorkflow --workflow-id presentation
```

### (new terminal) Start the UI / start the presentation
```sh
java -cp target/failover-worker.jar io.temporal.demo.failover.UiMain

open http://localhost:8080
```

### Clean up
```sh
temporal workflow terminate --namespace replicated --workflow-id presentation
```
