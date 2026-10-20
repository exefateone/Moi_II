# Stage C1 — backend provider abuse guard (17 October 2026)

## Added

- Added a thread-safe 60-second sliding-window quota to the authenticated provider routes: `/api/agent/plan`, `/api/chat` and `/api/agent/embeddings`. The default limit is 20 requests per route per minute for the configured bearer token. `SPIDER_PROVIDER_RATE_LIMIT_PER_MINUTE` accepts 1–600; malformed or out-of-range values fail closed with HTTP 503 rather than silently disabling the guard.
- The limiter hashes the configured client token for bucket identity and does not retain the raw credential. Old windows are pruned. Rate-limited responses are HTTP 429 with a `Retry-After` header.
- Added a process-local cap of four simultaneous upstream provider requests. Overload is rejected immediately with HTTP 429 and `Retry-After: 2`, instead of creating an unbounded queue of billable work.
- Added tests for rate cap enforcement before network calls, rolling-window expiration, invalid configuration, concurrent quota contention and concurrency rejection.

## Limitations

The limiter state and semaphore are process-local. They reset after restart and are not synchronized across multiple server workers, containers or replicas. `SPIDER_API_TOKEN` is currently a single shared credential, so this is a per-token/per-route cap rather than true per-user billing/quota. A public production deployment still needs HTTPS, an API gateway/reverse proxy, distributed per-user/IP limits, spend quotas, monitoring and abuse response.

## Verification

- `python -m unittest discover -s tests -p 'test_backend_agent_plan.py' -v`: 30 tests pass.
- `python -m unittest discover -s tests -p 'test_*.py' -v`: 93/93 Python tests pass in one run.
- `python -m py_compile backend/app/main.py scripts/*.py tests/*.py`: passes.
- JVM Agent Core smoke: 13 scenarios pass; Media URL policy smoke: 15 assertions pass. Kotlin source checks use signature stubs, not Android SDK compilation.
- HTML and Chromium UI smoke checks pass; see `docs/PROVIDER_GUARD_VALIDATION_2026-10-17.md` for full scope and limits.
- No live provider call, Android SDK build, native NDK build or APK assembly was performed.
