# AccessWindow

An access review ledger for small teams. Every collaborator permission gets a **human owner**, a **business reason** and a **review date**. AccessWindow shows which access is overdue or due soon, lets the owner **revoke** or **renew** it, and keeps a trail of who decided what, when and why.

AccessWindow is a ledger, not an identity provider. It does not change permissions in GitHub, AWS or other systems. An authorized operator still applies each decision in the source system.

## Review rules

"Today" is the calendar date in the workspace time zone (`APP_TIME_ZONE`). `days_left` is the number of calendar days from today to the review date, so DST changes, month ends and leap years never shift the count.

| Status | API value | Rule | UI badge |
| --- | --- | --- | --- |
| On track | `on_track` | active and `days_left > 14` | ACTIVE |
| Due soon | `due_soon` | active and `0 ≤ days_left ≤ 14` (a review date of today is still due soon) | REVIEW SOON |
| Overdue | `overdue` | active and `days_left < 0` | PAST DUE |
| Revoked | `revoked` | revoked, whatever the date | REVOKED |

The **review queue** (`/api/review-due` and the CSV export) holds the active records that are overdue or due soon. The 14-day window is configurable (`REVIEW_DUE_SOON_DAYS`).

Date rules:

- A new grant can have a review date from today up to `REVIEW_MAX_HORIZON_DAYS` ahead (365 by default).
- A renewal must move the date to after today, within the same limit.

## Revoke and renew

1. The owner opens a record (`···`), enters their name and a decision note.
2. **Revoke** marks the record revoked. Revoking a record that is already revoked returns `409 Conflict`.
3. **Renew** sets a new review date and makes the record active again. This also restores a revoked record that the owner approves again.
4. Every decision adds an event (`created`, `revoked`, `renewed`) with the actor, the note, the previous and new review dates, and a timestamp. Events are never changed or deleted.

Records carry a version number. If two people change the same record at the same time, the second request gets `409` and has to reload.

## API

All errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details (`application/problem+json`). For compatibility with the original API, each problem also has an `error` member that repeats `detail`. Validation problems list the failing fields in `errors: [{field, message}]`.

| Method & path | Description |
| --- | --- |
| `GET /api/access` | Register, active first, then by review date. Fields are snake_case: `id, email, resource, role, owner, reason, expires_at, status (active/revoked), review_status, days_left, created_at, updated_at` |
| `GET /api/access/{id}` | One record |
| `POST /api/access` | Create `{email, resource, role, owner, reason, expiresAt, actor?}` → `201` + `Location` |
| `PATCH /api/access/{id}` | `{action: "revoke", actor?, note?}` or `{action: "renew", expiresAt, actor?, note?}` → `{ok: true, access}` |
| `GET /api/access/{id}/events` | Decision trail, oldest first |
| `GET /api/review-due` | Review queue (camelCase: `expiresAt, daysLeft, reviewStatus`) |
| `GET /api/review-due.csv` | Same queue as an RFC 4180 CSV download |
| `GET /api/summary` | `{total, expiring, overdue, resources}` for active access |
| `GET /api/policy` | `{today, timeZone, dueSoonDays, maxReviewDate}` |
| `GET /api/health` | `{"status":"UP"}` or `503`. Includes the database check |
| `GET /actuator/health/{liveness,readiness}` | Probes for orchestrators |

Problem types: `/problems/validation`, `/problems/malformed-body`, `/problems/not-found`, `/problems/already-revoked`, `/problems/concurrent-update`, `/problems/rate-limited`, `/problems/payload-too-large`, `/problems/internal`.

Example:

```bash
curl -X PATCH localhost:8080/api/access/3 \
  -H 'Content-Type: application/json' \
  -d '{"action":"renew","expiresAt":"2026-12-31","actor":"Morgan Lee","note":"Quarterly review"}'
```

## Running

Requirements: JDK 21. The Gradle wrapper is included. Docker is optional.

```bash
# Local development: H2 database file under ./data, demo data, plain-text logs
./gradlew bootRun                    # http://localhost:8080
SERVER_PORT=3202 ./gradlew bootRun   # another port

# Quality gate: ktlint + detekt + unit and integration tests
./gradlew check

# Production-like stack: PostgreSQL 17 + app image
cp .env.example .env                 # then set POSTGRES_PASSWORD
docker compose up -d --build
```

You can also run the tests against a real PostgreSQL: `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/accesswindow SPRING_DATASOURCE_USERNAME=… ./gradlew test`. CI does this in a separate job.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` / `SERVER_PORT` | `8080` | HTTP port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/accesswindow` | JDBC URL (must be `jdbc:`, not `postgres://`) |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `accesswindow` / empty | Credentials |
| `DATABASE_POOL_SIZE` | `10` | Hikari pool size |
| `APP_TIME_ZONE` | `UTC` | IANA zone that defines "today" |
| `REVIEW_DUE_SOON_DAYS` | `14` | Due-soon window (0–90) |
| `REVIEW_MAX_HORIZON_DAYS` | `365` | Latest review date allowed, in days from today |
| `DEMO_SEED` | `false` (`true` in `dev`) | Seed demo records into an empty database |
| `ACTOR_HEADER` | empty | Trusted identity header from an authenticating proxy. When present, it overrides the self-declared `actor` |
| `RATE_LIMIT_ENABLED` / `RATE_LIMIT_WRITES` / `RATE_LIMIT_PERIOD` | `true` / `30` / `1m` | Write rate limit per client IP |
| `MAX_PAYLOAD_BYTES` | `16384` | Maximum API request body size |
| `HSTS_ENABLED` | `false` | Send `Strict-Transport-Security`. Enable only behind HTTPS |
| `FORWARD_HEADERS_STRATEGY` | `none` | Set to `framework` behind a reverse proxy so client IPs come from `X-Forwarded-For` |
| `LOG_FORMAT` | `ecs` | Structured JSON logs (`ecs`, `logstash`, `gelf`). Empty means plain text |

## Project layout

```
src/main/kotlin/dev/accesswindow/
  domain/        pure Kotlin: ReviewPolicy, ReviewCalendar, AccessGrant, Decision, AccessEvent, AccessLedger (port)
  application/   AccessReviewService: transactions and use cases
  persistence/   Spring Data JDBC adapter for the AccessLedger port
  web/           controller, DTOs, Problem Details, CSV, filters (security headers, correlation id, rate limit, payload limit)
  config/        typed properties, Clock/policy beans, Jackson, demo seed
src/main/resources/
  db/migration/  Flyway migrations (portable between PostgreSQL and H2)
  static/        UI: index.html, app.js, theme.js, style.css, self-hosted fonts, mark-aw.svg
src/test/kotlin/ domain unit tests + @SpringBootTest/MockMvc integration tests
```

## Architecture and decisions

- **Why Kotlin and Spring Boot 3.** The domain is small, but correctness matters: dates, states and audit. Kotlin gives null-safety at compile time, data classes for immutable records, and sealed types that force callers to handle every outcome (`Decision.Applied | AlreadyRevoked | InvalidDate`). Spring Boot provides proven pieces (validation, transactions, Flyway, Actuator, Problem Details, structured logs) without hand-written infrastructure. The single-file Express server this replaces had none of these.
- **Pure domain.** `domain/` has no Spring imports. Review rules use `java.time.LocalDate` and an injected `Clock`, so tests can pin "today" to any instant and zone.
- **Spring MVC, not WebFlux.** The workload is short CRUD over JDBC. Blocking MVC is simpler and has no throughput downside here.
- **Spring Data JDBC, not JPA.** Explicit SQL and plain rows. There is no lazy loading or dirty checking to reason about. Optimistic locking comes from `@Version`.
- **PostgreSQL in production, H2 for tests.** There is no Docker daemon in every development environment, so tests run on H2 in PostgreSQL mode with Spring Data's PostgreSQL dialect. The migrations use portable SQL. CI runs the same suite against a real PostgreSQL 17 to catch any gap. SQLite was rejected because it has no Spring Data JDBC dialect and handles concurrent writers poorly. H2 is a `developmentOnly`/test dependency and never ships in the jar.
- **Frontend without a build step.** Vanilla JS with DOM APIs only (`textContent`, no `innerHTML`). This allows a strict CSP with Trusted Types. Fonts are self-hosted (OFL), so the CSP needs no third-party origins.

## Production notes

- Image: multi-stage (Temurin 21 JDK → JRE), Spring Boot layered jar, non-root user `10001`, `HEALTHCHECK` on readiness. Compose runs it read-only with `no-new-privileges`.
- Security headers: CSP `default-src 'none'` with `'self'` for scripts, styles, fonts and images. It has no `unsafe-inline`, sets `frame-ancestors 'none'` and requires Trusted Types. The app also sends `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, COOP/CORP, `Cache-Control: no-store` on the API, and optional HSTS.
- Abuse limits: a token bucket per client IP for writes, a body size cap (chunked bodies included), JSON-only writes (`415` otherwise, which also blocks simple cross-site form posts), and strict input validation (lengths, single-line text, real calendar dates).
- CSV export prefixes cells that start with `= + - @` to block spreadsheet formula injection.
- Observability: ECS JSON logs, one line per API request with method, path, status and duration. Every response carries an `X-Request-Id` (a safe incoming value is kept, otherwise a UUID is generated), and the same id appears in every log line as `correlationId`.
- Graceful shutdown, Flyway migrations at startup, Actuator liveness/readiness probes.

## Limitations

- **No built-in authentication.** Deploy behind an SSO proxy (for example oauth2-proxy or Cloudflare Access) and set `ACTOR_HEADER` so the decision trail records verified identities. Without it, the actor name is self-declared.
- The rate limiter keeps its state in memory, per instance. With several replicas, rate limit at the proxy.
- No pagination. The register is meant for small teams (hundreds of records).
- There are no integrations yet: decisions are recorded here and applied manually in GitHub, cloud IAM and other systems. Read-only integrations and owner notifications are the next steps.
- Data from the old SQLite version (`data/accesswindow.sqlite`) is not migrated automatically.
