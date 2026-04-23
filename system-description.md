## System Description: Distributed Duplicate Request Detector

### Overview

The system is a distributed, self-coordinating cluster of identical Spring Boot application instances. Each instance serves two roles simultaneously: it exposes an HTTP endpoint that receives client requests, and it is a ring node that owns a portion of a distributed keyspace used for duplicate detection. When a request arrives, the receiving instance derives a key, determines which node in the ring owns that key, and queries that node for a prior occurrence. Based on the result, the request is classified as a duplicate or as new.

---

### Request Format

Each inbound request is an **HTTP GET** with two query parameters:

| Parameter | Type | Example |
|-----------|------|---------|
| `session` | Alphanumeric string | `foobar123` |
| `program` | Integer | `614` |

The request path is `/request`.

---

### Key Generation

A deterministic key is derived from the request parameters by concatenating `program`, a pipe character, and `session`:

```
<program>|<session>
```

For example, program `614` and session `foobar123` produce the key `614|foobar123`.

---

### Duplicate Detection Logic

Duplicate detection is a two-phase process involving a **timestamp comparison**, not a simple presence check.

**Phase 1 — Lookup:** The instance that received the HTTP request routes the key to the owning ring node (which may be itself). That node looks up the key in its local cache.

**Phase 2 — Evaluation:**

- **Key not found in cache:** The request is **not a duplicate**. The current timestamp is recorded in the cache under this key, the non-duplicate counter is incremented, and the request is logged.
- **Key found in cache:** The cache returns the timestamp of the previously recorded request. The receiving instance compares that timestamp to the current time:
  - **Difference ≤ 60 seconds →** The request **is a duplicate**. The duplicate counter is incremented. The timestamp in the cache is **not** updated (preserving the original occurrence time).
  - **Difference > 60 seconds →** The request **is not a duplicate**. The cached timestamp is updated to the current time, the non-duplicate counter is incremented, and the request is logged.

---

### Local Cache

Each node maintains a **`ConcurrentHashMap<String, Instant>`** that maps keys to the timestamp of the last recorded (non-duplicate) request.

- **Eviction:** Entries are evicted after a **TTL of 300 seconds** from their last write. This is longer than the 60-second duplicate window, ensuring that entries remain available for comparison even after they've aged out of the duplicate window.
- **Scope:** Each node's cache only contains entries for keys that fall within the keyspace segments assigned to that node (including segments it holds as a replica for adjacent nodes).

---

### Counters & Observability

Counters are tracked as **in-memory metrics using Spring Boot Actuator with Micrometer**. Micrometer is the natural fit here — it ships with Spring Boot, integrates with Actuator's `/actuator/metrics` and `/actuator/prometheus` endpoints out of the box, and supports dimensional tagging if you later want to slice metrics by program, node, etc.

Two counters are maintained per instance:

| Counter Name | Incremented When |
|---|---|
| `requests.duplicate` | A request is determined to be a duplicate (timestamp difference ≤ 60s) |
| `requests.new` | A request is determined to be new (key absent or timestamp difference > 60s) |

These are registered as Micrometer `Counter` objects and are automatically exposed through Actuator endpoints.

---

### Logging

When a request is classified as **not a duplicate**, it is logged via application-level logging (e.g., `logger.info(...)`) including the key, timestamp, and the node that processed it. Duplicate requests are **not** logged beyond the counter increment.

---

### Ring Architecture

Each application instance is simultaneously an HTTP endpoint and a ring node. The cluster of instances forms a Dynamo-style consistent-hash ring that partitions the keyspace. Each node owns the arc of the ring between its predecessor's token (exclusive) and its own token (inclusive), with wrap-around at the top of the keyspace.

**Implemented in this repo (`com.impact.freyja.DynamoRingService`):**

- **Hash function:** 64-bit **FNV-1a** over the UTF-8 bytes of the input. The same function is used both for token assignment and for hashing request keys.
- **Tokens:** **One token per node** (no virtual nodes). Each node's token is `hash("<id>@<host>:<port>")`, making token assignment deterministic from node identity.
- **Primary owner:** For a given key, the primary owner is the node whose token is the smallest token `>=` the key's hash, wrapping around to the lowest-token node if no such node exists.
- **Replication factor:** Configurable via `freyja.ring.replication-factor` (default `3`).
- **Preference list:** Primary owner plus the next `RF − 1` nodes walking clockwise around the ring (with wrap-around). If `RF` exceeds the number of nodes, the preference list is capped at the ring size.

**Still open / TBD:**

- **Synchronous vs asynchronous replication.** The current code computes a preference list but does **not** actually replicate writes to it; only the primary owner holds the entry. Whether replicas should be written sync, async, or at all for the PoC is undecided.
- **Virtual nodes.** Single-token-per-node will produce uneven keyspace distribution. Adding vnodes is deferred.
- **Segment handoff on topology change.** Membership reconciliation swaps the node set but does not migrate cache entries; entries on a departing node are simply lost and lazily re-learned via TTL expiry on the new owner.

---

### Topology Discovery

Each node periodically polls a **hardcoded configuration endpoint** (`freyja.ring.nodes-url`) over HTTP to discover the authoritative ring membership. Behavior is implemented in `RingNodeSyncJob`:

- **Trigger:** Spring `@Scheduled` task running at fixed delay `freyja.ring.sync-interval-ms` (default `30000` ms).
- **Enable flag:** `freyja.ring.sync-enabled` (default `false`); when disabled, membership is managed only via the manual `POST/DELETE /ring/nodes` endpoints.
- **Response schema:** JSON array of node descriptors:
  ```json
  [
    { "id": "n1", "host": "10.0.0.1", "port": 9001 },
    { "id": "n2", "host": "10.0.0.2", "port": 9001 }
  ]
  ```
- **Reconciliation strategy:** Full replace. After each successful fetch, local membership is forced to **exactly match** the remote list — nodes in the response are added (re-adding an existing id refreshes its host/port/token), and any local node not in the response is removed.
- **Failure mode:** Fetch errors (network failure, malformed body, missing URL) are logged at WARN level and the next scheduled tick retries. The previously known membership remains in effect until a successful fetch replaces it.

**Still open / TBD:**

- **Source of the node list.** No component in this repo publishes the node list; it is assumed to be served by an external control plane or static file. Whether instances self-register at startup (e.g., POST themselves to the control plane) or whether the list is curated entirely out-of-band is undecided.
- **Segment handoff coordination.** Topology changes are applied immediately on the next sync tick, with no coordinated handoff of cached entries.

---

### Inter-Node Communication

When a node receives a request whose key belongs to a different node's keyspace, it must forward the lookup to the owning node. Today the project includes the ring math (`DynamoRingService.locate`) needed to identify the owner, but the actual node-to-node lookup call is **not yet implemented**.

**Implied direction (from existing code):** Spring's `RestClient` is already used for topology sync, so REST/HTTP over the same `host:port` carried in `Node` is the natural transport.

**Still open / TBD:**

- Endpoint path, HTTP method, and request/response schema for a remote lookup
- Timeout and retry policy
- Whether the forwarded call returns the cached timestamp (so the receiving instance does the duplicate-vs-new evaluation) or returns a fully classified result
- Whether forwarding hops are bounded (e.g., at most one network hop)

---

### Failure Handling

The only failure path currently handled is the topology sync itself: failed fetches from the config endpoint are logged and retried on the next scheduled interval, with the last-known membership remaining in effect.

**Still open / TBD:**

- Behavior when the owning node is unreachable for a lookup (fall back to the next node in the preference list, or fail the request)
- Fail-open (treat as not-a-duplicate) vs fail-closed (reject the request) semantics
- How a recovering node repopulates its cache (currently it would start cold and rely on the 300-second TTL window to naturally re-learn entries as new requests arrive)

---

### Deployment

The number of instances and deployment topology for the proof of concept are **TBD**. The application is a standard Spring Boot service (`com.impact.freyja.Main`) configured via `src/main/resources/application.properties`; any number of identical instances can be launched provided each is given a unique `id`/`host`/`port` and they all point at the same `freyja.ring.nodes-url`.

---

### Summary of Request Flow

```
Client
  │
  ▼
┌─────────────────────────────┐
│  Receiving Instance (Node)  │
│  1. Parse session & program │
│  2. Derive key: program|session │
│  3. Hash key → ring position│
│  4. Identify owning node    │
└─────────┬───────────────────┘
          │
          ▼
   ┌──────────────┐        ┌──────────────┐
   │ Owning node  │───or──▶│ Self (local) │
   │ (remote call)│        │ (no network) │
   └──────┬───────┘        └──────┬───────┘
          │                       │
          ▼                       ▼
   Cache lookup: key → timestamp?
          │
    ┌─────┴──────┐
    │            │
 Not found    Found
    │            │
    ▼            ▼
 New request   Compare timestamps
 • Store key    │
   + now()     ┌┴─────────────┐
 • Increment   ≤60s          >60s
   new counter  │              │
 • Log request  ▼              ▼
              Duplicate      New request
              • Increment    • Update timestamp
                dup counter  • Increment new counter
                             • Log request
```


---

### Node Failure Simulation

To test failure-handling behavior without removing nodes from the ring, each node carries a mutable **alive** status flag. A node that is marked as "not alive" remains in the ring topology but is treated as unreachable by the key-routing logic, simulating a crash or network partition.

#### Status Flag

The `Node` record is extended (or wrapped) with a boolean `alive` field that defaults to `true`. The `DynamoRingService` maintains this flag per node.

#### REST API

Two new endpoints are added under `/ring/nodes/{id}`:

| Method | Path | Effect |
|--------|------|--------|
| `POST` | `/ring/nodes/{id}/fail` | Sets the node's `alive` flag to `false` |
| `POST` | `/ring/nodes/{id}/recover` | Sets the node's `alive` flag back to `true` |

Both return the updated node representation (including the current `alive` value).

#### Routing Behavior

When `locate()` builds the preference list for a key, it skips any node whose `alive` flag is `false`. If the primary node is down, the next alive node in ring order becomes the effective primary. If **no** alive node exists in the preference list, `locate()` throws an exception (fail-closed).

#### Interaction with Existing Features

- **`snapshot()`** continues to return all nodes regardless of status, but each node's `alive` flag is visible in the response so operators can see the full topology and its health.
- **`reconcileNodes()`** resets incoming nodes to `alive = true` by default; nodes that were previously marked as failed and are still present in the reconciled list regain alive status.
- **`removeNode()`** is unchanged — it physically removes a node from the ring, which is a separate concern from simulated failure.
