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
`http://<any-node-host>:<port>/` in a browser. The page (Alpine.js, no build
step) polls a single endpoint on the serving node, `GET /cluster/status`,
which returns a snapshot maintained by `ClusterStatusAggregator`. That
aggregator runs on a fixed-delay schedule
(`freyja.cluster-status.poll-interval-ms`, default 2s) and fans out
server-to-server to every peer's
`/actuator/metrics/requests.new` and `/actuator/metrics/requests.duplicate`.
This means the browser only needs to reach the node that served the page —
peer hosts don't need to be reachable from your laptop.

## Local demo

A three-node walk-through that exercises the ring, the UI, and the load
generator — all on `localhost`.

### 1. Run a small "cluster" of nodes

Open three terminals and start one node in each, on different ports:

```bash
# terminal 1
./gradlew bootRun --args='--server.port=9001 --freyja.node.host=127.0.0.1'

# terminal 2
./gradlew bootRun --args='--server.port=9002 --freyja.node.host=127.0.0.1'

# terminal 3
./gradlew bootRun --args='--server.port=9003 --freyja.node.host=127.0.0.1'
```

Each node self-registers itself, but they don't yet know about each other.
Tell every node about the other two by POSTing to `/ring/nodes`:

```bash
for me in 9001 9002 9003; do
  for peer in 9001 9002 9003; do
    [ "$me" = "$peer" ] && continue
    curl -s -X POST "http://127.0.0.1:$me/ring/nodes" \
      -H 'Content-Type: application/json' \
      -d "{\"id\":\"freyja-$peer\",\"host\":\"127.0.0.1\",\"port\":$peer}" \
      >/dev/null
  done
done

# sanity check: every node should now list all three
curl -s http://127.0.0.1:9001/ring | jq
```

### 2. Open the UI

Browse to any node:

```
http://127.0.0.1:9001/
```

You should see all three nodes listed as `alive`. Counters are zero. The
page is identical on `:9002/` and `:9003/` — each node aggregates the
cluster view independently from its own perspective.

### 3. Generate traffic and watch the UI

In a fourth terminal, run the load generator against all three nodes:

```bash
./gradlew :tools:load-gen:run -Pargs="\
  --targets http://127.0.0.1:9001,http://127.0.0.1:9002,http://127.0.0.1:9003 \
  --rate 50 \
  --duration 1m \
  --duplicate-ratio 0.25"
```

Within a couple of seconds the UI's NEW and DUPLICATE counters should
start climbing on every node, and the cluster totals should track the
generator's `sent` count. See
[how-to-run-simulated-traffic.md](./how-to-run-simulated-traffic.md) for
all flags.

Try simulating a node failure mid-run:

```bash
curl -X POST http://127.0.0.1:9001/ring/nodes/freyja-9002/fail
# ...later
curl -X POST http://127.0.0.1:9001/ring/nodes/freyja-9002/recover
```

The failed node turns red in the UI; routing skips it until you recover it.

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
