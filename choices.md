# Implementation Choices

This document records the design decisions made when filling in the gaps and
TBDs from `system-description.md`. Each choice is paired with the alternative(s)
considered and a brief rationale.

## Node self-identity

**Choice:** Each instance reads its own identity from configuration via three
new properties:

```
freyja.node.id      (defaults to "${spring.application.name}-${server.port}" if unset)
freyja.node.host    (defaults to InetAddress.getLocalHost().getHostAddress())
freyja.node.port    (defaults to ${server.port})
```

On startup the instance self-registers into its own ring via
`DynamoRingService.addNode(...)` so a single-instance deployment works out of
the box. Multi-instance deployments either rely on the topology sync URL or
operators POST nodes to `/ring/nodes` manually.

**Why:** The system description leaves "source of the node list" open and
explicitly notes that self-registration vs. external curation is undecided.
Self-registering into the *local* ring is the minimum needed for the node to
recognize "key X is owned by me" without needing any external service.

## Replication

**Choice:** For the PoC the cache is **primary-only** — only the primary owner
writes. Replication factor still drives preference-list length, which is reused
for failover (see below), but no writes are propagated to replicas.

**Why:** The system description lists synchronous vs. asynchronous replication
as undecided and acceptable for the PoC. Primary-only matches the "Still open
/ TBD" note that "only the primary owner holds the entry."

## Inter-node lookup transport

**Choice:** REST/HTTP using Spring's `RestClient`, mirroring the existing
topology-sync transport.

- **Endpoint:** `POST /internal/classify` on every node.
- **Request body:** `{ "key": "<key>" }`
- **Response body:** `{ "classification": "DUPLICATE" | "NEW", "timestamp": "<ISO-8601>" }`
- **Semantics:** The owning node performs the full lookup-evaluate-update cycle
  (cache read, timestamp comparison, conditional write, counter increment, log).
  It returns the *resulting* classification to the receiving node. The receiving
  node only responds to the client; it does **not** re-evaluate.
- **Hop bound:** The internal endpoint always processes locally and never
  re-forwards, regardless of whether the receiving internal node still believes
  the key belongs to itself. This guarantees at most one network hop.
- **Timeout:** 2 seconds connect + read.
- **Retry:** No retries inside a single request — instead, on failure the
  receiving node walks down the preference list to the next *alive* replica
  and tries that one (see "Failure handling" below).

**Why:** The description explicitly suggests REST for inter-node calls. Having
the owning node do the full evaluation keeps duplicate-detection state, write,
and counter increment co-located, which avoids races between receiving and
owning nodes.

## Counter location

**Choice:** Counters (`requests.duplicate` / `requests.new`) are incremented on
the node that performs the classification — i.e., the owning node (or the
fallback replica). The receiving node only increments counters when it ends up
processing the request locally.

**Why:** Counters then directly reflect the work each node did, and avoid
double-counting between forwarder and processor.

## Failure handling — owner unreachable

**Choice:** Walk the preference list. The receiving node tries `primary` first,
then each subsequent *alive* node in the preference list, until one succeeds or
the list is exhausted. If every preference-list node fails, the request fails
with HTTP 503 (fail-closed).

**Why:** Fail-closed is safer than fail-open for a duplicate detector — better
to reject and retry than to under-report duplicates. The preference list is
already computed for replication and is the natural ordered fallback set.

## Failure handling — node marked "not alive"

**Choice:** `DynamoRingService.locate()` filters dead nodes out of the
preference list entirely (the spec's prescribed behavior). If the resulting
list is empty, `locate()` throws (fail-closed).

## Cache eviction

**Choice:** A single Spring `@Scheduled` sweeper task running every 30 seconds
removes entries whose timestamp is older than 300 seconds. Eviction is driven
by the wall clock, not by reads.

**Why:** A separate sweeper is simpler than wrapping `ConcurrentHashMap` in
something fancier, and 30s granularity is more than sufficient given the
60s/300s windows.

## Self-routing optimization

**Choice:** When the receiving node *is* the owning node, it bypasses HTTP and
calls the local classify path directly. This avoids loopback HTTP for the
common case where requests happen to land on the right node.

## Internal endpoint security

**Choice:** None for the PoC — the `/internal/*` endpoints are open. In
production this would be locked down (mTLS, internal-only network, etc.).
