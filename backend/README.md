# ShiftBalance backend

The backend exposes the fixed time-slot catalog, A/B early-down rotation, and a configurable CP-SAT schedule generator. The direct solver endpoint accepts personnel and requirements in the request; database-backed generation reads personnel and the reusable requirement template from PostgreSQL. Each requirement belongs to one group, so the solver cannot mix groups within a post.

## Run

The backend connects directly to PostgreSQL on the VM using the address in `.env.local`. To import the schema into a new database, run this; `psql` prompts for the database password:

```sh
cd backend
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/schema_v1.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/schema_v2_requirement_template.sql
```

For a database that already has `schema_v1.sql` imported, run only the `schema_v2_requirement_template.sql` command.

The backend connection parameters are saved in the ignored `backend/.env.local` file using `SHIFTBALANCE_DB_*` names. Spring Boot loads it automatically when started from the repository root or `backend/`; no per-terminal `source` command is needed. Start the service from `backend/`:

```sh
cd backend
mvn spring-boot:run
```

`schema_v1.sql` creates the initial schema and reference rows; `schema_v2_requirement_template.sql` adds reusable staffing-template and rotation-anchor tables. Import each script once in order. The application requires `SHIFTBALANCE_DB_URL`, `SHIFTBALANCE_DB_USER`, and `SHIFTBALANCE_DB_PASSWORD` in `backend/.env.local`; it does not create or alter cluster tables at startup.

The service listens on port 8080. `GET /api/schedules/time-slots` returns the seven fixed slots. `GET /api/groups` and `GET /api/positions` return enabled reference data. `GET /api/people?asOf=YYYY-MM-DD` returns group membership, active qualifications, and counts from published assignments for that date. Configure the reusable staffing rules and 2-on/2-off rotation anchor once with `PUT /api/schedules/requirements-template`; daily requirements may still be overridden for exceptions. Add the `NEXT_DAY_0800_0900` early-down row to the template for the conditional rest-day handoff; the resolver uses it only after the second workday in each pair.

Open `http://localhost:8080/doc.html` for the Knife4j API explorer. It lists the REST endpoints and lets you send requests from the browser. The OpenAPI document is at `/v3/api-docs`.

See [API.md](API.md) for request/response fields, examples, status codes, and generation behavior.

Create a person with `POST /api/people`; `effectiveFrom` records when the group and qualifications start applying:

```json
{
  "name": "示例甲",
  "groupCode": "A",
  "enabled": true,
  "effectiveFrom": "2026-10-15",
  "qualifiedPositionCodes": ["BOMB", "FLOW"]
}
```

`PUT /api/people/{id}` updates name, enabled state, and remark, and can optionally change the current group with `groupCode` and qualifications with `qualifiedPositionCodes`. Group and qualification changes take effect on the current China-time date and retain prior history; omitting either field leaves it unchanged. `POST /api/schedules/generate` still accepts a complete staff list for direct solver use. `POST /api/schedules/generate-from-personnel` loads staff and published-history counts from PostgreSQL, then accepts the date and per-slot requirements:

```json
{
  "scheduleDate": "2026-10-17",
  "requirements": [
    {
      "slotCode": "DAY_0900_1130",
      "positionCode": "BOMB",
      "positionName": "防爆",
      "groupCode": "A",
      "minCount": 3,
      "maxCount": null
    }
  ]
}
```

`PUT /api/schedules/requirements-template` saves the standard slot/post/count rules, the first workday of the 2-on/2-off cycle, and its first early-down group once. Each template row uses `EARLY_DOWN` or `LATE_DOWN`; generation maps those roles to A/B for the requested date. `GET /api/schedules/{date}/requirements` shows a manual date override when present, otherwise the resolved template. `PUT` on that date path replaces the full set for an exception; `DELETE` clears the exception. `POST /api/schedules/generate-from-database` accepts only `{"scheduleDate":"YYYY-MM-DD"}` and automatically uses that date's template or override with personnel, qualifications, and published-history counts. Database-backed generation saves a `CANDIDATE` schedule and returns its `scheduleId` and `version`; candidates do not affect history counts.

`POST /api/rotation/next` remains a stateless helper for callers that need to calculate one rotation step. Database-backed generation uses the saved template anchor and does not require a daily rotation call.

## Request example

```json
{
  "scheduleDate": "2026-10-17",
  "staff": [
    {
      "id": "p01",
      "name": "示例甲",
      "groupCode": "A",
      "enabled": true,
      "qualifiedPositionCodes": ["BOMB", "FLOW"],
      "historicalCounts": {"BOMB": 4, "FLOW": 2}
    },
    {
      "id": "p02",
      "name": "示例乙",
      "groupCode": "A",
      "enabled": true,
      "qualifiedPositionCodes": ["BOMB", "FLOW"],
      "historicalCounts": {"BOMB": 2, "FLOW": 3}
    },
    {
      "id": "p03",
      "name": "示例丙",
      "groupCode": "A",
      "enabled": true,
      "qualifiedPositionCodes": ["BOMB", "FLOW"],
      "historicalCounts": {"BOMB": 3, "FLOW": 1}
    }
  ],
  "requirements": [
    {
      "slotCode": "DAY_0900_1130",
      "positionCode": "BOMB",
      "positionName": "防爆",
      "groupCode": "A",
      "minCount": 3,
      "maxCount": null
    }
  ]
}
```

Each configured requirement must have enough enabled, qualified people in its group. The generator enforces the requested minimum/maximum, prevents a person from holding two posts in one time slot, minimizes assigned headcount first, then balances historical post counts and avoids repeating the same post across that person's slots. A `null` maximum means there is no hard cap; without an explicit overstaffing target, generation still uses the minimum requested headcount. A management UI and publishing workflow remain later slices.
