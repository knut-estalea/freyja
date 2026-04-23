# Freyja load generator

A small standalone CLI that pumps simulated traffic at a Freyja cluster, with a
configurable mix of unique and duplicate requests.

It's a separate Gradle subproject (no Spring) and shares nothing with the main
app's classpath.

## Build

```
./gradlew :tools:load-gen:installDist
```

The runnable script will be at `tools/load-gen/build/install/load-gen/bin/load-gen`.

## Run

Quick run via Gradle:

```
./gradlew :tools:load-gen:run -Pargs="--targets http://localhost:9001,http://localhost:9002 --rate 50 --duration 30s"
```

Or via the installed script:

```
./tools/load-gen/build/install/load-gen/bin/load-gen \
  --targets http://localhost:9001,http://localhost:9002 \
  --rate 50 \
  --duration 5m \
  --duplicate-ratio 0.25 \
  --pool-size 100 \
  --zipf-exponent 1.2 \
  --window-seconds 50
```

## How duplicates are generated

- A pool of `--pool-size` unique `(program, session)` keys is pre-generated up
  front (deterministic via `--seed`).
- Each request rolls a uniform random number. With probability
  `--duplicate-ratio` it replays a key that was sent within the last
  `--window-seconds` (default 50s, kept under the system's 60s detection
  window). Otherwise it samples a "fresh" key from the pool using a Zipfian
  distribution so a few keys are hot.
- Until the window has warmed up, would-be replays fall back to a fresh sample;
  this is reported in the run summary as `attempted-duplicates`.

## Self-validation

With `--validate` (on by default), the tool snapshots
`/actuator/metrics/requests.duplicate` and `requests.new` on each target before
and after the run, sums the deltas across the cluster, and compares the
observed duplicate ratio to the configured target. Exits non-zero if the
deviation exceeds `--validate-tolerance` (default 0.10).

The observed ratio will typically run slightly above the configured value
because Zipfian "fresh" picks sometimes naturally collide with a hot key
already inside the 60s window.

## Cluster targeting

Requests are dispatched to a randomly chosen target from `--targets` so that
both local-owner and forwarded-lookup paths in the ring are exercised.
