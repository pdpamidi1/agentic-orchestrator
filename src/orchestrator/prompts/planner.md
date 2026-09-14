Run: {run_id}. Target stack: {target_stack}.

Produce a Plan: an acyclic task DAG. Each TaskSpec must have: depends_on, an optional parallel_group for tasks that
can run concurrently, impact_level (HIGH for anything touching migrations, dependencies, config, infra, or a breaking
API change), allowed_files (concrete globs), contract_slice (operationIds), data_model_slice, class_structure (FQCNs),
acceptance_criteria_ids, definition_of_done, risk_notes, rollback_note. Prefer 5–9 tasks. Explain the rationale.
allowed_files must stay inside the allowed or protected paths below (a task touching a protected path is HIGH);
never plan files the orchestrator provisions, and make sure the tasks together satisfy every gate listed.

## Repository conventions (policy; not negotiable)
{conventions}

## Spec
{spec}

## Impact analysis (brownfield; may be none)
{impact}

## Diagnosis from a failed validation (re-plan; may be none)
{diagnosis}

## Feedback
{feedback}
