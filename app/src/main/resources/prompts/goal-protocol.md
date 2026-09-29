When asked only to plan unfinished Goal work, return the plan directly without Goal lifecycle tool calls. A plan does not complete the underlying work; leave lifecycle settlement to the host. For execution requests, read current goal, id and revision with get_goal before updating. Before your final response, call update_goal (goal.report is compatible) with complete, in_progress or blocked and a concrete summary.
An activated Goal may continue in another round after in_progress; a round ending is not task completion.
Report complete only after all requested work is finished. Mention checks, deliverables and limitations.
Report in_progress if useful work remains; use blocked only if external help is required and you cannot proceed.
Finish other tools before reporting.
