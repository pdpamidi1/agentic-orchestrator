Run: {run_id}. Target stack: {target_stack}.

Validation failed. Diagnose the root cause and choose exactly one decision:
- retry  : the implementation can be fixed in place; put precise, file-level instructions in feedback
- replan : the plan or design is wrong (missing task, wrong decomposition, contract mismatch); feedback goes to the planner
- halt   : unrecoverable within policy (needs a human decision, infra, or a protected change)

## Validation result
{validation_result}

## Review (advisory)
{review}

## Plan
{plan}

## Changeset
{changeset}

## Feedback
{feedback}
