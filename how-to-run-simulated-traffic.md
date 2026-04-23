# How to run simulated traffic against Freyja

This repo includes a small CLI tool that pumps simulated HTTP traffic at a
running Freyja cluster, with a configurable mix of unique and duplicate
requests. It lives in its own Gradle subproject at `tools/load-gen/` and
shares no classpath with the main Spring Boot app.

## What it does

- Pre-generates a fixed pool of `(program, session)` keys.
- Sends `GET /request?session=...&program=...` at a steady, configurable rate
  for a fixed duration.
- For each request, rolls a die: with probability `--duplicate-ratio` it
  **replays a key sent within the last `--window-seconds`** (kept under the
  system's 60s duplicate-detection window so the cluster will actually count
  it as a duplicate); otherwise it samples a "fresh" key from the pool using a
  **Zipfian distribution** so a few keys are hot.
- Distributes requests across all `--targets` at random so both local-owner
  and forwarded-lookup paths in the ring are exercised.
- Optionally **self-validates** by scraping
  `/actuator/metrics/requests.duplicate` and `requests.new` from every target
  before and after the run, summing deltas, and comparing the observed
  duplicate ratio to the configured target.

A few key behaviors worth knowing:

- Until the replay window has warmed up, would-be replays fall back to a fresh
  sample. The number of attempted replays is reported in the run summary.
- The observed duplicate ratio at the cluster will typically run **slightly
  above** the configured value, because Zipfian "fresh" picks sometimes
  naturally collide with a hot key already inside the 60s window. This is
  realistic noise, not a bug.
- Sent keys are recorded back into the replay window only on a 2xx response.

## Build

From the repo root:

```bash
./gradlew :tools:load-gen:installDist
```

This produces a runnable script at
`tools/load-gen/build/install/load-gen/bin/load-gen`.

## Run

The simplest way is via Gradle, which uses the project's Java 21 toolchain
automatically (regardless of what `java` is on your `PATH`):

```bash
./gradlew :tools:load-gen:run -Pargs="\
  --targets http://localhost:9001,http://localhost:9002 \
  --rate 50 \
  --duration 30s"
```

Or directly via the installed script (requires Java 21 on `PATH` or
`JAVA_HOME`):

```bash
./tools/load-gen/build/install/load-gen/bin/load-gen \
  --targets http://localhost:9001,http://localhost:9002 \
  --rate 50 \
  --duration 5m \
  --duplicate-ratio 0.25 \
  --pool-size 100 \
  --zipf-exponent 1.2 \
  --window-seconds 50
```

Use `--help` to see all flags.

## Flags

| Flag | Default | Description |
|---|---|---|
| `--targets` | _(required)_ | Comma-separated list of instance base URLs, e.g. `http://localhost:9001,http://localhost:9002`. Requests are distributed at random across this list. |
| `--rate` | `50` | Target request rate, in requests per second. |
| `--duration` | `1m` | Run duration. Accepts shorthand like `500ms`, `30s`, `5m`, `1h`, or any ISO-8601 duration. |
| `--duplicate-ratio` | `0.25` | Fraction of requests that should be replays of recent keys. |
| `--pool-size` | `100` | Number of distinct `(program, session)` keys pre-generated at startup. |
| `--zipf-exponent` | `1.2` | Zipf exponent for non-replay key selection. Higher = more skewed (a few hotter keys). |
| `--window-seconds` | `50` | Maximum age of keys eligible for replay. Keep this under 60 so replays land inside the cluster's duplicate-detection window. |
| `--window-max-size` | `10000` | Hard cap on the number of entries kept in the replay window. |
| `--seed` | `42` | Random seed for reproducible key generation. |
| `--validate` / `--no-validate` | `--validate` | Snapshot Actuator counters before and after, then print observed-vs-expected duplicate ratio. |
| `--validate-tolerance` | `0.10` | Acceptable absolute difference between observed and expected duplicate ratio. The tool exits non-zero if the deviation exceeds this. |

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Run completed; if validation was enabled, the observed ratio was within tolerance. |
| `2` | Run completed but the observed duplicate ratio fell outside `--validate-tolerance`. |
| Other | Picocli argument errors or unhandled exceptions. |

## Prerequisites on the cluster

The tool reads counters from each instance's
`/actuator/metrics/requests.duplicate` and `/actuator/metrics/requests.new`
endpoints. The default `application.properties` already exposes the `metrics`
Actuator endpoint, so no additional configuration is required for validation
to work.

## Example session

```bash
$ ./gradlew :tools:load-gen:run -Pargs="\
    --targets http://localhost:9001,http://localhost:9002,http://localhost:9003 \
    --rate 100 --duration 1m"

Targets: [http://localhost:9001, http://localhost:9002, http://localhost:9003]
Rate: 100.0 req/s   Duration: PT1M   Duplicate ratio: 0.250
Key pool: size=100  zipf=1.20  seed=42
Replay window: 50s (max 10000 entries)

Pre-run counters:
  http://localhost:9001 -> dup=0 new=0
  http://localhost:9002 -> dup=0 new=0
  http://localhost:9003 -> dup=0 new=0

Starting traffic...
Done. sent=6000  attempted-duplicates=1487  errors=0  non-2xx=0  effective-rate=99.98 req/s

Post-run counters:
  http://localhost:9001 -> dup=512 new=1488
  http://localhost:9002 -> dup=498 new=1502
  http://localhost:9003 -> dup=521 new=1479

=== Validation ===
Classified by cluster: 6000  (duplicate=1531, new=4469)
Sent by generator:     6000  (expected duplicates ~1500)
Observed duplicate ratio: 0.2552   (target 0.2500, tolerance ±0.10)
OK: observed ratio within tolerance.
```
