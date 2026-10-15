# Personal base context

Generic starting point for working with Claude Code from anywhere (web/mobile), no local machine needed.
Copy this file into new projects or fork the repo as a template.

## Who I am
Backend developer: scalable, secure, performant server-side systems. APIs, databases, distributed systems, cloud infra.

## Default stack
- Node.js/TypeScript (Express, NestJS), Python (FastAPI, Flask)
- PostgreSQL, Redis
- Docker, Kubernetes (EKS), AWS (EC2, RDS, S3, Lambda, SQS/SNS), Kafka/RabbitMQ
- Tests: Jest + Supertest (Node), pytest + httpx (Python)

## How to work with me
- Clarify ambiguity before coding; look for existing patterns in the repo first.
- Start with the simplest working version, then iterate. Test as you go.
- Keep changes focused; don't refactor unrelated code.
- Say plainly what was verified and what was not. If tests fail, show the output.
- Ask before anything destructive or outward-facing (force push, deleting data, deploys).

## Conventions
- Types everywhere (TypeScript / Python type hints).
- REST: plural nouns, correct status codes, `{data, meta}` / `{error:{code,message,details}}` envelopes.
- DB: snake_case, plural tables, `id/created_at/updated_at`, migrations only (up + down), index FKs.
- Parameterized queries only; validate all input; never log secrets/PII; secrets via env/Secrets Manager.
- Structured JSON logging with request/correlation IDs.
- Coverage target: 80% overall, 90% on critical logic.
- Commits: Conventional Commits (`feat:`, `fix:`, `perf:`, `docs:`, `test:`, `refactor:`).
- PRs: clear title, context + approach + testing notes, CI green.

## Remote-session notes
- No local machine: run installs/tests inside the session sandbox; commit and push often so work isn't lost.
- Work on a feature branch, never directly on `main`.
- Put scratch files outside the repo or in `scratch/` (gitignored).
