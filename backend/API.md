# ShiftBalance API

当前后端 HTTP/JSON 接口说明。默认地址为 `http://localhost:8080`；接口调试页面为 `/doc.html`，OpenAPI JSON 为 `/v3/api-docs`。启动和数据库隧道配置见 [README.md](README.md)。除特别说明外，请求和响应均使用 JSON，日期格式为 `YYYY-MM-DD`。

## 接口一览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/groups` | 查询启用的组别 |
| GET | `/api/positions` | 查询启用的岗位 |
| GET | `/api/people?asOf=日期` | 查询人员、当日有效组别/资格和已发布岗位次数 |
| POST | `/api/people` | 新增人员及初始组别、岗位资格 |
| PUT | `/api/people/{id}?asOf=日期` | 修改人员姓名、启用状态、备注，并可选修改组别和岗位资格 |
| GET | `/api/schedules/time-slots` | 查询固定时段 |
| GET | `/api/schedules/requirements-template` | 查询标准岗位需求模板和轮班锚点 |
| PUT | `/api/schedules/requirements-template` | 一次性保存标准需求模板和轮班锚点 |
| GET | `/api/schedules/{date}/requirements` | 查询指定日期岗位需求 |
| PUT | `/api/schedules/{date}/requirements` | 整体覆盖指定日期岗位需求 |
| DELETE | `/api/schedules/{date}/requirements` | 清除指定日期覆盖并恢复标准模板 |
| POST | `/api/schedules/generate` | 用请求中的人员和需求直接求解，不写入数据库 |
| POST | `/api/schedules/generate-from-personnel` | 从数据库读取人员，使用请求中的需求并保存候选排班 |
| POST | `/api/schedules/generate-from-database` | 从数据库读取人员和指定日期需求并保存候选排班 |
| POST | `/api/rotation/next` | 计算一个工作日的早下/晚下组及下一轮状态 |

## 组别、岗位与时段

`GET /api/groups` 和 `GET /api/positions` 返回数组，元素分别为 `{ "code", "name" }`。初始化岗位编码为：`MEAL` 打饭、`OVERNIGHT` 过夜、`BOMB` 防爆、`LEADER` 负责人、`TERMINAL` 候机楼、`MEZZANINE` 夹层、`FLOW` 回流/拉包。排班需求中的组别、岗位编码应来自启用数据。

`GET /api/schedules/time-slots` 返回 `{ "code", "sequence", "label", "dayOffset" }` 数组。固定编码为 `DAY_0900_1130`、`DAY_1130_1430`、`DAY_1430_1730`、`DAY_1730_1930`、`NIGHT_UNTIL_END`、`NEXT_DAY_0530_0800`、`NEXT_DAY_0800_0900`。`dayOffset: 1` 表示次日时段；`NIGHT_UNTIL_END` 表示 19:30 至航班结束。

## 人员

`GET /api/people` 的 `asOf` 可选，缺省为中国时区当天。返回元素包含 `id`、`name`、`groupCode`、`enabled`、`remark`、`qualifiedPositionCodes`、`historicalCounts`。资格和组别按 `asOf` 查有效记录；`historicalCounts` 是该人员所有已发布排班的岗位次数，不含候选排班。

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

## 每日岗位需求

通常只需配置一次标准模板。模板中的 `groupRole` 使用 `EARLY_DOWN` 或 `LATE_DOWN`；系统根据轮班锚点和二上二休日历，将其解析为当天的 A/B 组。锚点日期是一个周期的第一个工作日；该日及次日工作，随后两天休息。轮班示例：锚点为 `2026-10-12` 且首日早下组为 A 时，工作日早下组依次为 12 日 A、13 日 B、16 日 B、17 日 A。

`PUT /api/schedules/requirements-template` 整体替换标准模板。下例仅展示格式，实际模板应包含所有固定岗位和时段。若第二个工作日早下后，休息日首日需要 08:00–09:00 交接，在模板中配置 `NEXT_DAY_0800_0900` 且 `groupRole` 设为 `EARLY_DOWN`；系统只会在每个两天工作段的第二天带出这一行。`NEXT_DAY_0530_0800` 则按模板正常解析为次日早下组岗位：

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

求解只从启用、组别匹配且具备岗位资格的人员中排班；同一人员同一时段不能兼任多个岗位。优化顺序为减少总上岗人数、平衡同组同岗位的历史加本次次数、尽量避免同一天重复同一岗位。需求按每个时段分别指定组别，不会跨组混排。

`POST /api/schedules/generate-from-personnel` 请求为 `{"scheduleDate":"2026-10-17","requirements":[...]}`，需求元素格式与上节相同；人员、资格和历史次数从数据库读取。`POST /api/schedules/generate-from-database` 请求为 `{"scheduleDate":"2026-10-17"}`，优先读取该日期的手动覆盖，否则自动套用标准模板；未配置模板时返回 `400`。这两个接口在得到可行方案后保存 `CANDIDATE` 候选排班；`/generate` 仅返回结果，不写入数据库。当前没有发布候选排班的 API。

生成响应字段：`scheduleDate`、`status`、`assignments`、`messages`、`fairness`、`scheduleId`、`version`。每条 `assignment` 包含时段、岗位、组别和 `people` 人员数组；`fairness` 提供每个组别/岗位历史加本次次数的最小值和最大值。数据库接口成功保存时返回候选 `scheduleId`、`version`；直接求解或不可行时二者为 `null`。

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

## 错误处理

无效日期、必填字段缺失、未知编码或人数范围错误通常返回 `400`；人员 ID 不存在返回 `404`。错误响应使用 Spring 默认格式，目前没有单独承诺固定 JSON 错误结构。
