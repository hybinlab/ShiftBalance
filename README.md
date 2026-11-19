# ShiftBalance

ShiftBalance 是面向两组轮班的排班管理系统，包含候选排班生成、人工调整和发布、人员与岗位资格管理、排班规则，以及只按已发布结果计算的公平性统计。

管理员发布当前排班后，浏览器自动下载高清 PNG 排班表；已发布版本也可点击“导出 PNG”再次下载。图片包含各岗位、次日时段、过夜和推电宝安排，并保留监管、换廖和打饭颜色标记。文件名包含排班日期与版本号；图片导出失败不会撤销发布，可手动重试。此功能无需数据库升级。导出数据检查：`node frontend/scripts/check-schedule-image.mjs`。

## Windows 单机桌面版

独立 Tauri 窗口、内置 Java 和 PostgreSQL，支持离线运行。桌面构建、数据目录、备份恢复和 Windows 安装包说明见 [desktop/README.md](desktop/README.md)。服务器部署继续使用下述方式，两者分别保存数据。

## 部署结构

Docker Compose 启动两个容器：Spring Boot 后端和 Nginx 前端。Nginx 提供 React 页面，并将 `/api` 和接口文档请求转发给后端。PostgreSQL 使用已有的 database VM，Compose 不会创建本地数据库容器。

后端使用 Java 25。数据库结构由 `backend/src/main/resources/db/` 下的 SQL 文件维护；应用启动时不会自动建表或执行迁移。

## 部署

需要安装 Docker Engine 或 Docker Desktop，并启用 Docker Compose v2。还需要确认 PostgreSQL VM 在 `192.168.31.5:5432` 接受来自部署主机的连接，且数据库 `shiftbalance` 已导入当前版本所需的结构。

在项目根目录创建环境文件：

```sh
cp .env.example .env
```

编辑 `.env`，至少设置真实的数据库密码。不要把 `.env` 提交到仓库。首次使用空的 `app_user` 表时，同时设置 `SHIFTBALANCE_ADMIN_USERNAME` 和 `SHIFTBALANCE_ADMIN_PASSWORD` 以创建第一个管理员；两项都留空则不会自动创建用户。已有用户时，这些初始化变量不会重置用户名或密码。

启动或更新服务：

```sh
docker compose up -d --build
```

默认访问地址为 `http://localhost:8080`，API 文档为 `http://localhost:8080/doc.html`。修改 `.env` 中的 `SHIFTBALANCE_HTTP_PORT` 可更换宿主机端口。查看日志或停止服务：

```sh
docker compose logs -f
docker compose down
```

如果通过 HTTPS 反向代理对外提供服务，请将 `SHIFTBALANCE_SESSION_COOKIE_SECURE` 设为 `true`。如果通过浏览器直接跨域调用后端，再将 `SHIFTBALANCE_CORS_ALLOWED_ORIGINS` 配置为可信前端的完整来源；默认部署由 Nginx 同源转发，不需要额外开放 CORS 来源。

工作周期排班生成会依次运行多个岗位公平性优化阶段，耗时可能超过常见反向代理的 60 秒默认超时。项目内 Nginx 已为 `/api/` 设置 300 秒代理超时；如果前面还配置了宿主机 Nginx、负载均衡器或 CDN，也需要将其读取/空闲超时设为至少 300 秒，否则仍会收到 `504 Gateway Time-out`。修改后重建前端容器：

```sh
docker compose up -d --build frontend
```

## 数据库初始化与升级

先确认 PostgreSQL 已创建 `shiftbalance` 数据库和应用角色 `shiftbalance`。运行下面的脚本只适用于**新建空数据库**，并使用拥有建表和授权权限的数据库账户执行。每个文件只执行一次，按顺序导入：

```bash
cd backend
DB_HOST=192.168.31.5
DB_USER=shiftbalance # 若该角色没有建表/授权权限，换成数据库 owner
DB_FILES=(
  schema_v1.sql
  schema_v2_requirement_template.sql
  schema_v3_terminal_supervisor.sql
  seed_v3_requirement_template.sql
  migration_v4_derived_terminal_rest_group_flow.sql
  migration_v5_next_day_breakfast_handoff.sql
  migration_v6_next_day_breakfast_meal.sql
  migration_v7_next_day_early_staffing.sql
  migration_v8_fixed_person_rules.sql
  migration_v9_power_bank_rotation.sql
  migration_v10_auth_users.sql
  migration_v11_user_profile.sql
  migration_v12_overnight_parallel_assignment.sql
  migration_v13_meal_three_people.sql
  migration_v14_power_bank_same_group.sql
  migration_v15_configurable_rules.sql
)
for file in "${DB_FILES[@]}"; do
  psql -h "$DB_HOST" -p 5432 -U "$DB_USER" -d shiftbalance -v ON_ERROR_STOP=1 -f "src/main/resources/db/$file" || exit 1
done
```

`psql` 会提示输入数据库密码。已有数据库不要重跑初始 schema 或已经应用过的迁移；只执行尚未应用的迁移文件。应用本身没有迁移记录表，部署前请由数据库维护者核对当前 schema 版本和执行记录。数据库角色还需要对这些表拥有应用运行所需的读写权限。

打饭改为每次 3 人（含早餐）时，已部署到 v12 的数据库需在项目根目录执行以下升级，再更新前后端：

```sh
psql -h 192.168.31.5 -p 5432 -U shiftbalance -d shiftbalance -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/migration_v13_meal_three_people.sql
docker compose up -d --build
```

该脚本仅更新标准模板和日期例外中的打饭人数，保留原有组别、固定人员及其他岗位配置。已有排班和历史统计保留原记录；需要使用 3 人规则的日期请重新生成排班、检查后发布。

推电宝改为第一天晚下组连续承担“第一天早上、第一天晚上、第二天早上”，第二天晚上交给另一组。已有数据库部署此规则前，需在项目根目录执行 v14，再更新前后端。此迁移需表所有者或数据库管理员权限；共享数据库的表所有者为 `postgres`，应用账号 `shiftbalance` 无权修改该约束：

```sh
psql -h 192.168.31.5 -p 5432 -U postgres -d shiftbalance -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/migration_v14_power_bank_same_group.sql
docker compose up -d --build
```

v14 仅解除推电宝同一天同组只能承担一次任务的约束，保留每个方向一人的限制。两组按确认的名单逐次轮换；已有排班需要重新生成、检查后发布。

可视化统一规则需要 v15。已升级到 v14 的数据库，在更新应用前由表所有者执行：

```sh
psql -h 192.168.31.5 -p 5432 -U postgres -d shiftbalance -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/migration_v15_configurable_rules.sql
docker compose up -d --build
```

v15 仅增加业务规则配置字段，不改已有人员、排班和需求人数。尚未保存统一配置时沿用原有默认行为；应用启动不会自动执行此迁移。

## 可视化排班规则

进入“排班规则 → 标准规则模板”，可统一调整：

- 轮班锚点、参考组，以及周期内每天的早下组、休息日和推电宝负责组。
- 当天、次日的时段起止时间及顺序；剩余人员分配到候机楼、防爆，或不自动分配。
- 各时段岗位的最低/最高人数、组别、固定人员。最低人数为 0 允许空缺，最高人数留空不设上限。
- 回流和监管是否允许重复、是否必须指定监管、固定人员是否只上指定时段，以及打饭去重和公平性优化开关。
- 候机楼替岗标记的开关、目标人员、文字和时段；推电宝的开关、任务时间和各组轮换名单顺序。

修改后点击“保存全部标准规则”。周期生成覆盖所选日期所在的连续工作日；按已发布推电宝记录衔接名单顺序，停用或不在该组的人员会跳过。规则按人员 ID 保存，修改姓名不影响轮换。

新生成的排班保存独立规则快照；调整、发布和导出时使用该版本的规则。修改标准规则后，需要重新生成对应日期才会应用新配置。日期例外独立覆盖当天的岗位人数、组别和固定人员，其他规则沿用标准配置；更改时段后应检查既有日期例外。

目前仍采用两个启用组别；岗位资格和同一时段正式岗位不能兼任属于基本校验。过夜、推电宝继续作为独立附加任务，不占正式岗位名额。人员减少时可降低需求人数、调整组别或放宽重复限制；系统不会自行忽略已配置的最低人数，配置仍无解时会明确报错。

规则回归检查：

```sh
sh backend/scripts/check-configurable-rules.sh
node frontend/scripts/check-schedule-rotation.mjs
node frontend/scripts/check-bomb-replacements.mjs
node frontend/scripts/check-schedule-image.mjs
```

可选数据库回归：`sh backend/scripts/check-configurable-rules.sh --database`。读取本地数据库连接配置，仅创建连接内的临时表并最终回滚，验证保存、生成、调整、发布和历史快照，不修改现有业务表。

## 配置

| 环境变量 | 说明 |
| --- | --- |
| `SHIFTBALANCE_DB_URL` | JDBC 地址，默认连接 `192.168.31.5:5432/shiftbalance` |
| `SHIFTBALANCE_DB_USER` | PostgreSQL 应用用户名，默认 `shiftbalance` |
| `SHIFTBALANCE_DB_PASSWORD` | PostgreSQL 密码，必须配置 |
| `SHIFTBALANCE_ADMIN_USERNAME` | 空用户表的初始管理员用户名；与密码同时配置 |
| `SHIFTBALANCE_ADMIN_PASSWORD` | 空用户表的初始管理员密码 |
| `SHIFTBALANCE_ADMIN_DISPLAY_NAME` | 初始管理员显示名称 |
| `SHIFTBALANCE_HTTP_PORT` | 前端对外端口，默认 `8080` |
| `SHIFTBALANCE_CORS_ALLOWED_ORIGINS` | 允许的跨域来源，默认 `http://localhost:8080` |
| `SHIFTBALANCE_SESSION_COOKIE_SECURE` | HTTPS 部署时设为 `true` |

`SHIFTBALANCE_ADMIN_*` 仅用于首次建用户：应用只在 `app_user` 为空时插入管理员，不会在重启时恢复已更改的用户名或密码。部署后不要依赖这些初始化变量恢复账号。

## 本地开发

后端通过 `backend/.env.local` 读取数据库连接配置；可参考 `backend/README.md`。前端在 `frontend/` 目录运行：

```sh
npm ci
npm run dev
```

Vite 默认把 `/api` 转发到 `http://localhost:8080`。完整 API 字段和示例见 [backend/API.md](backend/API.md)。

自动排班因缺人、规则冲突或求解超时无法完成时，会保存为**手工排班**草稿，并显示失败原因。管理员可以自由选人、增减和拖拽，不受人数、分组、资格、固定人员或重复限制；候机楼和打饭不再自动补人。保存后可直接发布、计入统计和导出 PNG，不做排班规则校验。手工模式随历史版本保存；正常自动生成的版本仍执行规则校验。数据库或网络故障需先恢复连接。
