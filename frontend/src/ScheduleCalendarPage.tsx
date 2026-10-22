import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, DatePicker, Empty, Spin, Tag } from 'antd'
import { ArrowRightOutlined, CalendarOutlined, LeftOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { groupForDate } from './scheduleRotation'
import './ScheduleCalendarPage.css'

type Group = { code: string; name: string }
type RotationTemplate = { anchorWorkDate: string; firstEarlyDownGroup: string; requirements: unknown[] }
type Requirement = {
  slotCode: string
  positionCode: string
  positionName: string
  groupCode: string
  minCount: number
  maxCount: number | null
  fixedPersonId: string | null
}
type CalendarEntry = {
  scheduleId: number
  scheduleDate: string
  status: 'CANDIDATE' | 'PUBLISHED'
  version: number
  requirements: Requirement[]
}
type ApiRequest = <T>(base: string, path: string, init?: RequestInit) => Promise<T>

type Props = {
  apiBase: string
  apiRequest: ApiRequest
  selectedDate: string
  groups: Group[]
  template: RotationTemplate | null
  onOpenSchedule: (date: string, entry: CalendarEntry | null) => void
}

const weekdayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const today = dayjs().format('YYYY-MM-DD')

function gridStartFor(month: Dayjs) {
  const firstDay = month.startOf('month')
  return firstDay.subtract((firstDay.day() + 6) % 7, 'day')
}

export default function ScheduleCalendarPage({
  apiBase, apiRequest, selectedDate, groups, template, onOpenSchedule,
}: Props) {
  const [visibleMonth, setVisibleMonth] = useState(() => dayjs(selectedDate).startOf('month'))
  const [activeDate, setActiveDate] = useState(selectedDate)
  const [reloadKey, setReloadKey] = useState(0)
  const [entries, setEntries] = useState<CalendarEntry[]>([])
  const [loadedRange, setLoadedRange] = useState('')
  const [loadError, setLoadError] = useState('')

  useEffect(() => {
    setActiveDate(selectedDate)
    setVisibleMonth(dayjs(selectedDate).startOf('month'))
  }, [selectedDate])

  const gridStart = useMemo(() => gridStartFor(visibleMonth), [visibleMonth])
  const gridEnd = gridStart.add(41, 'day')
  const startDate = gridStart.format('YYYY-MM-DD')
  const endDate = gridEnd.format('YYYY-MM-DD')
  const rangeKey = `${startDate}/${endDate}`
  const loading = loadedRange !== rangeKey

  useEffect(() => {
    let active = true
    setLoadError('')
    setLoadedRange('')
    void apiRequest<CalendarEntry[]>(apiBase,
      `/api/schedules/calendar?startDate=${startDate}&endDate=${endDate}`)
      .then((result) => {
        if (!active) return
        setEntries(result)
        setLoadedRange(rangeKey)
      })
      .catch((error) => {
        if (!active) return
        setLoadedRange(rangeKey)
        setLoadError(error instanceof Error ? error.message : '请求失败')
      })
    return () => { active = false }
  }, [apiBase, apiRequest, endDate, rangeKey, reloadKey, startDate])

  const entriesByDate = useMemo(() => new Map(entries.map((entry) => [entry.scheduleDate, entry])), [entries])
  const dates = useMemo(() => Array.from({ length: 42 }, (_, index) => gridStart.add(index, 'day')),
    [gridStart])
  const visibleEntries = entries.filter((entry) => dayjs(entry.scheduleDate).month() === visibleMonth.month()
    && dayjs(entry.scheduleDate).year() === visibleMonth.year())
  const publishedCount = visibleEntries.filter((entry) => entry.status === 'PUBLISHED').length
  const candidateCount = visibleEntries.filter((entry) => entry.status === 'CANDIDATE').length
  const activeRotation = groupForDate(activeDate, template, groups)
  const activeEntry = entriesByDate.get(activeDate) ?? null
  const groupName = (code: string | null) => groups.find((group) => group.code === code)?.name ?? (code ? `${code}组` : '—')

  const renderDay = (date: Dayjs) => {
    const key = date.format('YYYY-MM-DD')
    const rotation = groupForDate(key, template, groups)
    const entry = entriesByDate.get(key)
    const currentMonth = date.month() === visibleMonth.month() && date.year() === visibleMonth.year()
    const selected = key === activeDate
    const dayStatus = entry?.status === 'PUBLISHED'
      ? '已发布'
      : entry?.status === 'CANDIDATE'
        ? '待发布'
        : rotation?.isWorkday
          ? '未生成'
          : rotation ? '轮休日' : '未配置'
    return <button
      key={key}
      type="button"
      className={`schedule-calendar-day${currentMonth ? '' : ' outside-month'}${selected ? ' selected-day' : ''}${key === today ? ' today-day' : ''}${rotation?.isWorkday ? ' workday' : ''}`}
      aria-label={`${date.format('YYYY年M月D日')}，${rotation?.isWorkday ? `${groupName(rotation.early)}早下，${groupName(rotation.late)}晚下` : rotation ? '轮休日' : '未配置轮班'}，${dayStatus}`}
      aria-pressed={selected}
      onClick={() => setActiveDate(key)}
    >
      <span className="calendar-day-top"><strong>{date.date()}</strong>{key === today && <span className="calendar-today-mark">今天</span>}</span>
      {rotation?.isWorkday
        ? <span className="calendar-shift-label">{rotation.early} 早下 · {rotation.late} 晚下</span>
        : <span className="calendar-shift-label muted-shift">{rotation ? '轮休日' : '未配置轮班'}</span>}
      {entry ? <span className={`calendar-entry-status ${entry.status === 'PUBLISHED' ? 'published-entry' : 'candidate-entry'}`}>
        {entry.status === 'PUBLISHED' ? '已发布' : '待发布'} · v{entry.version}
      </span> : rotation?.isWorkday
        ? <span className="calendar-entry-status missing-entry">未生成</span>
        : <span className="calendar-entry-status rest-entry">无需排班</span>}
    </button>
  }

  return <div className="schedule-calendar-page">
    <section className="page-heading">
      <div>
        <h1>排班日历</h1>
        <p className="page-subtitle">查看每天的早下组、晚下组和排班版本；选择日期后可打开排班管理。</p>
      </div>
      <div className="heading-actions calendar-heading-actions">
        <span className="calendar-month-counts"><span>{publishedCount} 天已发布</span><span>{candidateCount} 天待发布</span></span>
        <Button icon={<ReloadOutlined />} onClick={() => setReloadKey((value) => value + 1)} loading={loading}>刷新</Button>
      </div>
    </section>

    <div className="schedule-calendar-layout">
      <Card className="calendar-month-card surface" styles={{ body: { padding: 0 } }}>
        <div className="calendar-toolbar">
          <div className="calendar-month-controls">
            <Button aria-label="上个月" icon={<LeftOutlined />} onClick={() => setVisibleMonth((month) => month.subtract(1, 'month'))} />
            <DatePicker
              aria-label="选择月份"
              picker="month"
              value={visibleMonth}
              format="YYYY年M月"
              allowClear={false}
              onChange={(value) => { if (value) setVisibleMonth(value.startOf('month')) }}
            />
            <Button aria-label="下个月" icon={<RightOutlined />} onClick={() => setVisibleMonth((month) => month.add(1, 'month'))} />
          </div>
          <Button onClick={() => { setVisibleMonth(dayjs().startOf('month')); setActiveDate(today) }}>今天</Button>
        </div>

        {loadError && <Alert
          className="calendar-load-error"
          type="error"
          showIcon
          message="排班记录读取失败"
          description={loadError}
          action={<Button size="small" onClick={() => setReloadKey((value) => value + 1)}>重试</Button>}
        />}

        <div className="calendar-weekdays" aria-hidden="true">
          {weekdayLabels.map((label) => <span key={label}>{label}</span>)}
        </div>
        <Spin spinning={loading}>
          <div className="schedule-calendar-grid" role="grid" aria-label={`${visibleMonth.format('YYYY年M月')} 排班日历`}>
            {dates.map((date) => renderDay(date))}
          </div>
        </Spin>
        <div className="calendar-legend">
          <span><i className="legend-dot early-legend" />早下 / 晚下</span>
          <span><i className="legend-dot published-legend" />已发布</span>
          <span><i className="legend-dot candidate-legend" />待发布</span>
          <span><i className="legend-dot no-schedule-legend" />未生成</span>
        </div>
      </Card>

      <aside className="calendar-detail-column">
        <Card className="calendar-detail-card surface" title={<span><CalendarOutlined />　所选日期</span>}>
          <div className="calendar-detail-date">{dayjs(activeDate).format('M月D日')}<small>{dayjs(activeDate).locale('zh-cn').format('dddd')}</small></div>
          <dl className="calendar-detail-list">
            <div><dt>日期类型</dt><dd>{activeRotation ? activeRotation.isWorkday ? '工作日' : '轮休日' : '未配置轮班锚点'}</dd></div>
            <div><dt>轮班组别</dt><dd>{activeRotation?.isWorkday ? `${groupName(activeRotation.early)}早下 · ${groupName(activeRotation.late)}晚下` : '—'}</dd></div>
            <div><dt>排班状态</dt><dd>{activeEntry?.status === 'PUBLISHED' ? <Tag color="success">已发布</Tag> : activeEntry ? <Tag color="processing">待发布</Tag> : activeRotation?.isWorkday ? <Tag>未生成</Tag> : <span>无需排班</span>}</dd></div>
            {activeEntry && <div><dt>排班版本</dt><dd>v{activeEntry.version} · ID {activeEntry.scheduleId}</dd></div>}
          </dl>
          <Button className="calendar-open-schedule" type="primary" icon={<ArrowRightOutlined />} onClick={() => onOpenSchedule(activeDate, activeEntry)}>
            打开排班管理
          </Button>
          {!template && <Empty className="calendar-no-template" image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先在排班规则中配置轮班锚点" />}
        </Card>

        <Card className="calendar-note-card surface" title="排班状态">
          <p>候选排班需要人工确认发布；只有已发布版本会计入历史统计和公平性计算。</p>
          <div className="calendar-detail-note"><span className="legend-dot published-legend" />已发布排班按该日期最新发布版本显示。</div>
          <div className="calendar-detail-note"><span className="legend-dot candidate-legend" />存在待发布版本时，日历优先标记待发布。</div>
        </Card>
      </aside>
    </div>
  </div>
}
