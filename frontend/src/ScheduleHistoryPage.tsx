import { useEffect, useMemo, useState } from 'react'
import { App as AntApp, Alert, Button, DatePicker, Drawer, Empty, Input, Space, Spin, Table, Tabs, Tag, type TableColumnsType } from 'antd'
import { DeleteOutlined, EyeOutlined, ReloadOutlined, RollbackOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import './ScheduleHistoryPage.css'

type Requirement = {
  slotCode: string
  positionCode: string
  positionName: string
  groupCode: string
  minCount: number
  maxCount: number | null
  fixedPersonId: string | null
}
type HistoryEntry = {
  scheduleId: number
  scheduleDate: string
  status: 'CANDIDATE' | 'PUBLISHED' | 'CANCELLED' | 'DRAFT'
  version: number
  createdAt: string
  publishedAt: string | null
  assignmentCount: number
  peopleCount: number
  powerBankDutyCount: number
}
type ScheduleDetail = {
  scheduleId: number
  scheduleDate: string
  status: string
  version: number
  requirements: Requirement[]
  assignments: Array<{
    slotCode: string
    slotLabel: string
    slotSequence: number
    positionCode: string
    positionName: string
    groupCode: string
    fixedPersonId: string | null
    people: Array<{ id: string; name: string; terminalSupervisor: boolean }>
  }>
  powerBankDuties: Array<{
    direction: 'TO_WAREHOUSE' | 'FROM_WAREHOUSE'
    slotCode: string
    groupCode: string
    personId: string
    personName: string
  }>
}
type ApiRequest = <T>(base: string, path: string, init?: RequestInit) => Promise<T>

type Props = {
  apiBase: string
  apiRequest: ApiRequest
  onOpenSchedule: (date: string, scheduleId: number, requirements: Requirement[]) => void
  onScheduleDeleted: (entry: HistoryEntry) => void
}

function statusTag(entry: HistoryEntry) {
  if (entry.status === 'PUBLISHED') return <Tag color="success">已发布</Tag>
  if (entry.status === 'CANDIDATE') return <Tag color="processing">待发布</Tag>
  if (entry.status === 'DRAFT') return <Tag>草稿</Tag>
  if (entry.publishedAt) return <Tag>已发布后替代</Tag>
  return <Tag>已替代</Tag>
}

function dateTime(value: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'
}

function scheduleDateLabel(value: string) {
  return new Date(`${value}T00:00:00`).toLocaleDateString('zh-CN', {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
  })
}

export default function ScheduleHistoryPage({ apiBase, apiRequest, onOpenSchedule, onScheduleDeleted }: Props) {
  const { modal, message } = AntApp.useApp()
  const [entries, setEntries] = useState<HistoryEntry[]>([])
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [activeTab, setActiveTab] = useState('all')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)
  const [query, setQuery] = useState('')
  const [detailEntry, setDetailEntry] = useState<HistoryEntry | null>(null)
  const [detail, setDetail] = useState<ScheduleDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState('')

  const startDate = dateRange?.[0]?.format('YYYY-MM-DD')
  const endDate = dateRange?.[1]?.format('YYYY-MM-DD')
  useEffect(() => {
    let active = true
    setLoading(true)
    setLoadError('')
    const params = new URLSearchParams()
    if (startDate) params.set('startDate', startDate)
    if (endDate) params.set('endDate', endDate)
    void apiRequest<HistoryEntry[]>(apiBase, `/api/schedules/history${params.size ? `?${params}` : ''}`)
      .then((result) => { if (active) setEntries(result) })
      .catch((error) => { if (active) setLoadError(error instanceof Error ? error.message : '历史记录读取失败') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [apiBase, apiRequest, endDate, reloadKey, startDate])

  useEffect(() => {
    if (!detailEntry) return
    let active = true
    setDetail(null)
    setDetailError('')
    setDetailLoading(true)
    void apiRequest<ScheduleDetail>(apiBase, `/api/schedules/${detailEntry.scheduleId}`)
      .then((result) => { if (active) setDetail(result) })
      .catch((error) => { if (active) setDetailError(error instanceof Error ? error.message : '排班详情读取失败') })
      .finally(() => { if (active) setDetailLoading(false) })
    return () => { active = false }
  }, [apiBase, apiRequest, detailEntry?.scheduleId])

  const filtered = useMemo(() => entries.filter((entry) => {
    const tabMatches = activeTab === 'all'
      || (activeTab === 'candidate' && entry.status === 'CANDIDATE')
      || (activeTab === 'published' && entry.status === 'PUBLISHED')
      || (activeTab === 'replaced' && entry.status === 'CANCELLED')
    const queryMatches = !query || `${entry.scheduleDate} v${entry.version} ${entry.scheduleId}`.toLowerCase().includes(query.trim().toLowerCase())
    return tabMatches && queryMatches
  }), [activeTab, entries, query])

  const counts = useMemo(() => ({
    all: entries.length,
    candidate: entries.filter((entry) => entry.status === 'CANDIDATE').length,
    published: entries.filter((entry) => entry.status === 'PUBLISHED').length,
    replaced: entries.filter((entry) => entry.status === 'CANCELLED').length,
  }), [entries])

  const restore = (entry: HistoryEntry) => {
    modal.confirm({
      title: `恢复 ${entry.scheduleDate} v${entry.version}`,
      content: '系统会复制该版本为一个新的待发布版本，原记录保留。若当天已有待发布版本，它会转为已替代；当前已发布排班在新版本发布前不变。',
      okText: '恢复为待发布',
      cancelText: '取消',
      onOk: async () => {
        try {
          const restored = await apiRequest<ScheduleDetail>(apiBase, `/api/schedules/${entry.scheduleId}/restore`, { method: 'POST' })
          message.success(`已恢复为 v${restored.version} 待发布版本。`)
          onOpenSchedule(restored.scheduleDate, restored.scheduleId, restored.requirements)
          setDetailEntry(null)
          setReloadKey((value) => value + 1)
        } catch (error) {
          message.error(error instanceof Error ? error.message : '恢复排班失败')
          throw error
        }
      },
    })
  }

  const remove = (entry: HistoryEntry) => {
    const impact = entry.status === 'PUBLISHED'
      ? '该版本当前已发布。删除会永久移除排班和岗位分配，并从历史统计与公平性计算中移除。'
      : entry.status === 'CANDIDATE'
        ? '该版本仍待发布。删除后当天将不再保留这份候选排班。'
        : '该版本已被替代。删除会永久移除排班及其分配明细。'
    modal.confirm({
      title: `删除 ${entry.scheduleDate} v${entry.version}？`,
      content: impact,
      okText: '永久删除',
      okButtonProps: { danger: true },
      cancelText: '保留记录',
      onOk: async () => {
        try {
          await apiRequest<void>(apiBase, `/api/schedules/${entry.scheduleId}`, { method: 'DELETE' })
          message.success(`已删除 ${entry.scheduleDate} v${entry.version}。`)
          onScheduleDeleted(entry)
          setEntries((previous) => previous.filter((item) => item.scheduleId !== entry.scheduleId))
          if (detailEntry?.scheduleId === entry.scheduleId) setDetailEntry(null)
        } catch (error) {
          message.error(error instanceof Error ? error.message : '删除历史记录失败')
          throw error
        }
      },
    })
  }

  const columns: TableColumnsType<HistoryEntry> = [
    {
      title: '排班日期 / 版本',
      key: 'identity',
      width: 220,
      sorter: (a, b) => a.scheduleDate.localeCompare(b.scheduleDate) || a.version - b.version,
      render: (_, entry) => <div className="history-identity"><strong>{scheduleDateLabel(entry.scheduleDate)}</strong><span>版本 {entry.version} · ID {entry.scheduleId}</span></div>,
    },
    { title: '状态', key: 'status', width: 150, render: (_, entry) => statusTag(entry) },
    {
      title: '生成时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 175,
      render: (value: string) => <span className="history-time">{dateTime(value)}</span>,
    },
    {
      title: '分配概况',
      key: 'summary',
      width: 210,
      render: (_, entry) => <span>{entry.assignmentCount} 次岗位分配 · {entry.peopleCount} 人{entry.powerBankDutyCount ? ` · 推电宝 ${entry.powerBankDutyCount} 次` : ''}</span>,
    },
    {
      title: '操作',
      key: 'actions',
      align: 'right',
      width: 300,
      render: (_, entry) => <Space size={4}>
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetailEntry(entry)}>查看</Button>
        {entry.status !== 'CANDIDATE' && <Button type="link" size="small" icon={<RollbackOutlined />} onClick={() => restore(entry)}>恢复为待发布</Button>}
        <Button type="link" danger size="small" icon={<DeleteOutlined />} onClick={() => remove(entry)}>删除</Button>
      </Space>,
    },
  ]

  const detailColumns: TableColumnsType<ScheduleDetail['assignments'][number]> = [
    { title: '时段', dataIndex: 'slotLabel', key: 'slotLabel', width: 150, sorter: (a, b) => a.slotSequence - b.slotSequence },
    { title: '岗位', dataIndex: 'positionName', key: 'positionName', width: 150 },
    { title: '组别', dataIndex: 'groupCode', key: 'groupCode', width: 90, render: (value: string) => `${value}组` },
    {
      title: '安排人员',
      dataIndex: 'people',
      key: 'people',
      render: (people: ScheduleDetail['assignments'][number]['people']) => people.length
        ? <div className="history-person-list">{people.map((person) => <Tag key={person.id} color={person.terminalSupervisor ? 'blue' : undefined}>{person.name}{person.terminalSupervisor ? ' · 负责人' : ''}</Tag>)}</div>
        : <span className="history-empty-value">—</span>,
    },
  ]

  const tabItems = [
    { key: 'all', label: `全部版本 ${counts.all}` },
    { key: 'candidate', label: `待发布 ${counts.candidate}` },
    { key: 'published', label: `已发布 ${counts.published}` },
    { key: 'replaced', label: `已替代 ${counts.replaced}` },
  ]

  return <div className="schedule-history-page">
    <section className="page-heading history-page-heading">
      <div>
        <h1>排班历史</h1>
        <p className="page-subtitle">回看每次生成的版本；可将旧版本恢复为新的待发布排班，发布后才会进入历史统计。</p>
      </div>
      <div className="heading-actions">
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => setReloadKey((value) => value + 1)}>刷新记录</Button>
      </div>
    </section>

    {loadError && <Alert className="history-error" type="error" showIcon message="历史记录读取失败" description={loadError} action={<Button size="small" onClick={() => setReloadKey((value) => value + 1)}>重试</Button>} />}

    <section className="history-list-surface surface">
      <div className="history-list-heading">
        <div><h2>版本记录</h2><p>已生成、已发布及被后续版本替代的记录</p></div>
        <span className="history-total">{filtered.length} 个版本</span>
      </div>
      <Tabs activeKey={activeTab} items={tabItems} onChange={setActiveTab} className="history-tabs" />
      <div className="history-toolbar">
        <DatePicker.RangePicker
          value={dateRange}
          onChange={(value) => setDateRange(value as [Dayjs | null, Dayjs | null] | null)}
          allowClear
          placeholder={['开始日期', '结束日期']}
        />
        <Input allowClear value={query} onChange={(event) => setQuery(event.target.value)} prefix={<SearchOutlined />} placeholder="搜索日期、版本或 ID" />
      </div>
      <Spin spinning={loading}>
        <Table<HistoryEntry>
          className="history-table"
          columns={columns}
          dataSource={filtered}
          rowKey="scheduleId"
          size="middle"
          pagination={{ pageSize: 12, showSizeChanger: false, showTotal: (total) => `共 ${total} 个版本` }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={loadError ? '无法读取历史记录' : '所选范围内没有排班版本'} /> }}
          scroll={{ x: 980 }}
        />
      </Spin>
    </section>

    <Drawer
      className="history-detail-drawer"
      width={900}
      open={detailEntry !== null}
      onClose={() => setDetailEntry(null)}
      title={detailEntry ? <div className="history-drawer-title"><span>排班版本详情</span><strong>{scheduleDateLabel(detailEntry.scheduleDate)} · v{detailEntry.version}</strong></div> : '排班版本详情'}
      extra={detailEntry && <Space>
        {detailEntry.status !== 'CANDIDATE' && <Button icon={<RollbackOutlined />} onClick={() => restore(detailEntry)}>恢复为待发布</Button>}
        <Button danger icon={<DeleteOutlined />} onClick={() => remove(detailEntry)}>删除记录</Button>
      </Space>}
    >
      {detailEntry && <div className="history-detail-content">
        <div className="history-detail-meta">
          <div><span>版本状态</span>{statusTag(detailEntry)}</div>
          <div><span>生成时间</span><strong>{dateTime(detailEntry.createdAt)}</strong></div>
          <div><span>首次发布时间</span><strong>{dateTime(detailEntry.publishedAt)}</strong></div>
          <div><span>岗位分配</span><strong>{detailEntry.assignmentCount} 次 · {detailEntry.peopleCount} 人</strong></div>
        </div>
        {detailError && <Alert type="error" showIcon message="详情读取失败" description={detailError} />}
        <Spin spinning={detailLoading}>
          <section className="history-detail-section">
            <div className="history-detail-section-heading"><h3>岗位安排</h3><span>按时段逐项回看该版本</span></div>
            <Table
              columns={detailColumns}
              dataSource={detail?.assignments ?? []}
              rowKey={(row) => `${row.slotCode}:${row.positionCode}:${row.groupCode}`}
              size="small"
              pagination={false}
              scroll={{ x: 680 }}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该版本没有岗位分配记录" /> }}
            />
          </section>
          {!!detail?.powerBankDuties.length && <section className="history-detail-section power-history-section">
            <div className="history-detail-section-heading"><h3>推电宝轮值</h3><span>{detail.powerBankDuties.length} 次安排</span></div>
            <div className="history-power-duties">{detail.powerBankDuties.map((duty) => <div key={duty.direction}>
              <Tag color={duty.direction === 'TO_WAREHOUSE' ? 'orange' : 'cyan'}>{duty.direction === 'TO_WAREHOUSE' ? '晚间送入仓库' : '次日早上推回'}</Tag>
              <span>{duty.groupCode}组</span><strong>{duty.personName}</strong>
            </div>)}</div>
          </section>}
        </Spin>
        {detailEntry.status === 'CANDIDATE' && <div className="history-detail-footer"><Button type="primary" disabled={!detail} onClick={() => detail && onOpenSchedule(detail.scheduleDate, detail.scheduleId, detail.requirements)}>打开排班管理</Button><span>待发布版本可继续调整，发布后纳入统计。</span></div>}
      </div>}
    </Drawer>
  </div>
}
