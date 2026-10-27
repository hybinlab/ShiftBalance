# ShiftBalance

ShiftBalance 是面向两组轮班的排班管理系统，包含候选排班生成、人工调整和发布、人员与岗位资格管理、排班规则，以及只按已发布结果计算的公平性统计。

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

两天排班生成会依次运行多个岗位公平性优化阶段，耗时可能超过常见反向代理的 60 秒默认超时。项目内 Nginx 已为 `/api/` 设置 300 秒代理超时；如果前面还配置了宿主机 Nginx、负载均衡器或 CDN，也需要将其读取/空闲超时设为至少 300 秒，否则仍会收到 `504 Gateway Time-out`。修改后重建前端容器：

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
)
for file in "${DB_FILES[@]}"; do
  psql -h "$DB_HOST" -p 5432 -U "$DB_USER" -d shiftbalance -v ON_ERROR_STOP=1 -f "src/main/resources/db/$file" || exit 1
done
```

`psql` 会提示输入数据库密码。已有数据库不要重跑初始 schema 或已经应用过的迁移；只执行尚未应用的迁移文件。应用本身没有迁移记录表，部署前请由数据库维护者核对当前 schema 版本和执行记录。数据库角色还需要对这些表拥有应用运行所需的读写权限。

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
