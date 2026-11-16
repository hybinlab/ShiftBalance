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
| GET | `/api/schedules/time-slots` | 查询已配置时段 |
| GET | `/api/schedules/calendar?startDate=2026-10-01&endDate=2026-11-11` | 查询日期范围内最新的待发布或已发布排班 |
| GET | `/api/schedules/requirements-template` | 查询标准岗位需求模板和轮班锚点 |
| PUT | `/api/schedules/requirements-template` | 一次性保存标准需求、轮班锚点及完整 rules |
| GET | `/api/schedules/{date}/requirements` | 查询指定日期岗位需求 |
| PUT | `/api/schedules/{date}/requirements` | 整体覆盖指定日期岗位需求 |
| DELETE | `/api/schedules/{date}/requirements` | 清除指定日期覆盖并恢复标准模板 |
| POST | `/api/schedules/generate` | 用请求中的人员和需求直接求解，不写入数据库 |
| POST | `/api/schedules/generate-from-personnel` | 从数据库读取人员，使用请求中的需求并保存排班 |
| POST | `/api/schedules/generate-from-database` | 从数据库读取人员和指定日期需求并保存排班 |
| POST | `/api/schedules/generate-work-period-from-database` | 根据已配置周期生成并保存连续工作日的排班 |
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

过夜属于附加职责，不占用当晚正式岗位名额；同一人员可以在 `NIGHT_UNTIL_END` 同时安排一个正式岗位和 `OVERNIGHT`。推电宝存于独立任务表，也不占用正式岗位名额。已有数据库需应用 `migration_v12_overnight_parallel_assignment.sql` 才能保存同一人员的过夜与正式岗位记录。

## 组别、岗位与时段

`GET /api/groups` 和 `GET /api/positions` 返回数组，元素包含 `id`、`code`、`name`。`id` 可用于统计接口筛选。岗位编码为：`MEAL` 打饭、`OVERNIGHT` 过夜、`BOMB` 防爆、`TERMINAL` 候机楼、`MEZZANINE` 夹层、`FLOW` 回流/拉包。历史 `LEADER` 编码已停用；负责人从候机楼已排人员中标记，不单独占岗位名额。

`GET /api/schedules/time-slots` 返回 `{ "code", "sequence", "label", "dayOffset" }` 数组。初始默认编码为 `DAY_0900_1130`、`DAY_1130_1430`、`DAY_1430_1730`、`DAY_1730_1930`、`NIGHT_UNTIL_END`、`NEXT_DAY_0530_0800`、`NEXT_DAY_0800_0900`。`dayOffset: 1` 表示次日时段；`NIGHT_UNTIL_END` 表示 19:30 至航班结束。

## 人员

`GET /api/people` 的 `asOf` 可选，缺省为中国时区当天。返回元素包含 `id`、`name`、`groupCode`、`enabled`、`remark`、`qualifiedPositionCodes`、`historicalCounts`。资格和组别按 `asOf` 查有效记录；`historicalCounts` 只统计每个工作日最新已发布版本中，该人员当时属于当前组、具备该岗位资格且该时段确有岗位需求的分配次数。它与统计响应的 `fairnessCount` 使用相同口径，并作为生成器公平性优化的历史基数。候选排班及未发布调整不会改变历史基数；连续工作日生成时，算法会临时计入本次批量中此前各日的结果。

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

数据库保存型排班接口按规则附带 `powerBankDuties`；关闭推电宝时为空，周期日的任一方向可设为不安排。默认每个工作日有两条任务，按当天早上推回、当天晚上送仓排序。每个两天工作段的第一天早上、第一天晚上、第二天早上由第一天晚下组负责；第二天晚上由另一组负责，并在下个工作段继续轮换。工作段由标准模板的轮班锚点确定，单独生成第二天时也使用同一规则，休息日不安排推电宝。

例如第一天 A 早下、B 晚下：当天早晚均由 B 组承担，第二天早上仍由 B 组承担、晚上由 A 组承担；下一工作段第一天早晚均由 A 组承担，第二天早上 A 组、晚上 B 组。每次任务按该组名单顺延一人，名单末尾回到开头，跳过当天未启用或不在该组的人员。名单外人员不参与自动轮换。v15 起名单按人员 ID 保存，可在排班规则页增删排序；改名不影响轮换。以上组别顺序为初始默认值，每天负责组可通过 rules.cycle 调整。

`GET /api/schedules/power-bank-order` 返回实际使用的 A/B 组有序姓名列表，排班页右侧直接展示该接口的数据。初始默认顺序为：

- A：唐龙、甘春霖、刘雨鑫、吕珈萱、吴世富、石蓉昌、骆灿、黄勇斌、黎哲成。
- B：宾昕宇、阳菲莉、李文锦、王小曼、刘芳全、黄在显、钟绍凌、邹运谷、莫少波。

轮换位置只依据生成日期之前的已发布排班。同一天同组有两次任务时，以晚上送仓的人作为最后一人；批量生成时各工作日之间继续推进，未发布版本和重复生成不会消耗历史轮次。手动调整仍允许在任务原组内选择启用人员；若上次手动人选不在固定名单内，下次从该组名单开头继续。

已有数据库需先应用 `migration_v14_power_bank_same_group.sql`，解除同一天同组只能承担一个方向的旧约束；`(schedule_id, direction)` 主键继续确保每个方向只有一人。迁移保留已有记录，新规则通过重新生成候选版本生效。

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

`direction` 为 `TO_WAREHOUSE`（当天晚上送入仓库，时段编码取 `rules.powerBankEveningSlot`）或 `FROM_WAREHOUSE`（当天早上推回，`slotCode: MORNING_0530_0800`）。推回任务日期为该排班工作日，不是次日岗位时段；统计日期仍按该工作日计算。直接求解接口 `POST /api/schedules/generate` 不保存排班，因此不会生成或推进推电宝轮换。

## 每日岗位需求

`GET /api/schedules/requirements-template` 返回 `anchorWorkDate`、`firstEarlyDownGroup`、`rules` 和 `requirements`。`PUT` 整体替换这四个字段，`rules` 必填；客户端应先读取完整模板，修改后保留未改字段再提交。

`groupRole` 使用 `EARLY_DOWN` / `LATE_DOWN`，按锚点和 `rules.cycle` 解析成当天两个启用组别。`FIRST` 表示参考组，`OTHER` 表示另一组；`REST` 表示休息日。锚点对应周期第 1 天，周期须以工作日开始、休息日结束，长度 2–60 天。

| rules 字段 | 含义 |
| --- | --- |
| `cycle` | 每天 `{earlyGroup, morningPowerGroup, eveningPowerGroup}`；早下组 FIRST/OTHER/REST，推电宝组 FIRST/OTHER/NONE |
| `slots` | 每段 `{code, start, end, dayOffset, onlyBeforeWorkday, remainderPosition}`；HH:mm 时间，end=null 表示航班结束，dayOffset 为 0/1，剩余分配为空字符串/TERMINAL/BOMB |
| `flowNoRepeat`、`supervisorNoRepeat` | 同一人员每天回流/监管最多一次 |
| `supervisorRequired` | 有候机楼人员时必须指定监管 |
| `fixedExclusive` | 固定人员是否仅参加指定时段与岗位 |
| `mealPreferDistinct` | 优先避免打饭重复，含次日早餐；人数不足时可重复 |
| `balanceHistory`、`positionPreferDistinct`、`rolePreferences` | 历史次数均衡、同岗位去重、打饭/回流人选偏好优化 |
| `replacementEnabled`、`replacementPersonId`、`replacementLabel`、`replacementSlots` | 替岗高亮开关、被替岗人员 ID、文字（1–10 字符）、适用时段编码 |
| `powerBankEnabled`、`powerBankOrder` | 推电宝开关及 `{组别编码: [人员ID, ...]}` 有序名单 |
| `powerBankMorningStart`、`powerBankMorningEnd`、`powerBankEveningSlot` | 早上推回时间范围和晚上送仓时段编码 |

岗位需求配置 `slotCode`、`positionCode`、`groupRole`、`minCount`、`maxCount`、`fixedPersonId`。最高人数为 null 不限，最低为 0 允许空缺。自动分配剩余人员时须保留接收岗位，其最高人数也会校验。时段须有唯一编码、按时间顺序排列且不得重叠；旧时段数据库记录保留以支持历史引用。

新排班快照包含 `rules` 和 `rotation`，排班详情接口返回这两项。自动模式的调整和发布按版本快照校验，修改规则仅影响后续生成；无快照旧版本沿用原默认业务规则。需先执行 `migration_v15_configurable_rules.sql`，详见根目录 README。

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

求解按已保存的 rules 和需求人数执行，不再硬编码防爆至少 3 人、打饭固定 3 人或早班候机楼固定 1 人。候机楼不要求单独岗位资格；其他岗位要求有效资格。同一人不能在同一时段兼任正式岗位；固定人员可以跨组到指定岗位，过夜和推电宝是独立附加任务。

回流组别由需求指定。回流不重复、监管必选/不重复以及固定人员排他性由规则开关决定。优化顺序为控制超额人数、打饭去重、历史公平性、同岗位去重、岗位人选偏好；后四项可分别关闭。人员减少时可降低人数或放宽重复限制，系统不会自动忽略最低需求。

`POST /api/schedules/generate-from-personnel` 请求为 `{"scheduleDate":"2026-10-17","requirements":[...]}`，需求元素格式与上节相同；人员、资格和历史次数从数据库读取。`POST /api/schedules/generate-from-database` 请求为 `{"scheduleDate":"2026-10-17"}`，按单日读取需求并保存生成结果。排班页面应使用 `POST /api/schedules/generate-work-period-from-database`，请求格式相同；可传入工作周期的任一工作日，服务端按已配置周期定位连续工作日，一次生成并保存整个工作段。每个日期都会保存为待发布版本；无解、求解超时或业务配置冲突的日期保存为手工模式，整体 `status` 返回 `MANUAL`。后一天的公平性计算会计入本批此前日期的岗位分配。响应包含 `workPeriodStartDate`、`workPeriodEndDate`、整体 `status`、`days`（逐日返回实际需求和生成结果）及 `messages`。传入休息日时返回 `400`。`/generate` 仅返回结果，不写入数据库。

使用生成响应中的 `scheduleId` 调用 `GET /api/schedules/{scheduleId}` 查询排班。待发布排班可通过 `PUT /api/schedules/{scheduleId}` 提交完整岗位分配；人员使用数据库 ID。系统会检查人员当天的组别、相应岗位资格（候机楼无需单独资格）、需求人数、同一时段不可重复上岗，以及监管人员和回流的轮换规则。过夜人员属于岗位分配，可在原时段、原组内按岗位资格调整。请求可另外带 `powerBankDuties`，在推电宝的原组内换人；此辅助任务不检查岗位资格，也不占岗位名额。省略 `powerBankDuties` 表示保留现有推电宝安排。只有 `CANDIDATE` 状态可编辑，其他状态返回 `409`。

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

生成响应字段：`scheduleDate`、`status`、`assignments`、`messages`、`fairness`、`scheduleId`、`version`。每条 `assignment` 包含时段、岗位、组别和 `people` 人员数组；人员字段 `terminalSupervisor` 标记该候机楼时段的监管人员，其他人员为 `false`。`fairness` 提供每个组别/岗位历史加本次次数的最小值和最大值。数据库接口成功保存时返回候选 `scheduleId`、`version`；直接求解时二者为 `null`；数据库生成接口的无解结果改为保存手工草稿并返回 `MANUAL`。

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

## 换廖标记调整

`GET /api/schedules/{scheduleId}` 的 `bombReplacements` 字段保存各时段的“换廖”人选，例如 `{"DAY_0900_1130":"12","DAY_1430_1730":"8"}`。编辑接口 `PUT /api/schedules/{scheduleId}` 可随原有 `assignments` 一起提交此字段。只接受 09:00–11:30 和 14:30–17:30，选中人员必须在该时段候机楼名单里，且同组防爆名单中包含廖家史。标记可以与监管重叠，不另占岗位、不检查防爆资格、不改变岗位统计。

标记和岗位调整在同一事务中保存到当前版本的配置快照。刷新、发布、历史详情与恢复版本会保留已保存的标记；无需数据库迁移。省略字段时保留仍然有效的旧标记，岗位变动导致失效的标记会被清除；传入空对象表示清空已保存标记。没有有效标记时前端沿用公平性自动选择，再次保存调整时会记录选定人选。

## 打饭人数规则

打饭规则升级为每次 3 人（含早餐）需先执行 `migration_v13_meal_three_people.sql`。打饭需求的 `minCount` 和 `maxCount` 都必须为 `3`；标准模板、日期例外和直接生成接口使用同一校验。已有排班仍使用保存时的需求快照，新规则通过重新生成生效。

## 固定人员规则

标准模板和日期例外的岗位需求都支持可选字段 **fixedPersonId**。填写人员 ID 后，该人员当天只会安排在所有引用该 ID 的固定时段和岗位中；同一人可在不同时间段分别固定，但不能在同一时段固定到多个岗位。固定人员可以属于与规则组别不同的组，仍须在排班日期有效并具备岗位资格。省略字段或传 null 表示由求解器分配。

人员 ID 使用 GET 人员接口返回的字符串 ID。修改已生成的候选排班时，固定人员约束会继续生效；不能把该人员移到其他时段或岗位。

## 错误处理

无效日期、必填字段缺失、未知编码或人数范围错误通常返回 `400`；人员 ID 不存在返回 `404`。错误响应使用 Spring 默认格式，目前没有单独承诺固定 JSON 错误结构。

### 自动失败后的手工排班

数据库保存型生成接口在自动规则无法满足时返回 HTTP 200、`status: MANUAL` 和已保存的 `scheduleId`；周期内成功的日期仍保留自动模式。推电宝轮换失败时保留已生成的正式岗位，并将本批次切换为手工模式。数据库或网络故障仍返回错误，不能凭空创建草稿。

详情接口增加 `manualMode`、`manualReason`，由服务端写入版本快照，刷新与恢复历史版本均保留。手工草稿的 PUT 和发布接口不执行人数、分组、资格、启用状态、固定人员、重复、监管必选、换岗或剩余人员分配等业务校验；管理员可自行增减、跨组或重复安排，推电宝也可清空或重新选人。仍检查人员/岗位/时段/组别是否存在、数据结构是否合法，防止保存无效引用。手工模式不自动补人，保存后可发布并正常计入统计、导出 PNG。正常生成的自动模式继续执行原规则。无需新增数据库迁移。
