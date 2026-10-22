# ShiftBalance backend

The backend exposes the fixed time-slot catalog, A/B early-down rotation, and a configurable CP-SAT schedule generator. The direct solver endpoint accepts personnel and requirements in the request; database-backed generation reads personnel and the reusable requirement template from PostgreSQL. Requirements follow the configured group; an explicit fixed-person rule is the exception and can assign that person across groups.

## Run

The backend connects directly to PostgreSQL on the VM using the address in `.env.local`. To import the schema into a new database, run this; `psql` prompts for the database password:

```sh
cd backend
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/schema_v1.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/schema_v2_requirement_template.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/schema_v3_terminal_supervisor.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/seed_v3_requirement_template.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/migration_v4_derived_terminal_rest_group_flow.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/migration_v8_fixed_person_rules.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/migration_v9_power_bank_rotation.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/migration_v10_auth_users.sql
psql 'postgresql://shiftbalance@192.168.31.5:5432/shiftbalance' -v ON_ERROR_STOP=1 -f src/main/resources/db/migration_v11_user_profile.sql

Migration v8 adds nullable fixed-person references to standard and date-specific staffing rules. It was applied to the shared PostgreSQL database on 2026-10-22. The application does not run schema migrations on startup.
```

Migration v10 adds the application login table and its database grants. It was applied to the shared PostgreSQL database on 2026-10-23. The initial administrator is created from `backend/.env.local` only while the user table is empty. Later restarts do not recreate the configured username after it has been changed, or reset an existing password.

For a database that already has `schema_v1.sql` and `schema_v2_requirement_template.sql` imported, run `schema_v3_terminal_supervisor.sql`, then `migration_v4_derived_terminal_rest_group_flow.sql`. Run `seed_v3_requirement_template.sql` only when the standard template is empty; it updates existing standard rows.

The backend connection parameters are saved in the ignored `backend/.env.local` file using `SHIFTBALANCE_DB_*` names. Spring Boot loads it automatically when started from the repository root or `backend/`; no per-terminal `source` command is needed. Authentication also needs a first administrator account configured there:

```properties
SHIFTBALANCE_ADMIN_USERNAME=admin
SHIFTBALANCE_ADMIN_PASSWORD=replace-with-a-long-unique-password
SHIFTBALANCE_ADMIN_DISPLAY_NAME=排班管理员
```

The account is inserted only when the user table is empty; restarting does not recreate this account after a username change or reset its password. There is no public registration endpoint. The user table migration must be imported before setting these values. For an HTTPS deployment set `SHIFTBALANCE_SESSION_COOKIE_SECURE=true`.

Start the service from `backend/`:

```sh
cd backend
mvn spring-boot:run
```

`schema_v1.sql` creates the initial schema and reference rows; `schema_v2_requirement_template.sql` adds reusable staffing-template and rotation-anchor tables; `schema_v3_terminal_supervisor.sql` stores the terminal supervisor marker and disables the obsolete standalone leader position; migration v4 makes terminal staffing derived and assigns regular flow posts to the resting group; migration v5 configures both morning handoff rows and assigns 08:00–09:00 to the opposite group. Import each script once in order, then run `seed_v3_requirement_template.sql` to load the standard rules. The application requires `SHIFTBALANCE_DB_URL`, `SHIFTBALANCE_DB_USER`, and `SHIFTBALANCE_DB_PASSWORD` in `backend/.env.local`; it does not create or alter cluster tables at startup.

The service listens on port 8080. `GET /api/schedules/time-slots` returns the seven fixed slots. `GET /api/groups` and `GET /api/positions` return enabled reference data. `GET /api/people?asOf=YYYY-MM-DD` returns group membership, active qualifications, and counts from published assignments for that date. Configure the reusable staffing rules and 2-on/2-off rotation anchor once with `PUT /api/schedules/requirements-template`; daily requirements may still be overridden for exceptions. After the first workday, `NEXT_DAY_0530_0800` assigns its early-down group and `NEXT_DAY_0800_0900` assigns the opposite group for the breakfast handoff; both use normal bomb, mezzanine, and derived terminal coverage.

Open `http://localhost:8080/doc.html` for the Knife4j API explorer. It lists the REST endpoints and lets you send requests from the browser. The OpenAPI document is at `/v3/api-docs`. Both docs are public; CSRF initialization, session lookup, and login are public, while the other auth endpoints and business APIs require a session. The frontend obtains a CSRF token from `/api/auth/csrf` and sends it on every POST, PUT, and DELETE request. The allowed browser origins are configured by `SHIFTBALANCE_CORS_ALLOWED_ORIGINS` (comma-separated); keep this list limited to trusted frontend origins.

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

`PUT /api/schedules/requirements-template` saves the standard slot/post/count rules, the first workday of the 2-on/2-off cycle, and its first early-down group once. Each template row uses `EARLY_DOWN` or `LATE_DOWN`; generation maps those roles to A/B for the requested date. `GET /api/schedules/{date}/requirements` shows a manual date override when present, otherwise the resolved template. `PUT` on that date path replaces the full set for an exception; `DELETE` clears the exception. `POST /api/schedules/generate-from-database` accepts only `{"scheduleDate":"YYYY-MM-DD"}` and saves one date. The schedule page uses `POST /api/schedules/generate-work-period-from-database` with the same request; it accepts either working date, resolves the pair from the rotation anchor, and saves both candidates atomically after both days are feasible. The second day's fairness calculation includes the first day's assignments. Each returned day includes its resolved requirements and saved schedule. Use `GET /api/schedules/{scheduleId}` to load a schedule and `PUT /api/schedules/{scheduleId}` to edit a candidate; published schedules are read-only. Candidates do not affect published history counts; use `POST /api/schedules/{scheduleId}/publish` after review.

`POST /api/rotation/next` remains a stateless helper for callers that need to calculate one rotation step. Database-backed generation uses the saved template anchor and does not require a daily rotation call.

Database-backed generation also assigns the independent `推电宝` task for each workday: that day's early-down group takes one person for the morning return, and that day's late-down group takes one person for the 19:30 warehouse trip. It rotates independently within each group using published schedules as history. These duties are stored separately, do not consume formal staffing or qualification counts, and appear as `powerBankDuties` in saved schedule responses. Apply `migration_v9_power_bank_rotation.sql` to an existing database before using this feature; it was applied to the shared PostgreSQL database on 2026-10-23.

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

Each configured requirement must have enough enabled, qualified people in its group. The generator enforces the requested minimum/maximum, prevents a person from holding two posts in one time slot, minimizes assigned headcount first, then balances published historical post counts and avoids repeating the same post across that person's slots. A `null` maximum means there is no hard cap; without an explicit overstaffing target, generation still uses the minimum requested headcount. Generation saves an editable candidate; call `POST /api/schedules/{scheduleId}/publish` after human review to finalize it. Only the latest published schedule for each workday contributes to historical statistics and future fairness counts.
