import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, DatePicker, Empty, Input, InputNumber, Select, Skeleton, Space, Statistic, Table, Tabs, Tag, Tooltip, type TableColumnsType } from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import type { ApiRequest } from './ManagementPages'
import './StatisticsPage.css'

type Group = { id: number; code: string; name: string }
type Position = { id: number; code: string; name: string }
type PersonPositionAssignment = {
  personId: number
  personName: string
  positionId: number
  positionCode: string
  positionName: string
  groupId: number
  groupCode: string
  assignmentCount: number
  fairnessCount: number
  eligibleSlotCount: number
}
type PositionDistribution = {
  positionId: number
  positionCode: string
  positionName: string
  groupId: number
  groupCode: string
  eligiblePeople: number
  assignedPeople: number
  assignmentCount: number
  fairnessCount: number
  minFairnessCount: number
  maxFairnessCount: number
  fairnessGap: number
  averageFairnessCount: number
}
type HistoryResponse = { startDate: string | null; endDate: string | null; assignments: PersonPositionAssignment[] }
type DistributionResponse = { startDate: string | null; endDate: string | null; positions: PositionDistribution[] }
type ScheduleStatistics = {
  scheduleId: number
  scheduleDate: string
  status: string
  assignmentCount: number
  personPositionAssignments: PersonPositionAssignment[]
  positionDistributions: PositionDistribution[]
}
type Props = { apiBase: string; apiRequest: ApiRequest }

const countFormat = new Intl.NumberFormat('zh-CN')
const count = (value: number) => countFormat.format(value)
const statusName = (status: string) => status === 'PUBLISHED' ? '已发布' : status === 'CANDIDATE' ? '待发布' : status

function buildQuery(startDate?: string, endDate?: string, groupId?: number, positionId?: number) {
  const params = new URLSearchParams()
  if (startDate) params.set('startDate', startDate)
  if (endDate) params.set('endDate', endDate)
  if (groupId) params.set('groupId', String(groupId))
  if (positionId) params.set('positionId', String(positionId))
  const query = params.toString()
  return query ? `?${query}` : ''
}

function personColumns(positionMap: Map<number, string>, groupMap: Map<number, string>): TableColumnsType<PersonPositionAssignment> {
  return [
    { title: '人员', dataIndex: 'personName', key: 'personName', fixed: 'left', width: 190, sorter: (a, b) => a.personName.localeCompare(b.personName, 'zh-CN') },
    { title: '组别', dataIndex: 'groupCode', key: 'groupCode', width: 110, render: (code: string, row) => groupMap.get(row.groupId) ?? `${code} 组` },
    { title: '岗位', dataIndex: 'positionName', key: 'positionName', width: 150, render: (name: string, row) => positionMap.get(row.positionId) ?? name },
    { title: '分配次数', dataIndex: 'assignmentCount', key: 'assignmentCount', width: 120, align: 'right', sorter: (a, b) => a.assignmentCount - b.assignmentCount, render: (value: number) => <span className="stat-number">{count(value)} 次</span> },
    { title: <Tooltip title="仅统计当时属于该组、具备岗位资格且排班快照存在该岗位需求的分配">公平次数</Tooltip>, dataIndex: 'fairnessCount', key: 'fairnessCount', width: 120, align: 'right', sorter: (a, b) => a.fairnessCount - b.fairnessCount, render: (value: number) => <span className="stat-number">{count(value)} 次</span> },
    { title: '符合条件时段', dataIndex: 'eligibleSlotCount', key: 'eligibleSlotCount', width: 140, align: 'right', sorter: (a, b) => a.eligibleSlotCount - b.eligibleSlotCount, render: (value: number) => <span className="stat-number">{count(value)} 段</span> },
  ]
}

function positionColumns(groupMap: Map<number, string>): TableColumnsType<PositionDistribution> {
  return [
    { title: '组别', dataIndex: 'groupCode', key: 'groupCode', width: 120, render: (code: string, row) => groupMap.get(row.groupId) ?? `${code} 组` },
    { title: '岗位', dataIndex: 'positionName', key: 'positionName', width: 180 },
    { title: '符合资格人数', dataIndex: 'eligiblePeople', key: 'eligiblePeople', width: 130, align: 'right', render: (value: number) => <span className="stat-number">{count(value)} 人</span> },
    { title: '已排人数', dataIndex: 'assignedPeople', key: 'assignedPeople', width: 130, align: 'right', render: (value: number) => <span className="stat-number">{count(value)} 人</span> },
    { title: '分配总次数', dataIndex: 'assignmentCount', key: 'assignmentCount', width: 130, align: 'right', sorter: (a, b) => a.assignmentCount - b.assignmentCount, render: (value: number) => <span className="stat-number">{count(value)} 次</span> },
    { title: '公平次数范围', key: 'range', width: 180, align: 'right', render: (_, row) => <span className="stat-number">{count(row.minFairnessCount)}–{count(row.maxFairnessCount)} 次</span> },
    { title: '次数差', dataIndex: 'fairnessGap', key: 'fairnessGap', width: 110, align: 'right', sorter: (a, b) => a.fairnessGap - b.fairnessGap, render: (value: number) => <span className={`stat-number ${value > 0 ? 'gap-value' : ''}`}>{count(value)} 次</span> },
  ]
}

export default function StatisticsPage({ apiBase, apiRequest }: Props) {
  const [groups, setGroups] = useState<Group[]>([])
  const [positions, setPositions] = useState<Position[]>([])
  const [assignments, setAssignments] = useState<PersonPositionAssignment[]>([])
  const [distributions, setDistributions] = useState<PositionDistribution[]>([])
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [groupId, setGroupId] = useState<number>()
  const [positionId, setPositionId] = useState<number>()
  const [search, setSearch] = useState('')
  const [tab, setTab] = useState('history')
  const [historyLoad, setHistoryLoad] = useState<{ requestKey: string; errors: string[] }>({ requestKey: '', errors: [] })
  const [reloadKey, setReloadKey] = useState(0)
  const [scheduleIdInput, setScheduleIdInput] = useState<number | null>(null)
  const [schedule, setSchedule] = useState<ScheduleStatistics | null>(null)
  const [scheduleLoading, setScheduleLoading] = useState(false)
  const [scheduleError, setScheduleError] = useState('')

  const startDate = dateRange?.[0]?.format('YYYY-MM-DD')
  const endDate = dateRange?.[1]?.format('YYYY-MM-DD')
  const requestKey = JSON.stringify([apiBase, startDate, endDate, groupId, positionId, reloadKey])
  const loading = historyLoad.requestKey !== requestKey
  const errors = loading ? [] : historyLoad.errors

  useEffect(() => {
    let active = true
    const query = buildQuery(startDate, endDate, groupId, positionId)
    void Promise.allSettled([
      apiRequest<Group[]>(apiBase, '/api/groups'),
      apiRequest<Position[]>(apiBase, '/api/positions'),
      apiRequest<HistoryResponse>(apiBase, `/api/v1/statistics/person-position-assignments${query}`),
      apiRequest<DistributionResponse>(apiBase, `/api/v1/statistics/position-distribution${query}`),
    ]).then((results) => {
      if (!active) return
      const nextErrors: string[] = []
      if (results[0].status === 'fulfilled') setGroups(results[0].value)
      else nextErrors.push(`组别：${results[0].reason instanceof Error ? results[0].reason.message : '读取失败'}`)
      if (results[1].status === 'fulfilled') setPositions(results[1].value)
      else nextErrors.push(`岗位：${results[1].reason instanceof Error ? results[1].reason.message : '读取失败'}`)
      if (results[2].status === 'fulfilled') setAssignments(results[2].value.assignments)
      else nextErrors.push(`人员岗位统计：${results[2].reason instanceof Error ? results[2].reason.message : '读取失败'}`)
      if (results[3].status === 'fulfilled') setDistributions(results[3].value.positions)
      else nextErrors.push(`岗位分布：${results[3].reason instanceof Error ? results[3].reason.message : '读取失败'}`)
      setHistoryLoad({ requestKey, errors: nextErrors })
    })
    return () => { active = false }
  }, [apiBase, apiRequest, startDate, endDate, groupId, positionId, reloadKey, requestKey])

  const groupMap = useMemo(() => new Map(groups.map((group) => [group.id, group.name])), [groups])
  const positionMap = useMemo(() => new Map(positions.map((position) => [position.id, position.name])), [positions])
  const filteredAssignments = useMemo(() => {
    const query = search.trim().toLocaleLowerCase()
    return assignments.filter((row) => !query || row.personName.toLocaleLowerCase().includes(query))
  }, [assignments, search])
  const sortedDistributions = useMemo(() => [...distributions].sort((a, b) =>
    b.fairnessGap - a.fairnessGap || a.groupCode.localeCompare(b.groupCode) || a.positionName.localeCompare(b.positionName, 'zh-CN')),
  [distributions])
  const totalAssignments = assignments.reduce((sum, row) => sum + row.assignmentCount, 0)
  const peopleCount = new Set(assignments.map((row) => row.personId)).size
  const largestGap = sortedDistributions[0]
  const headline = tab === 'schedule'
    ? '核对单次排班的岗位覆盖与人员分配；待发布方案不会进入历史统计。'
    : loading
      ? '正在汇总已发布排班中的岗位分配。'
      : largestGap?.fairnessGap
        ? `${largestGap.groupCode} 组${largestGap.positionName}公平次数差最大，为 ${count(largestGap.fairnessGap)} 次（${count(largestGap.minFairnessCount)}–${count(largestGap.maxFairnessCount)} 次）。`
        : distributions.length
          ? '当前范围内可比较的组别与岗位，公平次数差均为 0。'
          : '当前范围内暂无已发布排班；生成并调整后，请在排班管理中发布。'

  const loadSchedule = async () => {
    if (!scheduleIdInput || scheduleIdInput < 1 || !Number.isInteger(scheduleIdInput)) {
      setScheduleError('请输入有效的排班 ID。可在排班管理页的排班表标题旁查看。')
      setSchedule(null)
      return
    }
    setScheduleLoading(true)
    setScheduleError('')
    setSchedule(null)
    try {
      const result = await apiRequest<ScheduleStatistics>(apiBase, `/api/v1/schedules/${scheduleIdInput}/statistics`)
      setSchedule(result)
    } catch (cause) {
      setScheduleError(cause instanceof Error ? cause.message : '读取该排班统计失败')
    } finally {
      setScheduleLoading(false)
    }
  }

  const schedulePersonRows = schedule?.personPositionAssignments ?? []
  const schedulePositionRows = schedule?.positionDistributions ?? []

  return <section className="statistics-page">
    <header className="page-heading management-heading statistics-heading">
      <div><h1>统计分析</h1><p className="page-subtitle">{headline}</p></div>
      {tab === 'history' && <Button icon={<ReloadOutlined />} onClick={() => setReloadKey((value) => value + 1)} loading={loading}>刷新统计</Button>}
    </header>

    {tab === 'history' && <div className="statistics-toolbar">
      <div className="statistics-scope"><span className="scope-label">历史范围</span><Tag>{startDate || endDate ? `${startDate ?? '最早'} 至 ${endDate ?? '最近'}` : '全部历史'}</Tag><span className="scope-note">仅统计每个工作日最新发布版本；待发布和已取消排班不计入</span></div>
      <Space wrap size={8} className="statistics-filters">
        <DatePicker.RangePicker
          aria-label="统计日期范围"
          allowEmpty={[true, true]}
          value={dateRange}
          onChange={(value) => setDateRange(value as [Dayjs | null, Dayjs | null] | null)}
          placeholder={['开始日期', '结束日期']}
          disabledDate={(date) => Boolean(dateRange?.[0] && dateRange?.[1] === null && date.isBefore(dateRange[0], 'day'))}
        />
        <Select allowClear placeholder="全部组别" value={groupId} onChange={setGroupId} options={groups.map((group) => ({ value: group.id, label: group.name }))} style={{ width: 130 }} />
        <Select allowClear placeholder="全部岗位" value={positionId} onChange={setPositionId} options={positions.map((position) => ({ value: position.id, label: position.name }))} style={{ width: 150 }} />
      </Space>
    </div>}

    <Tabs className="statistics-tabs" activeKey={tab} onChange={setTab} items={[
      { key: 'history', label: '历史岗位统计' },
      { key: 'schedule', label: '单次排班核对' },
    ]} />

    {tab === 'history' ? <div className="statistics-history">
      {errors.length > 0 && <Alert showIcon type="error" className="statistics-error" message="部分统计数据读取失败" description={<Space direction="vertical" size={2}>{errors.map((error) => <span key={error}>{error}</span>)}</Space>} action={<Button size="small" onClick={() => setReloadKey((value) => value + 1)}>重试</Button>} />}

      <Card className="statistics-kpis" styles={{ body: { padding: 0 } }}>
        <div className="statistics-kpi"><span>岗位分配记录</span><Statistic value={totalAssignments} formatter={(value) => count(Number(value))} suffix="次" /></div>
        <div className="statistics-kpi"><span>参与人员</span><Statistic value={peopleCount} formatter={(value) => count(Number(value))} suffix="人" /></div>
        <div className="statistics-kpi"><span>可比较的组别岗位</span><Statistic value={distributions.length} formatter={(value) => count(Number(value))} suffix="项" /></div>
        <div className="statistics-kpi"><span>最大公平次数差</span><Statistic value={largestGap?.fairnessGap ?? 0} formatter={(value) => count(Number(value))} suffix="次" /></div>
      </Card>

      <Card className="statistics-card" title="组别 × 岗位公平性" extra={<span className="statistics-card-note">次数差 = 最高公平次数 − 最低公平次数</span>}>
        {loading ? <Skeleton active paragraph={{ rows: 4 }} /> : sortedDistributions.length ? <div className="fairness-matrix" role="table" aria-label="组别岗位公平次数差">
          <div className="fairness-matrix-head" role="row"><span>组别 / 岗位</span><span>符合资格</span><span>已安排</span><span>公平次数范围</span><span>次数差</span></div>
          {sortedDistributions.map((row) => {
            const gapWidth = largestGap?.fairnessGap ? `${row.fairnessGap / largestGap.fairnessGap * 100}%` : '0%'
            return <div className="fairness-matrix-row" role="row" key={`${row.groupId}-${row.positionId}`}>
              <div className="matrix-role"><span>{groupMap.get(row.groupId) ?? `${row.groupCode} 组`}</span><strong>{positionMap.get(row.positionId) ?? row.positionName}</strong></div>
              <span className="stat-number matrix-numeric">{count(row.eligiblePeople)} 人</span>
              <span className="stat-number matrix-numeric">{count(row.assignedPeople)} 人</span>
              <div className="matrix-range"><span className="stat-number">{count(row.minFairnessCount)}–{count(row.maxFairnessCount)} 次</span><span className="gap-track"><span style={{ width: gapWidth }} /></span></div>
              <strong className={`stat-number matrix-gap ${row.fairnessGap > 0 ? 'gap-value' : ''}`}>{count(row.fairnessGap)} 次</strong>
            </div>
          })}
        </div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选范围内没有可比较的岗位分配" />}
      </Card>

      <Card className="statistics-card statistics-detail-card" title="人员岗位次数" extra={<span className="statistics-card-note">每个时段的一次安排分别计数</span>}>
        <div className="statistics-table-tools">
          <span>{loading ? '正在汇总…' : `共 ${count(filteredAssignments.length)} 条人员岗位记录`}</span>
          <Input allowClear prefix={<SearchOutlined />} placeholder="搜索人员" value={search} onChange={(event) => setSearch(event.target.value)} aria-label="搜索人员岗位统计" />
        </div>
        {loading ? <Skeleton active paragraph={{ rows: 5 }} /> : <Table<PersonPositionAssignment>
          rowKey={(row) => `${row.personId}-${row.positionId}-${row.groupId}`}
          size="small"
          columns={personColumns(positionMap, groupMap)}
          dataSource={filteredAssignments}
          pagination={{ pageSize: 12, showSizeChanger: false, showTotal: (total) => `共 ${count(total)} 条` }}
          scroll={{ x: 850 }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={search ? '没有匹配的人员' : '所选范围内没有人员岗位统计'} /> }}
        />}
      </Card>
      <p className="statistics-footnote">“分配次数”按每个工作日最新发布版本中的每条人员/岗位/时段记录计数；待发布版本、已取消版本和旧发布版本不重复计入。“公平次数”仅计入人员当时属于该组、具备岗位资格且排班需求快照包含该岗位的分配。候机楼岗位不要求单独资格。系统没有考勤记录，因此这些数字表示最终发布的排班安排，不表示实际到岗。</p>
    </div> : <div className="statistics-schedule-view">
      <Card className="statistics-card" title="查询单次排班" extra={<span className="statistics-card-note">待发布方案可核对；历史统计只计已发布</span>}>
        <div className="schedule-lookup">
          <div><label htmlFor="schedule-stat-id">排班 ID</label><small>在排班管理页标题旁查看，例如 ID 12。</small></div>
          <Space.Compact className="schedule-lookup-controls"><InputNumber id="schedule-stat-id" min={1} precision={0} value={scheduleIdInput} onChange={(value) => setScheduleIdInput(typeof value === 'number' ? value : null)} placeholder="输入排班 ID" onPressEnter={() => void loadSchedule()} /><Button type="primary" onClick={() => void loadSchedule()} loading={scheduleLoading}>查询统计</Button></Space.Compact>
        </div>
        {scheduleError && <Alert className="statistics-error" showIcon type="error" message="无法读取该排班" description={scheduleError} />}
      </Card>

      {scheduleLoading ? <Card className="statistics-card"><Skeleton active paragraph={{ rows: 5 }} /></Card> : schedule ? <>
        <Card className="statistics-kpis schedule-kpis" styles={{ body: { padding: 0 } }}>
          <div className="statistics-kpi"><span>排班日期</span><strong className="schedule-date-value">{dayjs(schedule.scheduleDate).format('YYYY年M月D日')}</strong></div>
          <div className="statistics-kpi"><span>排班状态</span><Tag className="schedule-status">{statusName(schedule.status)}</Tag></div>
          <div className="statistics-kpi"><span>岗位分配记录</span><Statistic value={schedule.assignmentCount} formatter={(value) => count(Number(value))} suffix="次" /></div>
          <div className="statistics-kpi"><span>组别岗位</span><Statistic value={schedulePositionRows.length} formatter={(value) => count(Number(value))} suffix="项" /></div>
        </Card>
        <Card className="statistics-card" title="本次岗位分布">
          <Table<PositionDistribution> rowKey={(row) => `${row.groupId}-${row.positionId}`} size="small" columns={positionColumns(groupMap)} dataSource={schedulePositionRows} pagination={false} scroll={{ x: 850 }} locale={{ emptyText: <Empty description="该排班没有岗位分配记录" /> }} />
        </Card>
        <Card className="statistics-card" title="本次人员分配">
          <Table<PersonPositionAssignment> rowKey={(row) => `${row.personId}-${row.positionId}-${row.groupId}`} size="small" columns={personColumns(positionMap, groupMap)} dataSource={schedulePersonRows} pagination={{ pageSize: 12, showSizeChanger: false }} scroll={{ x: 850 }} locale={{ emptyText: <Empty description="该排班没有人员分配记录" /> }} />
        </Card>
      </> : <Card className="statistics-card statistics-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="输入排班 ID 后查看岗位覆盖和人员分配" /></Card>}
    </div>}
  </section>
}
