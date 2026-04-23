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

Each application instance is simultaneously an HTTP endpoint and a ring node. The cluster of instances forms a ring that partitions the keyspace. Each node owns one or more **segments (spaces)** of the ring and can hand off segments during topology changes. Replication is performed across adjacent nodes so that more than one node holds data for a given key range.

The following details are **TBD**:

- **Hash function** used to map keys onto the ring (e.g., murmur3, SHA-256)
- **Number of virtual nodes / segments per instance**
- **Replication factor** (how many adjacent nodes hold replicas)
- **Whether replication is synchronous or asynchronous**

---

### Topology Discovery

Each node periodically polls a **hardcoded configuration endpoint** to discover or update its understanding of the ring topology. The details of this mechanism are **TBD**:

- Schema/format of the configuration response
- Polling interval
- How changes in topology trigger segment handoff

---

### Inter-Node Communication

When a node receives a request whose key belongs to a different node's keyspace, it must forward the lookup to the owning node. The protocol and format for this communication are **TBD**:

- Protocol (REST/HTTP, gRPC, etc.)
- Request/response schema
- Timeout and retry behavior

---

### Failure Handling

Behavior when a ring node is unreachable is **TBD**:

- Whether the request falls back to a replica node
- Whether requests fail open (treat as non-duplicate) or fail closed (reject)
- How a recovering node repopulates its cache

---

### Deployment

The number of instances and deployment topology for the proof of concept are **TBD**.

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