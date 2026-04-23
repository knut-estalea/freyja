# Bet One-Pager: Distributed Session Tracking

## Problem

Singular tracking servers become overloaded due to the sticky-session model used for click deduplication. Some clients effectively behaving as one "big user" and all their traffic is sent to one tracking server. This concentrated load is difficult to split up, making horizontal scaling difficult and blocking the development of new features that depend on a more evenly distributed architecture.

## Who Benefits and How

The **Tracking Team** is the primary beneficiary:

- **Easier scaling & maintenance** — Distributing clicks across a ring of nodes removes the single-server bottleneck, letting the team scale capacity by adding nodes.
- **New feature enablement** — A balanced, horizontally scalable tracking layer unblocks features that were previously infeasible under the sticky-session constraint.
- **Increased reliability** — Eliminating hot-spot servers reduces the blast radius of any single node failure and improves overall system resilience.

## Value Hypothesis

> If we replace the sticky-session routing model with a consistent-hashing ring, then the tracking infrastructure will distribute load more evenly, enabling simpler scaling, higher reliability, and the ability to ship new client-facing features.

**Value Bucket:** *Project Backlog* — Tackle real tickets and known pain points from the existing backlog. These are problems the business already knows about, and today we get the space to solve them.

## Riskiest Assumptions

1. **Production latency & memory impact is unknown.** The current proof of concept runs against mocks, not real production workloads. Actual latency or memory pressure could be higher than anticipated.
2. **Mock fidelity gap.** The POC uses mocked servers and services; a key behavioral detail of the real infrastructure may be unaccounted for.
3. **Key-space evolution.** We are unsure how the hash key space will evolve after initial ring creation — rebalancing costs and data-migration overhead are not yet validated.

## Validation Plan

**Spike (Tracking Team):** Integrate the proof of concept into the real tracking code path and deploy to a staging environment. The spike should answer:

- Does the consistent-hashing ring introduce meaningful latency or memory overhead under realistic load?
- Do any behavioral differences surface when running against real services instead of mocks?
- How does the key space distribute and evolve after initial ring creation?

The spike is validated when we have staging-level evidence that confirms or refutes the assumptions above.

