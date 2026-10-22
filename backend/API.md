# ShiftBalance API

当前后端 HTTP/JSON 接口说明。默认地址为 `http://localhost:8080`；接口调试页面为 `/doc.html`，OpenAPI JSON 为 `/v3/api-docs`。启动和数据库隧道配置见 [README.md](README.md)。除特别说明外，请求和响应均使用 JSON，日期格式为 `YYYY-MM-DD`。

## 接口一览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/auth/csrf` | 初始化 CSRF 令牌 |
| GET | `/api/auth/session` | 查询当前登录会话 |
| POST | `/api/auth/login` | 使用账号密码创建会话 |
| POST | `/api/auth/logout` | 注销当前会话 |
| GET | `/api/auth/profile` | 查询当前用户资料 |
| PUT | `/api/auth/profile` | 修改当前用户名 |
| PUT | `/api/auth/profile/password` | 修改当前密码 |
| PUT | `/api/auth/profile/avatar` | 上传当前用户头像（multipart/form-data） |
| GET | `/api/auth/profile/avatar` | 获取当前用户头像 |
| GET | `/api/groups` | 查询启用的组别 |
| GET | `/api/positions` | 查询启用的岗位 |
| GET | `/api/people?asOf=日期` | 查询人员、当日有效组别/资格和已发布排班岗位次数 |
| POST | `/api/people` | 新增人员及初始组别、岗位资格 |
| PUT | `/api/people/{id}?asOf=日期` | 修改人员姓名、启用状态、备注，并可选修改组别和岗位资格 |
| GET | `/api/v1/statistics/person-position-assignments` | 查询历史人员/岗位分配次数及公平性统计 |
| GET | `/api/v1/statistics/position-distribution` | 汇总各组各岗位的分配分布和次数差异 |
| GET | `/api/schedules/time-slots` | 查询固定时段 |
| GET | `/api/schedules/calendar?startDate=2026-10-01&endDate=2026-11-11` | 查询日期范围内最新的待发布或已发布排班 |
| GET | `/api/schedules/requirements-template` | 查询标准岗位需求模板和轮班锚点 |
| PUT | `/api/schedules/requirements-template` | 一次性保存标准需求模板和轮班锚点 |
| GET | `/api/schedules/{date}/requirements` | 查询指定日期岗位需求 |
| PUT | `/api/schedules/{date}/requirements` | 整体覆盖指定日期岗位需求 |
| DELETE | `/api/schedules/{date}/requirements` | 清除指定日期覆盖并恢复标准模板 |
| POST | `/api/schedules/generate` | 用请求中的人员和需求直接求解，不写入数据库 |
| POST | `/api/schedules/generate-from-personnel` | 从数据库读取人员，使用请求中的需求并保存排班 |
| POST | `/api/schedules/generate-from-database` | 从数据库读取人员和指定日期需求并保存排班 |
| POST | `/api/schedules/generate-work-period-from-database` | 根据上二休二周期一次生成并保存连续两个工作日的排班 |
| GET | `/api/schedules/{scheduleId}` | 查询已保存排班及当前岗位人员 |
| PUT | `/api/schedules/{scheduleId}` | 调整已生成排班的岗位人员 |
| POST | `/api/schedules/{scheduleId}/publish` | 人工确认并发布待确认排班 |
| GET | `/api/v1/schedules/{scheduleId}/statistics` | 查询排班版本统计；待发布版本可预览 |
| POST | `/api/rotation/next` | 计算一个工作日的早下/晚下组及下一轮状态 |

日历查询范围最多 63 天。每个日期只返回一个版本：有待发布版本时优先返回待发布版本，否则返回最新已发布版本。响应中的 `requirements` 是该排班版本生成时保存的需求快照。

除 CSRF、会话查询和登录接口外，其余接口均需有效登录会话。浏览器先请求 `GET /api/auth/csrf`，从 JSON 响应中读取 `csrfToken`；后续 `POST`、`PUT`、`DELETE` 请求需携带 `X-CSRF-TOKEN` 请求头和会话 Cookie。登录失败统一返回 HTTP 401 和 `{"message":"账号或密码错误"}`。没有自助注册接口，初始管理员由环境变量 `SHIFTBALANCE_ADMIN_USERNAME`、`SHIFTBALANCE_ADMIN_PASSWORD` 和可选的 `SHIFTBALANCE_ADMIN_DISPLAY_NAME` 创建，且只在用户表为空时创建；因此改名后的账号不会在服务重启时被旧配置重新创建。服务重启不会覆盖已存在账号的密码。用户表迁移见 `src/main/resources/db/migration_v10_auth_users.sql` 和 `src/main/resources/db/migration_v11_user_profile.sql`。

### 用户资料

`GET /api/auth/profile` 返回 `{"username":"admin","avatarAvailable":false}`。通过 `PUT /api/auth/profile` 更新用户名：

```json
{"username":"new-admin"}
```

用户名长度为 3–100 个字符，只允许英文字母、数字、点、短横线和下划线；系统会转为小写。已占用的用户名返回 HTTP 409。

通过 `PUT /api/auth/profile/password` 修改密码：

```json
{"currentPassword":"old-password","newPassword":"a-new-password-with-10-chars"}
```

新密码至少 10 个字符且最多 72 个 UTF-8 字节；当前密码不匹配返回 HTTP 400。

头像使用 `multipart/form-data`，字段名为 `file`，只接受 JPEG、PNG、WebP 图片，最大 2 MB。服务根据文件签名判断图片类型，不信任上传请求声明的 MIME 类型。头像以二进制数据保存于 `app_user`，`GET /api/auth/profile/avatar` 返回该用户图片；未设置时返回 HTTP 204。

```http
GET /api/auth/csrf
```

```json
{"csrfToken":"..."}
```

```http
POST /api/auth/login
Content-Type: application/json
X-CSRF-TOKEN: <csrfToken>
```

```json
{"username":"admin","password":"..."}
```

登录成功和 `GET /api/auth/session` 都返回：

```json
{"authenticated":true,"username":"admin","displayName":"admin"}
```

尚未登录时，session 接口返回 `{"authenticated":false,"username":null,"displayName":null}`；`displayName` 为兼容字段，当前与 `username` 相同。`POST /api/auth/logout` 成功返回 HTTP 204。

生成和发布是两个步骤。页面的周期生成接口会把可行结果保存为 `CANDIDATE`（待发布），此时可以人工调整，但不进入历史统计或公平性基数。确认后调用 `POST /api/schedules/{scheduleId}/publish`，状态变为 `PUBLISHED`，从此计入统计和后续排班公平性计算；已发布版本不能直接编辑。如需修改，生成新的待发布版本，发布新版本时系统会把同一工作日先前的发布版本标记为 `CANCELLED`，避免重复计数。在新版本发布前，旧的已发布版本仍是该工作日的统计依据。`POST /api/schedules/generate` 只求解并返回结果，不保存。

## 组别、岗位与时段

`GET /api/groups` 和 `GET /api/positions` 返回数组，元素包含 `id`、`code`、`name`。`id` 可用于统计接口筛选。岗位编码为：`MEAL` 打饭、`OVERNIGHT` 过夜、`BOMB` 防爆、`TERMINAL` 候机楼、`MEZZANINE` 夹层、`FLOW` 回流/拉包。历史 `LEADER` 编码已停用；负责人从候机楼已排人员中标记，不单独占岗位名额。

`GET /api/schedules/time-slots` 返回 `{ "code", "sequence", "label", "dayOffset" }` 数组。固定编码为 `DAY_0900_1130`、`DAY_1130_1430`、`DAY_1430_1730`、`DAY_1730_1930`、`NIGHT_UNTIL_END`、`NEXT_DAY_0530_0800`、`NEXT_DAY_0800_0900`。`dayOffset: 1` 表示次日时段；`NIGHT_UNTIL_END` 表示 19:30 至航班结束。

## 人员

`GET /api/people` 的 `asOf` 可选，缺省为中国时区当天。返回元素包含 `id`、`name`、`groupCode`、`enabled`、`remark`、`qualifiedPositionCodes`、`historicalCounts`。资格和组别按 `asOf` 查有效记录；`historicalCounts` 只统计每个工作日最新已发布版本中，该人员当时属于当前组、具备该岗位资格且该时段确有岗位需求的分配次数。它与统计响应的 `fairnessCount` 使用相同口径，并作为生成器公平性优化的历史基数。候选排班及未发布调整不会改变历史基数；两天工作段的第二天生成时，算法仍会临时计入刚生成的第一天结果。

## 统计

### 人员岗位历史分配

`GET /api/v1/statistics/person-position-assignments` 只统计已发布排班（`PUBLISHED`），每个工作日取最新发布版本。待发布、草稿和已取消排班不计入；存在较新的待发布版本时，先前已发布版本仍计入，直到新版本正式发布。支持可选参数 `startDate`、`endDate`、`personId`、`positionId`、`groupId`；日期按排班的工作日日期包含首尾筛选，省略日期表示覆盖全部已发布历史。日期起止都传时，`startDate` 不能晚于 `endDate`。各 ID 均为数据库正整数 ID。

每个 `assignmentCount` 对应一条最新已发布排班中的人员/岗位/时段分配记录，同一人员在同一天不同时间段做同一岗位会分别计数。它表示最终发布的排班安排，不是考勤或实际到岗记录；系统当前没有记录实际出勤。`fairnessCount` 是用于长期公平性优化的次数：只纳入人员当时属于该组、当时具备该岗位资格、且排班快照中该组该时段确有需求的分配。候机楼岗位不要求单独岗位资格。`eligibleSlotCount` 是该人员在所选已发布历史区间内符合组别、资格且存在岗位需求的时段数；即使分配次数为零也会返回该人员岗位组合。

```http
GET /api/v1/statistics/person-position-assignments?startDate=2026-10-01&endDate=2026-10-31&groupId=1
```

```json
{
  "startDate": "2026-10-01",
  "endDate": "2026-10-31",
  "assignments": [
    {
      "personId": 1,
      "personName": "示例甲",
      "positionId": 3,
      "positionCode": "BOMB",
      "positionName": "防爆",
      "groupId": 1,
      "groupCode": "A",
      "assignmentCount": 12,
      "fairnessCount": 12,
      "eligibleSlotCount": 40
    }
  ]
}
```

### 岗位人员分布

`GET /api/v1/statistics/position-distribution` 接受可选 `startDate`、`endDate`、`positionId` 和 `groupId`，日期及历史范围规则同上。每行按组别和岗位汇总；`assignmentCount` / `fairnessCount` 是总次数，`minFairnessCount`、`maxFairnessCount` 和 `fairnessGap` 只比较所选区间内至少有一个符合条件时段的人员，避免把不具备资格或不属于该组的人员当成零次岗位分配。

### 单次排班分配统计

`GET /api/v1/schedules/{scheduleId}/statistics` 返回指定排班版本的人员岗位明细及岗位分布。待发布版本可用于发布前核对，但不会进入历史统计；草稿或已取消排班返回 `409 Conflict`。其 `assignmentCount` 按该版本每条时段分配计数，公平性分布使用与历史接口及求解器相同的资格/组别/需求口径。

历史资格和组别按各次排班日期的有效期记录计算。系统尚未保存人员启停用状态的历史版本，因此 `eligibleSlotCount` 不按过去的启停用状态回溯；当前求解仍只会分配给当前启用人员。

新增示例（成功返回 `201 Created`）：

```json
{
  "name": "示例甲",
  "groupCode": "A",
  "enabled": true,
  "remark": "",
  "effectiveFrom": "2026-10-15",
  "qualifiedPositionCodes": ["BOMB", "FLOW"]
}
```

`POST /api/people` 必须提供上述字段；`remark` 可为 `null`。`effectiveFrom` 同时作为初始组别和岗位资格的生效日期。`PUT /api/people/{id}` 必须提供 `name`、`enabled`、`remark`，并可选提供 `groupCode` 和 `qualifiedPositionCodes`；不传这两个字段分别表示保留现有组别和岗位资格。组别和岗位资格变更都从中国时区当天生效，并保留旧历史；传空 `qualifiedPositionCodes` 数组表示移除当前全部资格。只能指定启用的组别和岗位。`asOf` 只用于选择响应中展示的组别/资格日期。

修改组别和岗位示例（其他必填字段也需一起提交）：

```json
{
  "name": "示例甲",
  "enabled": true,
  "remark": "",
  "groupCode": "B",
  "qualifiedPositionCodes": ["BOMB", "FLOW"]
}
```

人员写入和查询的响应均为 `PersonView`，例如：

```json
{
  "id": "1",
  "name": "示例甲",
  "groupCode": "A",
  "enabled": true,
  "remark": "",
  "qualifiedPositionCodes": ["BOMB", "FLOW"],
  "historicalCounts": {"BOMB": 4, "FLOW": 2}
}
```

## 推电宝轮换

数据库保存型排班接口会在每个工作日自动附带两条 `powerBankDuties`：该工作日早下组负责早上推回，晚下组在 `NIGHT_UNTIL_END`（19:30–结束）负责晚上推入仓库。早下组从该工作日 `NEXT_DAY_0530_0800` 的防爆需求组别读取，晚下组从 `NIGHT_UNTIL_END` 的防爆需求组别读取；两天工作周期中的每一天分别按当天配置安排。轮换按组独立进行，周期批量生成时会在两天之间继续推进；历史轮换位置只依据已发布排班，候选排班重新生成或调整不会提前消耗轮次。

推电宝是独立辅助任务，不属于岗位需求，不参与岗位资格校验、岗位人数覆盖或岗位公平性统计；同一人员可以同时承担该任务和正式岗位。`GET /api/schedules/{scheduleId}` 和数据库保存型生成响应中的任务结构如下：

```json
{
  "direction": "TO_WAREHOUSE",
  "slotCode": "NIGHT_UNTIL_END",
  "groupCode": "B",
  "personId": "12",
  "personName": "示例乙"
}
```

`direction` 为 `TO_WAREHOUSE`（送入仓库）或 `FROM_WAREHOUSE`（推回）。直接求解接口 `POST /api/schedules/generate` 不保存排班，因此不会生成或推进推电宝轮换。

## 每日岗位需求

通常只需配置一次标准模板。模板中的 `groupRole` 使用 `EARLY_DOWN` 或 `LATE_DOWN`；系统根据轮班锚点和二上二休日历，将其解析为当天的 A/B 组。锚点日期是一个周期的第一个工作日；该日及次日工作，随后两天休息。轮班示例：锚点为 `2026-10-12` 且首日早下组为 A 时，工作日早下组依次为 12 日 A、13 日 B、16 日 B、17 日 A。

`PUT /api/schedules/requirements-template` 整体替换标准模板。下例仅展示格式，实际模板应包含所有固定岗位和时段。每个两天工作段的第一天会包含次日 `05:30–08:00` 和 `08:00–09:00` 交接时段：前者由早下组上岗并安排 2 人打早餐，后者由另一组接班；第二工作日之后不生成 08:00–09:00 用餐交接。两个早间时段都固定安排候机楼（负责人）1 人和夹层 1 人；除去候机楼、夹层和 05:30–08:00 的 2 名打早餐人员后，该组其余人员全部安排防爆。模板岗位组别分别设为 `EARLY_DOWN` 和 `LATE_DOWN`。

```json
{
  "anchorWorkDate": "2026-10-12",
  "firstEarlyDownGroup": "A",
  "requirements": [
    {
      "slotCode": "DAY_0900_1130",
      "positionCode": "BOMB",
      "groupRole": "EARLY_DOWN",
      "minCount": 3,
      "maxCount": null
    },
    {
      "slotCode": "NIGHT_UNTIL_END",
      "positionCode": "FLOW",
      "groupRole": "LATE_DOWN",
      "minCount": 1,
      "maxCount": 1
    }
  ]
}
```

`GET /api/schedules/{date}/requirements` 优先返回当天手动覆盖；没有覆盖时返回按模板和轮班规则解析的需求。`PUT` 会整体替换当天需求，适用于临时例外；`DELETE` 可删除覆盖并恢复模板。

需求元素字段：`slotCode`、`positionCode`、`groupCode`、`minCount`、`maxCount`。查询响应额外包含 `scheduleDate` 和 `positionName`。

```json
{
  "requirements": [
    {
      "slotCode": "DAY_0900_1130",
      "positionCode": "BOMB",
      "groupCode": "A",
      "minCount": 3,
      "maxCount": 3
    },
    {
      "slotCode": "DAY_0900_1130",
      "positionCode": "FLOW",
      "groupCode": "A",
      "minCount": 2,
      "maxCount": null
    }
  ]
}
```

日期覆盖列表必须非空。同一时段同一岗位不能重复；`minCount` 不得小于 0，`maxCount` 可为 `null`，否则不得小于最低人数。`maxCount: null` 表示无硬上限；求解器仍会优先使用满足最低人数的最少人手。

## 排班生成

`POST /api/schedules/generate` 的完整请求形状如下；两个数据库生成接口仅需 `scheduleDate` 或 `scheduleDate` 加 `requirements`，参见下文。

```json
{
  "scheduleDate": "2026-10-17",
  "staff": [
    {
      "id": "1",
      "name": "示例甲",
      "groupCode": "A",
      "enabled": true,
      "qualifiedPositionCodes": ["BOMB", "FLOW"],
      "historicalCounts": {"BOMB": 4, "FLOW": 2}
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

求解只从启用、组别匹配且具备岗位资格的人员中安排防爆和夹层；防爆每段至少 3 人，打饭每次固定 2 人。常规时段候机楼人数由求解器分配完同组其他岗位后自动计算，候机楼资格无需单独维护；该时段的候机楼需求行应设 `minCount: 0`、`maxCount: null`。例外是次日 `05:30–08:00` 和 `08:00–09:00`：候机楼（负责人）和夹层各 1 人；05:30 时另安排 2 人打早餐。除这些固定岗位人员外，该组其余人员全部安排防爆。这两个早间时段不允许添加其他岗位。同一人员同一时段不能兼任多个岗位。

常规时段回流由防爆上岗组的轮休组承担；19:30 至航班结束的回流由防爆上岗组承担。回流人员当天跨时段不得重复，并优先选取当天其他时段承担候机楼岗位的人员。每个候机楼时段会在候机楼人员中选一名监管人员，且监管人员当天不重复。打饭优先选择具备防爆资格的人员。优化顺序为减少总上岗人数、平衡同组同岗位的历史加本次次数、尽量避免同一天重复同一岗位，再处理打饭和回流偏好。需求按每个时段分别指定组别，不会跨组混排。

`POST /api/schedules/generate-from-personnel` 请求为 `{"scheduleDate":"2026-10-17","requirements":[...]}`，需求元素格式与上节相同；人员、资格和历史次数从数据库读取。`POST /api/schedules/generate-from-database` 请求为 `{"scheduleDate":"2026-10-17"}`，按单日读取需求并保存生成结果。排班页面应使用 `POST /api/schedules/generate-work-period-from-database`，请求格式相同；可传入工作周期的任一工作日，服务端按轮班锚点定位两天工作段，一次生成并保存两天。只有两天都可行时才写入数据库，第二天的公平性计算会计入第一天的岗位分配。响应包含 `workPeriodStartDate`、`workPeriodEndDate`、整体 `status`、`days`（逐日返回实际需求和生成结果）及 `messages`。传入休息日时返回 `400`。`/generate` 仅返回结果，不写入数据库。

使用生成响应中的 `scheduleId` 调用 `GET /api/schedules/{scheduleId}` 查询排班。待发布排班可通过 `PUT /api/schedules/{scheduleId}` 提交完整岗位分配；人员使用数据库 ID。系统会检查人员当天的组别、相应岗位资格（常规候机楼按剩余在岗人员规则，次日早班固定 1 人）、需求人数、同一时段不可重复上岗，以及监管人员和回流的轮换规则。过夜人员属于岗位分配，可在原时段、原组内按岗位资格调整。请求可另外带 `powerBankDuties`，在推电宝的原组内换人；此辅助任务不检查岗位资格，也不占岗位名额。省略 `powerBankDuties` 表示保留现有推电宝安排。只有 `CANDIDATE` 状态可编辑，其他状态返回 `409`。

人工确认后调用 `POST /api/schedules/{scheduleId}/publish`，无需请求体。成功响应为更新后的 `ScheduleResponse`，其中 `status` 为 `PUBLISHED`。重复发布当前已发布版本会返回该版本；已取消或草稿版本返回 `409`。同一工作日的新版本发布后，旧发布版本自动转为 `CANCELLED`。

```bash
curl -X POST http://localhost:8080/api/schedules/42/publish
```

调整请求示例（`PUT /api/schedules/{scheduleId}`）：

```json
{
  "assignments": [
    {
      "slotCode": "DAY_0900_1130",
      "positionCode": "BOMB",
      "groupCode": "A",
      "personIds": ["1", "2", "3"],
      "terminalSupervisorId": null
    },
    {
      "slotCode": "DAY_0900_1130",
      "positionCode": "TERMINAL",
      "groupCode": "A",
      "personIds": ["4", "5"],
      "terminalSupervisorId": "4"
    }
  ],
  "powerBankDuties": [
    { "direction": "TO_WAREHOUSE", "groupCode": "B", "personId": "12" },
    { "direction": "FROM_WAREHOUSE", "groupCode": "A", "personId": "5" }
  ]
}
```

生成响应字段：`scheduleDate`、`status`、`assignments`、`messages`、`fairness`、`scheduleId`、`version`。每条 `assignment` 包含时段、岗位、组别和 `people` 人员数组；人员字段 `terminalSupervisor` 标记该候机楼时段的监管人员，其他人员为 `false`。`fairness` 提供每个组别/岗位历史加本次次数的最小值和最大值。数据库接口成功保存时返回候选 `scheduleId`、`version`；直接求解或不可行时二者为 `null`。

```json
{
  "scheduleDate": "2026-10-17",
  "status": "OPTIMAL",
  "assignments": [
    {
      "slotCode": "DAY_0900_1130",
      "slotLabel": "09:00–11:30",
      "slotSequence": 1,
      "positionCode": "BOMB",
      "positionName": "防爆",
      "groupCode": "A",
      "people": [
        {"id": "1", "name": "示例甲"},
        {"id": "2", "name": "示例乙"},
        {"id": "3", "name": "示例丙"}
      ]
    }
  ],
  "messages": [],
  "fairness": [
    {"groupCode": "A", "positionCode": "BOMB", "lowestProjectedCount": 5, "highestProjectedCount": 5}
  ],
  "scheduleId": 18,
  "version": 1
}
```

| HTTP 状态 | 含义 |
| --- | --- |
| 200 | `OPTIMAL` 最优解或 `FEASIBLE` 可行解 |
| 400 | 请求字段/编码无效，或数据库生成时没有可用的模板/日期需求 |
| 422 | `INFEASIBLE`：人数不足或岗位约束冲突 |
| 503 | `UNKNOWN` 或 `MODEL_INVALID`：求解器未能完成 |

## 早下/晚下轮换

`POST /api/rotation/next` 示例：

```json
{
  "groupACode": "A",
  "groupBCode": "B",
  "firstEarlyDownGroup": "A",
  "workDayIndex": 0
}
```

`workDayIndex` 为 `0` 表示本轮第一个工作日，`1` 表示第二个工作日。响应包含 `earlyDownGroup`、`lateDownGroup` 和 `nextState`（`firstEarlyDownGroup`、`workDayIndex`）。该接口只计算一步且不保存状态；数据库生成排班会使用标准需求模板中的轮班锚点自动计算，不需要逐日调用此接口。

```json
{
  "earlyDownGroup": "A",
  "lateDownGroup": "B",
  "nextState": {"firstEarlyDownGroup": "A", "workDayIndex": 1}
}
```

## 固定人员规则

标准模板和日期例外的岗位需求都支持可选字段 **fixedPersonId**。填写人员 ID 后，该人员当天只会安排在所有引用该 ID 的固定时段和岗位中；同一人可在不同时间段分别固定，但不能在同一时段固定到多个岗位。固定人员可以属于与规则组别不同的组，仍须在排班日期有效并具备岗位资格。省略字段或传 null 表示由求解器分配。

人员 ID 使用 GET 人员接口返回的字符串 ID。修改已生成的候选排班时，固定人员约束会继续生效；不能把该人员移到其他时段或岗位。

## 错误处理

无效日期、必填字段缺失、未知编码或人数范围错误通常返回 `400`；人员 ID 不存在返回 `404`。错误响应使用 Spring 默认格式，目前没有单独承诺固定 JSON 错误结构。
