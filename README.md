# Freyja - Crude Dynamo Ring Prototype

This project now includes a minimal in-memory Dynamo-style consistent hashing ring with single-token-per-node membership.

## What is implemented

- Add/remove/list ring nodes in-memory
- Deterministic token generation per node (`id@host:port`)
- Key lookup to primary owner with ring wrap-around
- Preference list generation using configurable replication factor
- HTTP endpoints for quick manual testing

## Endpoints

- `POST /ring/nodes` - add node
- `DELETE /ring/nodes/{id}` - remove node
- `GET /ring` - list ring nodes (sorted by token)
- `GET /ring/locate?key=...` - resolve key to primary + preference list

### Example request

`POST /ring/nodes`

```json
{
  "id": "node-a",
  "host": "127.0.0.1",
  "port": 9001
}
```

## Configuration

`src/main/resources/application.properties`

```properties
freyja.ring.replication-factor=3
```

## Notes / limitations

- Single process and in-memory only (no gossip, no persistence)
- Single token per node (no virtual nodes yet)
- No hinted handoff, quorum (`R/W`), or failure detector yet

