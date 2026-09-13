-- Postgres sink schema + metrics views (TASKS T10). Must agree with trace/metrics.py on the same events.
CREATE SCHEMA IF NOT EXISTS sdlc;
CREATE TABLE IF NOT EXISTS sdlc.run (run_id text PRIMARY KEY, scenario text NOT NULL, status text NOT NULL,
  state_json jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS sdlc.artifact (run_id text, name text, version int, produced_by text, content jsonb,
  created_at timestamptz DEFAULT now(), PRIMARY KEY (run_id, name, version));
CREATE TABLE IF NOT EXISTS sdlc.approval (id bigserial PRIMARY KEY, run_id text, node_id text, action text,
  decision text, decided_by text, decided_at timestamptz DEFAULT now());
CREATE TABLE IF NOT EXISTS sdlc.trace_event (id bigserial PRIMARY KEY, run_id text NOT NULL, node_id text, task_id text,
  attempt int NOT NULL DEFAULT 0, ts timestamptz NOT NULL, kind text NOT NULL, status text, actor text,
  tokens_in bigint DEFAULT 0, tokens_out bigint DEFAULT 0, cost_usd numeric(10,5) DEFAULT 0, payload jsonb DEFAULT '{}'::jsonb);
CREATE INDEX IF NOT EXISTS trace_run_ts ON sdlc.trace_event (run_id, ts);

CREATE OR REPLACE VIEW sdlc.v_node_outcomes AS
SELECT run_id, node_id,
       max(attempt) FILTER (WHERE kind='ATTEMPT_STARTED')        AS attempts,
       bool_or(kind='NODE_PASSED')                               AS passed,
       min(ts) FILTER (WHERE kind='ATTEMPT_FAILED')              AS first_failure_at,
       max(ts) FILTER (WHERE kind='NODE_PASSED')                 AS passed_at
FROM sdlc.trace_event WHERE node_id IS NOT NULL
GROUP BY run_id, node_id
HAVING bool_or(kind='ATTEMPT_STARTED');

CREATE OR REPLACE VIEW sdlc.v_run_metrics AS
SELECT r.run_id, r.scenario,
       count(o.node_id)                                                       AS nodes,
       round(avg(CASE WHEN o.passed THEN 1 ELSE 0 END)::numeric, 3)          AS task_success_rate,
       coalesce(sum(greatest(o.attempts-1,0)),0)                               AS retry_count,
       (SELECT count(*) FROM sdlc.trace_event e WHERE e.run_id=r.run_id AND e.kind='ROLLED_BACK') AS rollback_count,
       round(avg(extract(epoch FROM (o.passed_at-o.first_failure_at)))
             FILTER (WHERE o.passed AND o.first_failure_at IS NOT NULL)::numeric,3)             AS mttr_seconds,
       extract(epoch FROM ((SELECT max(ts) FROM sdlc.trace_event e WHERE e.run_id=r.run_id AND e.kind IN ('RUN_COMPLETED','RUN_HALTED'))
                         -(SELECT min(ts) FROM sdlc.trace_event e WHERE e.run_id=r.run_id AND e.kind='RUN_STARTED'))) AS e2e_latency_seconds,
       (SELECT coalesce(sum(cost_usd),0) FROM sdlc.trace_event e WHERE e.run_id=r.run_id)         AS llm_cost_usd,
       (SELECT count(*) FROM sdlc.trace_event e WHERE e.run_id=r.run_id AND e.kind='APPROVAL_REQUESTED') AS human_checkpoints,
       (SELECT count(*) FROM sdlc.trace_event e WHERE e.run_id=r.run_id AND e.kind='REPLAN_TRIGGERED')   AS replans
FROM sdlc.run r LEFT JOIN sdlc.v_node_outcomes o USING (run_id)
GROUP BY r.run_id, r.scenario;

CREATE OR REPLACE VIEW sdlc.v_decision_lineage AS
SELECT run_id, ts, node_id, kind, actor, status, payload FROM sdlc.trace_event
WHERE kind IN ('POLICY_DECISION','APPROVAL_REQUESTED','APPROVAL_GRANTED','APPROVAL_REJECTED','INPUT_REQUESTED',
               'INPUT_RECEIVED','REPLAN_TRIGGERED','NODE_INVALIDATED','FALLBACK_TAKEN','ROLLED_BACK','RUN_HALTED')
ORDER BY run_id, ts;
