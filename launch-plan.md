# Launch Plan: Distributed Session Tracking

## Situation Report

After the hackathon we have a standalone proof of concept.  Known shortcomings include lack of load testing,
and questions around dynamic behavior during topology changes.  The production requirement will have to handle
1 billion requests per day, today.  Expected growth will double that in a year or two.


## Refinement of Concept

We believe the approach is sound.  The "ring" with consistent hashing is a well known pattern for distributed
load balancing and session management.  We can iterate our way to a better implementation over time.  Even the
basic approach we have demonstrated, will be an improvement over what Impact offers today.


## Handoff to Product Team

The hackathon team already has two members from the tracking team, who can bring the concept to the tracking squad.


## Risks

There is a risk that our feature adds undue complexity to the production system.  However, we already have
problems with the "sticky session" load balancer feature, which we would rather replace.  By moving the logic
into our own application, we can control and optimize behavior much easier than we can at the infrastructure level.
Overall, we consider our approach to be a net win.
