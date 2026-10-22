import { Alert, Button, Card, Empty, Progress, Skeleton, Space, Statistic, Tag } from 'antd'
import {
  ArrowRightOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import './DashboardPage.css'

type Group = { code: string; name: string }
type Person = { id: string; groupCode: string; enabled: boolean }
type Requirement = { slotCode: string; positionName: string; groupCode: string; minCount: number }
type Assignment = { slotCode: string; positionName: string; groupCode: string; minCount: number; personIds: string[] }
type TimeSlot = { code: string; sequence: number; label: string }
type Props = {
  date: string
  groups: Group[]
  people: Person[]
  requirements: Requirement[]
  assignments: Assignment[]
  slots: TimeSlot[]
  isLoading: boolean
  errors: string[]
  isWorkday: boolean | null
  earlyGroup: string | null
  lateGroup: string | null
  scheduleStatus: string | null
  onOpenSchedule: () => void
  onNavigate: (module: 'people' | 'groups' | 'positions' | 'analytics' | 'rules') => void
  onRetry: () => void
}

function formatDate(value: string) {
  const date = new Date(`${value}T00:00:00`)
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(date)
}

function statusText(status: string | null, isWorkday: boolean | null, errors: string[], loading: boolean) {
  if (loading) return '读取中'
  if (errors.length) return '接口异常'
  if (isWorkday === false) return '轮休日'
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'CANDIDATE') return '待确认'
  return '未生成'
}

export default function DashboardPage({
  date, groups, people, requirements, assignments, slots, isLoading, errors, isWorkday,
  earlyGroup, lateGroup, scheduleStatus, onOpenSchedule, onNavigate, onRetry,
}: Props) {
  const enabledPeople = people.filter((person) => person.enabled)
  const groupCounts = groups.map((group) => ({
    ...group,
    count: enabledPeople.filter((person) => person.groupCode === group.code).length,
  }))
  const scheduleRows = slots
    .filter((slot) => requirements.some((requirement) => requirement.slotCode === slot.code))
    .sort((left, right) => left.sequence - right.sequence)
    .map((slot) => {
      const required = requirements.filter((requirement) => requirement.slotCode === slot.code)
      const assigned = assignments.filter((assignment) => assignment.slotCode === slot.code)
      const minimum = required.reduce((sum, item) => sum + item.minCount, 0)
      const filled = assigned.reduce((sum, item) => sum + item.personIds.length, 0)
      const covered = assigned.filter((item) => item.personIds.length >= item.minCount).length
      return { ...slot, requirementCount: required.length, minimum, filled, covered, complete: covered === required.length }
    })
  const totalRequired = requirements.reduce((sum, item) => sum + item.minCount, 0)
  const totalAssigned = assignments.reduce((sum, item) => sum + item.personIds.length, 0)
  const coveredRequirements = assignments.filter((item) => item.personIds.length >= item.minCount).length
  const progress = totalRequired ? Math.min(100, Math.round(totalAssigned / totalRequired * 100)) : 0
  const scheduleLabel = statusText(scheduleStatus, isWorkday, errors, isLoading)
  const groupName = (code: string | null) => groups.find((group) => group.code === code)?.name ?? (code ? `${code} 组` : '—')

  return (
    <div className="dashboard-page">
      <section className="dashboard-heading">
        <div>
          <p className="eyebrow">工作台概览</p>
          <h1>首页</h1>
          <p className="dashboard-subtitle">{formatDate(date)} · 排班与人员情况</p>
        </div>
        <Button type="primary" size="large" icon={<CalendarOutlined />} onClick={onOpenSchedule}>打开排班管理</Button>
      </section>

      {errors.length > 0 && <Alert
        className="dashboard-alert"
        type="error"
        showIcon
        title="暂时无法完整读取工作台数据"
        description={errors.join('；')}
        action={<Button size="small" onClick={onRetry}>重试</Button>}
      />}

      <Card className="dashboard-kpis" aria-label="今日摘要">
        <div className="dashboard-kpi-cell">
          <Statistic title="排班状态" value={isLoading ? '—' : scheduleLabel} />
          <span className="dashboard-kpi-caption">{scheduleStatus === 'PUBLISHED' ? '已纳入历史统计' : scheduleStatus === 'CANDIDATE' ? '发布后纳入历史统计' : '以当前日期为准'}</span>
        </div>
        <div className="dashboard-kpi-cell">
          <Statistic title="有效人员" value={isLoading ? '—' : enabledPeople.length} suffix={!isLoading && enabledPeople.length ? '人' : undefined} />
          <span className="dashboard-kpi-caption">{groupCounts.map((group) => `${group.name} ${group.count} 人`).join(' · ') || '尚无组别数据'}</span>
        </div>
        <div className="dashboard-kpi-cell">
          <Statistic title="岗位需求覆盖" value={isLoading || !scheduleStatus ? '—' : `${coveredRequirements}/${requirements.length}`} />
          <span className="dashboard-kpi-caption">{scheduleStatus ? `最低需求 ${totalRequired} 人` : isWorkday === false ? '轮休日期无需安排岗位' : '当前没有待检查的需求'}</span>
        </div>
      </Card>

      <div className="dashboard-content-grid">
        <main className="dashboard-main-column">
          <Card className="dashboard-roster-card" title={<span><CalendarOutlined />　当前排班</span>} extra={<Tag className={`dashboard-status-tag ${scheduleStatus === 'PUBLISHED' ? 'published' : ''}`}>{scheduleLabel}</Tag>}>
            {isLoading ? <Skeleton active paragraph={{ rows: 4 }} /> : scheduleRows.length ? <>
              <div className="dashboard-roster-summary">
                <div>
                  <strong>{scheduleStatus ? `${coveredRequirements} / ${requirements.length}` : '—'}</strong>
                  <span>项岗位需求已满足</span>
                </div>
                <Progress percent={progress} showInfo={false} strokeColor="#2374ee" trailColor="#e8eef7" />
                <span className="dashboard-progress-label">已安排 {totalAssigned} / 最低需求 {totalRequired} 人</span>
              </div>
              <div className="dashboard-slot-list">
                {scheduleRows.map((row) => <div className="dashboard-slot-row" key={row.code}>
                  <time>{row.label}</time>
                  <span>{row.requirementCount} 项岗位需求 · 至少 {row.minimum} 人</span>
                  <span className={`dashboard-slot-state ${row.complete && scheduleStatus ? 'complete' : ''}`}>
                    {scheduleStatus ? `${row.covered}/${row.requirementCount} 满足` : '未排班'}
                  </span>
                </div>)}
              </div>
            </> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={isWorkday === false ? '当前日期为轮休日' : scheduleStatus ? '当前排班没有配置岗位需求' : '尚未生成当前日期的排班'}>
              {isWorkday !== false && <Button type="primary" onClick={onOpenSchedule}>前往生成排班</Button>}
            </Empty>}
          </Card>

          <Card className="dashboard-groups-card" title={<span><TeamOutlined />　组别人员</span>} extra={<Button type="link" onClick={() => onNavigate('groups')}>组别管理 <ArrowRightOutlined /></Button>}>
            {isLoading ? <Skeleton active paragraph={{ rows: 2 }} /> : groupCounts.length ? <div className="dashboard-group-list">
              {groupCounts.map((group) => <div className="dashboard-group-item" key={group.code}>
                <span className={`dashboard-group-mark group-${group.code.toLowerCase()}`}>{group.code}</span>
                <div className="dashboard-group-copy"><strong>{group.name}</strong><span>{group.count} 名有效人员</span></div>
                <span className="dashboard-group-count">{group.count}<small>人</small></span>
              </div>)}
            </div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无组别信息" />}
          </Card>
        </main>

        <aside className="dashboard-side-column">
          <Card className="dashboard-rotation-card" title={<span><ClockCircleOutlined />　轮班安排</span>}>
            {isLoading ? <Skeleton active paragraph={{ rows: 2 }} /> : isWorkday ? <>
              <p className="dashboard-side-caption">按排班模板锚点计算</p>
              <div className="dashboard-rotation-flow">
                <div><span>早下组</span><strong>{groupName(earlyGroup)}</strong><small>承担早下时段</small></div>
                <ArrowRightOutlined />
                <div className="late"><span>晚下组</span><strong>{groupName(lateGroup)}</strong><small>负责当晚过夜</small></div>
              </div>
            </> : <div className="dashboard-rest-day"><ClockCircleOutlined /><span>{isWorkday === false ? '轮休日，暂无轮班分配' : '轮班模板尚未配置'}</span></div>}
          </Card>

          <Card className="dashboard-actions-card" title={<span><ThunderboltOutlined />　快捷入口</span>}>
            <Space orientation="vertical" size={8} className="dashboard-action-list">
              <Button block onClick={() => onNavigate('people')}>人员管理 <ArrowRightOutlined /></Button>
              <Button block onClick={() => onNavigate('positions')}>岗位管理 <ArrowRightOutlined /></Button>
              <Button block onClick={() => onNavigate('rules')}>排班规则 <ArrowRightOutlined /></Button>
              <Button block onClick={() => onNavigate('analytics')}>统计分析 <ArrowRightOutlined /></Button>
            </Space>
            <div className="dashboard-action-note"><CheckCircleOutlined /> 仅已发布排班计入历史统计</div>
          </Card>
        </aside>
      </div>
    </div>
  )
}
