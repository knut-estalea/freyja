# Freyja - Crude Dynamo Ring Prototype

![logo](./Ynglingesaga.jpg)

This project demonstrates a minimal in-memory Dynamo-style consistent hashing ring with single-token-per-node membership.  Similar to BigTable, DynamoDB, and Cassandra, for distribution.  We don't persist any data to disk.
The idea is the tracking server instances in a region form a group where they have a shared memory of user sessions.


## What is implemented

- Add/remove/list ring nodes in-memory
- Deterministic token generation per node (`id@host:port`)
- Key lookup to primary owner with ring wrap-around
- Preference list generation using configurable replication factor
- Optional periodic node sync from a remote URL
- HTTP endpoints for quick manual testing
- **Duplicate-request detection** with a 60s sliding duplicate window and a 300s cache TTL
- **Cluster routing**: `/request` derives a key, locates the owner via the ring, and forwards over HTTP if remote
- **Failure simulation**: each node carries an `alive` flag; dead nodes are skipped during routing
- **Metrics**: `requests.duplicate` and `requests.new` counters via Micrometer / Spring Boot Actuator

## Endpoints

### Public

- `GET /request?session=<alphanumeric>&program=<int>` — classify a request as `NEW` or `DUPLICATE`
- `GET /actuator/metrics/requests.duplicate` (and `requests.new`) — counter values

### Ring management

- `POST /ring/nodes` — add node
- `DELETE /ring/nodes/{id}` — remove node
- `POST /ring/nodes/{id}/fail` — simulate node failure (sets `alive=false`)
- `POST /ring/nodes/{id}/recover` — recover a failed node (sets `alive=true`)
- `GET /ring` — list ring nodes (sorted by token)
- `GET /ring/locate?key=...` — resolve key to primary + preference list

### Internal (node-to-node)

- `POST /internal/classify` — body `{"key": "..."}`; processes locally without re-forwarding

### Example request

`POST /ring/nodes`

```json
{
  "id": "node-a",
  "host": "127.0.0.1",
  "port": 9001
}
```

## Cluster status UI

Every node serves a small status page at its root URL — open
`http://<any-node-host>:<port>/` in a browser. The page (Alpine.js,
no build step) calls `/ring` on the node you opened to discover the
cluster, then fans out from your browser to each node's
`/actuator/metrics/requests.new` and `/actuator/metrics/requests.duplicate`
to show per-node and cluster-wide request and duplicate counts. It
auto-refreshes every couple of seconds.

## Configuration

`src/main/resources/application.properties`

```properties
freyja.ring.replication-factor=3
freyja.ring.sync-enabled=false
freyja.ring.nodes-url=
freyja.ring.sync-interval-ms=30000

# Self-identity (optional overrides; sensible defaults derived from server.port)
#freyja.node.id=
#freyja.node.host=
#freyja.node.port=
freyja.node.self-register=true

# Cache eviction sweep cadence; the 300s entry TTL itself is fixed by the spec.
freyja.cache.sweep-interval-ms=30000
```

When sync is enabled, the app periodically GETs `freyja.ring.nodes-url` and reconciles local membership to exactly match the remote list.

Expected remote payload:

```json
[
  { "id": "n1", "host": "10.0.0.1", "port": 9001 },
  { "id": "n2", "host": "10.0.0.2", "port": 9001 }
]
```

## Notes / limitations

- Single process and in-memory only (no gossip, no persistence)
- Single token per node (no virtual nodes yet)
- No hinted handoff, quorum (`R/W`), or failure detector yet
- Sync fetch failures are logged and retried at next interval
