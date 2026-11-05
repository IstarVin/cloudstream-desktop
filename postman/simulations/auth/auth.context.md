# Authentication HTTP simulation

Created 2026-11-05 from the local authentication contract; no external mock owner search was needed because this Worker is implemented in this repository.

## Purpose

Runs the server's real WorkerClient against the stand-in in `postman/mocks/auth-worker/default.js`. Worker behavior itself is tested in Cloudflare's runtime under `auth-worker/test`.

## Execution

Start `postman simulation run postman/simulations/auth/auth.sim.yaml --port 4900 --output ndjson`, then run `AUTH_TEST_WORKER_URL=http://127.0.0.1:4900/worker ./gradlew :server:test --tests desktop.AuthTest`. Stop the simulation with Ctrl-C. The opt-in test is skipped without that environment variable. Keep request logs outside the repository.

## Assumptions

- The simulation uses HTTP on loopback; WorkerClient explicitly permits this only through a constructor option used by tests. Production environment configuration requires HTTPS. (Observed 2026-11-05.)
- This stand-in models login success and selected failures, not approval storage or Cloudflare deployment. The committed handler owns its synthetic credentials. (Observed 2026-11-05.)

## Observations

<!-- begin:observations -->
- 2026-11-05: The `worker` member served real HTTP client calls for successful login, incorrect password, JSON 503, and HTML 502. No member stayed silent. An unreachable loopback port also produced 503. All server AuthTest checks passed.
<!-- end:observations -->

## What didn't work

- 2026-11-05: The first mock read an already-consumed request stream and returned 400. With `interceptRequests` enabled, the body is in `req.body`; the handler now uses it. The passing run confirmed this fix.
