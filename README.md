# AccessWindow

An access review ledger for small teams. Give every collaborator permission a human owner, a business reason, and a review date. Find overdue access and revoke or renew it from one place.

## Run locally

```bash
npm install
npm start
```

Open `http://localhost:3008`. Demo collaborators and access records are seeded into a local SQLite database.

## Product scope

This MVP is a review ledger, not an identity provider: it does not grant or revoke permissions in cloud services. The next product step is read-only integrations for GitHub, cloud IAM, and ticketing systems, followed by owner notifications and evidence-backed revocation workflows.

## API

- `GET /api/access` — access register
- `GET /api/summary` — overview metrics
- `POST /api/access` — create a tracked grant
- `PATCH /api/access/:id` — revoke or renew a record

Stack: Express, SQLite, and a responsive vanilla JavaScript UI.
