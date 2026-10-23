import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, App as AntApp, Avatar, Badge, Button, Card, DatePicker, Empty, Input, Menu, Modal, Select, Space, Spin, Statistic, Table, Tag, type TableColumnsType } from 'antd'
import {
  ApiOutlined,
  ArrowRightOutlined,
  BarChartOutlined,
  CalendarOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CloseOutlined,
  EditOutlined,
  HistoryOutlined,
  HomeOutlined,
  LeftOutlined,
  PlusOutlined,
  ProfileOutlined,
  RightOutlined,
  SendOutlined,
  SettingOutlined,
  TeamOutlined,
  ToolOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import DashboardPage from './DashboardPage'
import LoginPage from './LoginPage'
import ManagementPages, { type ManagementModule } from './ManagementPages'
import ProfilePage from './ProfilePage'
import RulesPage from './RulesPage'
import ScheduleCalendarPage from './ScheduleCalendarPage'
import ScheduleHistoryPage from './ScheduleHistoryPage'
import StatisticsPage from './StatisticsPage'
import { groupForDate, workPeriodStartDate } from './scheduleRotation'
import './App.css'

type Group = { code: string; name: string }
type Position = { code: string; name: string }
type Person = {
  id: string
  name: string
  groupCode: string
  enabled: boolean
  remark: string | null
  qualifiedPositionCodes: string[]
  historicalCounts: Record<string, number>
}
type TimeSlot = { code: string; sequence: number; label: string; dayOffset: number }
type Requirement = {
  scheduleDate?: string
  slotCode: string
  positionCode: string
  positionName: string
  groupCode: string
  minCount: number
  maxCount: number | null
  fixedPersonId: string | null
}
type ScheduledPerson = { id: string; name: string; terminalSupervisor: boolean }
type PowerBankDuty = {
  direction: 'TO_WAREHOUSE' | 'FROM_WAREHOUSE'
  slotCode: string
  groupCode: string
  personId: string
  personName: string
}
type Assignment = {
  slotCode: string
  slotLabel: string
  slotSequence: number
  positionCode: string
  positionName: string
  groupCode: string
  minCount: number
  maxCount: number | null
  fixedPersonId: string | null
  personIds: string[]
  terminalSupervisorId: string | null
}
type ScheduleRecord = {
  scheduleId: number
  scheduleDate: string
  status: string
  version: number
  assignments: Array<{
    slotCode: string
    slotLabel: string
    slotSequence: number
    positionCode: string
    positionName: string
    groupCode: string
    fixedPersonId: string | null
    people: ScheduledPerson[]
  }>
  powerBankDuties: PowerBankDuty[]
  requirements?: Requirement[]
}
type WorkPeriodGenerateResponse = {
  workPeriodStartDate: string
  workPeriodEndDate: string
  status: string
  days: Array<{
    scheduleDate: string
    requirements: Requirement[]
    schedule: {
      scheduleDate: string
      status: string
      assignments: ScheduleRecord['assignments']
      powerBankDuties: PowerBankDuty[]
      messages: string[]
      scheduleId: number | null
      version: number | null
    }
  }>
  messages: string[]
}
type ScheduleRow = { key: string; kind: 'slot' | 'overnight' | 'powerbank'; slot: TimeSlot | null }
type RotationTemplate = {
  anchorWorkDate: string
  firstEarlyDownGroup: string
  requirements: Array<{
    slotCode: string
    positionCode: string
    groupRole: 'EARLY_DOWN' | 'LATE_DOWN'
    minCount: number
    maxCount: number | null
  }>
}
type CandidateMeta = { scheduleId: number; requirements: Requirement[] }
type DayData = {
  groups: Group[]
  positions: Position[]
  people: Person[]
  slots: TimeSlot[]
  template: RotationTemplate | null
  requirements: Requirement[]
  candidate: ScheduleRecord | null
  assignments: Assignment[]
  messages: string[]
  loading: boolean
  errors: string[]
}

const EMPTY_DAY: DayData = {
  groups: [],
  positions: [],
  people: [],
  slots: [],
  template: null,
  requirements: [],
  candidate: null,
  assignments: [],
  messages: [],
  loading: true,
  errors: [],
}

const ROLE_ORDER = ['BOMB', 'TERMINAL', 'MEZZANINE', 'FLOW', 'REST']
const HISTORY_POSITION_ORDER = ['MEAL', 'OVERNIGHT', 'BOMB', 'LEADER', 'TERMINAL', 'MEZZANINE', 'FLOW']
const ROLE_LABELS: Record<string, string> = {
  MEAL: '打饭',
  OVERNIGHT: '过夜',
  BOMB: '防爆',
  TERMINAL: '候机楼',
  MEZZANINE: '夹层',
  FLOW: '回流 / 拉包',
  REST: '休 / 维 / 备勤',
}
const ROLE_CLASS: Record<string, string> = {
  MEAL: 'meal',
  OVERNIGHT: 'overnight',
  BOMB: 'bomb',
  TERMINAL: 'terminal',
  MEZZANINE: 'mezzanine',
  FLOW: 'flow',
  REST: 'rest',
}
const OVERNIGHT_PERSON_ORDER = ['宾昕宇', '阳菲莉', '李文锦', '王小曼', '刘芳全', '黄在显', '钟绍凌', '邹运谷', '莫少波']
const POWER_BANK_PERSON_ORDER = ['唐龙', '甘春霖', '刘雨鑫', '吕珈萱', '吴世富', '石蓉昌', '骆灿', '黄勇斌', '黎哲成']

const today = toIsoDate(new Date())
const envApiBase = import.meta.env.VITE_API_BASE_URL ?? ''
type AuthSession = { authenticated: boolean; username: string | null; displayName: string | null }
type UserProfile = { username: string; avatarAvailable: boolean }
type AppModule = 'home' | 'schedule' | 'calendar' | 'history' | 'analytics' | 'rules' | 'profile' | ManagementModule
let csrfToken: string | null = null
let csrfTokenBase: string | null = null

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function fromIsoDate(value: string) {
  return new Date(`${value}T00:00:00`)
}

function shiftDate(value: string, days: number) {
  const date = fromIsoDate(value)
  date.setDate(date.getDate() + days)
  return toIsoDate(date)
}

function shiftMonth(value: string, months: number) {
  const date = fromIsoDate(value)
  const day = date.getDate()
  date.setDate(1)
  date.setMonth(date.getMonth() + months)
  const lastDay = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
  date.setDate(Math.min(day, lastDay))
  return toIsoDate(date)
}

function displayDate(date: string, options: Intl.DateTimeFormatOptions = {}) {
  return fromIsoDate(date).toLocaleDateString('zh-CN', options)
}

function uniquePeople(assignments: Assignment[], slotCode: string) {
  const used = new Set<string>()
  assignments
    .filter((assignment) => assignment.slotCode === slotCode)
    .forEach((assignment) => assignment.personIds.forEach((id) => used.add(id)))
  return used
}

function readCandidateMeta(date: string): CandidateMeta | null {
  try {
    const raw = localStorage.getItem(`shiftbalance.candidate.${date}`)
    return raw ? (JSON.parse(raw) as CandidateMeta) : null
  } catch {
    return null
  }
}

function writeCandidateMeta(date: string, meta: CandidateMeta) {
  // ponytail: remember the latest candidate per date until the API exposes a lookup-by-date endpoint.
  localStorage.setItem(`shiftbalance.candidate.${date}`, JSON.stringify(meta))
}

function makeAssignments(
  requirements: Requirement[],
  slots: TimeSlot[],
  stored: ScheduleRecord['assignments'] = [],
) {
  return requirements.map((requirement) => {
    const assigned = stored.find(
      (item) =>
        item.slotCode === requirement.slotCode &&
        item.positionCode === requirement.positionCode &&
        item.groupCode === requirement.groupCode,
    )
    const slot = slots.find((item) => item.code === requirement.slotCode)
    const personIds = assigned?.people.map((person) => person.id) ?? []
    const terminalSupervisorId =
      assigned?.people.find((person) => person.terminalSupervisor)?.id ?? null
    return {
      ...requirement,
      slotLabel: slot?.label ?? assigned?.slotLabel ?? requirement.slotCode,
      slotSequence: slot?.sequence ?? assigned?.slotSequence ?? 99,
      personIds,
      fixedPersonId: assigned?.fixedPersonId ?? requirement.fixedPersonId ?? null,
      terminalSupervisorId,
    }
  })
}

function normalizeApiBase(value: string) {
  return value.trim().replace(/\/$/, '')
}

async function apiRequest<T>(base: string, path: string, init?: RequestInit): Promise<T> {
  const normalizedBase = normalizeApiBase(base)
  const method = (init?.method ?? 'GET').toUpperCase()
  const headers = new Headers(init?.headers)
  if (!(init?.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
    await ensureCsrf(normalizedBase)
    headers.set('X-CSRF-TOKEN', csrfToken!)
  }
  const response = await fetch(`${normalizeApiBase(base)}${path}`, {
    ...init,
    credentials: 'include',
    headers,
  })
  const text = await response.text()
  let body: unknown = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/auth/login' && path !== '/api/auth/csrf' && path !== '/api/auth/session') {
      window.dispatchEvent(new Event('shiftbalance:unauthorized'))
    }
    const payload = body as { detail?: string; message?: string; error?: string } | null
    throw new Error(payload?.detail ?? payload?.message ?? payload?.error ?? `HTTP ${response.status}`)
  }
  return body as T
}

async function ensureCsrf(base: string) {
  if (csrfToken && csrfTokenBase === base) return
  const response = await fetch(`${base}/api/auth/csrf`, { credentials: 'include' })
  if (!response.ok) throw new Error(`无法初始化安全令牌（HTTP ${response.status}）`)
  const result = await response.json() as { csrfToken: string }
  csrfToken = result.csrfToken
  csrfTokenBase = base
}

function candidateRecord(response: {
  scheduleId: number
  scheduleDate: string
  status: string
  version: number
  assignments: ScheduleRecord['assignments']
  powerBankDuties?: PowerBankDuty[]
}): ScheduleRecord {
  return {
    scheduleId: response.scheduleId,
    scheduleDate: response.scheduleDate,
    status: response.status,
    version: response.version,
    assignments: response.assignments,
    powerBankDuties: response.powerBankDuties ?? [],
  }
}

function Icon({ name, size = 19 }: { name: string; size?: number }) {
  const icons = {
    plane: SendOutlined,
    home: HomeOutlined,
    calendar: CalendarOutlined,
    people: TeamOutlined,
    group: TeamOutlined,
    briefcase: ToolOutlined,
    rules: ProfileOutlined,
    chart: BarChartOutlined,
    history: HistoryOutlined,
    settings: SettingOutlined,
    plus: PlusOutlined,
    chevronLeft: LeftOutlined,
    chevronRight: RightOutlined,
    close: CloseOutlined,
    edit: EditOutlined,
    check: CheckOutlined,
    alert: WarningOutlined,
    clock: ClockCircleOutlined,
    arrow: ArrowRightOutlined,
    api: ApiOutlined,
    spark: ThunderboltOutlined,
  }
  const AntIcon = icons[name as keyof typeof icons] ?? ThunderboltOutlined
  return <AntIcon aria-hidden="true" style={{ fontSize: size, verticalAlign: '-0.125em' }} />
}

function validateAssignments(assignments: Assignment[], people: Person[]) {
  const errors: string[] = []
  const allIdsBySlot = new Set<string>()
  const flowIds = new Set<string>()
  const supervisorIds = new Set<string>()
  const fixedPersonIds = new Set(assignments.map((item) => item.fixedPersonId).filter((id): id is string => Boolean(id)))
  for (const assignment of assignments) {
    const count = assignment.personIds.length
    if (count < assignment.minCount) errors.push(`${assignment.slotLabel} · ${assignment.positionName} 至少需要 ${assignment.minCount} 人`)
    if (assignment.maxCount !== null && count > assignment.maxCount) errors.push(`${assignment.slotLabel} · ${assignment.positionName} 最多 ${assignment.maxCount} 人`)
    for (const id of assignment.personIds) {
      const key = `${assignment.slotCode}:${id}`
      if (allIdsBySlot.has(key)) errors.push(`${assignment.slotLabel} 有人员重复上岗`)
      allIdsBySlot.add(key)
      const person = people.find((item) => item.id === id)
      if (!person || !person.enabled) {
        errors.push(`人员 ${person?.name ?? id} 已停用或不在当天人员列表中`)
        continue
      }
      if (person && ((person.groupCode !== assignment.groupCode && id !== assignment.fixedPersonId) || (!person.qualifiedPositionCodes.includes(assignment.positionCode) && assignment.positionCode !== 'TERMINAL'))) {
        errors.push(`${person.name} 不符合 ${assignment.positionName} 的组别或资格要求`)
      }
      if (fixedPersonIds.has(id) && id !== assignment.fixedPersonId) {
        errors.push(person.name + ' 只能安排在其固定时段和岗位')
      }
      if (assignment.positionCode === 'FLOW') {
        if (flowIds.has(id)) errors.push('回流人员当天不能重复安排')
        flowIds.add(id)
      }
    }
    if (assignment.fixedPersonId && !assignment.personIds.includes(assignment.fixedPersonId)) {
      errors.push('固定人员必须保留在指定岗位')
    }
    if (assignment.positionCode === 'TERMINAL' && assignment.terminalSupervisorId) {
      if (!assignment.personIds.includes(assignment.terminalSupervisorId)) errors.push(`${assignment.slotLabel} 的监管人员必须属于候机楼人员`)
      if (supervisorIds.has(assignment.terminalSupervisorId)) errors.push('候机楼监管人员当天不能重复安排')
      supervisorIds.add(assignment.terminalSupervisorId)
    }
    if (assignment.positionCode === 'TERMINAL' && count > 0 && !assignment.terminalSupervisorId) errors.push(`${assignment.slotLabel} 需要指定一名候机楼监管人员`)
  }
  for (const terminal of assignments.filter((item) => item.positionCode === 'TERMINAL')) {
    const hasDerivedPosts = assignments.some((item) => item.slotCode === terminal.slotCode && item.groupCode === terminal.groupCode && ['BOMB', 'MEZZANINE'].includes(item.positionCode))
    if (!hasDerivedPosts) continue
    const expected = new Set(people.filter((person) => person.enabled && person.groupCode === terminal.groupCode).map((person) => person.id))
    assignments
      .filter((item) => item.fixedPersonId)
      .forEach((item) => {
        const pinned = people.find((person) => person.id === item.fixedPersonId)
        if (pinned && (pinned.groupCode !== terminal.groupCode
            || item.slotCode !== terminal.slotCode || item.groupCode !== terminal.groupCode)) {
          expected.delete(pinned.id)
        }
      })
    const scheduled = new Set(
      assignments
        .filter((item) => item.slotCode === terminal.slotCode && item.groupCode === terminal.groupCode)
        .flatMap((item) => item.personIds),
    )
    scheduled.forEach((id) => { if (!expected.has(id)) scheduled.delete(id) })
    if (expected.size !== scheduled.size || [...expected].some((id) => !scheduled.has(id))) {
      errors.push(`${terminal.slotLabel} 的候机楼及同组岗位必须覆盖该组全部在岗人员`)
    }
  }
  return [...new Set(errors)]
}

function validatePowerBankDuties(duties: PowerBankDuty[], people: Person[]) {
  if (!duties.length) return []
  const errors: string[] = []
  const expectedDirections = new Set(['TO_WAREHOUSE', 'FROM_WAREHOUSE'])
  const seenDirections = new Set<string>()
  for (const duty of duties) {
    if (!expectedDirections.has(duty.direction) || seenDirections.has(duty.direction)) {
      errors.push('推电宝需要分别安排一名送入仓库和推回人员')
      continue
    }
    seenDirections.add(duty.direction)
    const person = people.find((item) => item.id === duty.personId)
    if (!person?.enabled || person.groupCode !== duty.groupCode) {
      errors.push(`${duty.direction === 'TO_WAREHOUSE' ? '送入仓库' : '推回'}人员必须是${duty.groupCode}组启用人员`)
    }
  }
  if (seenDirections.size !== expectedDirections.size) errors.push('推电宝需要分别安排一名送入仓库和推回人员')
  return [...new Set(errors)]
}

function reconcileTerminals(assignments: Assignment[], people: Person[]) {
  const next = assignments.map((assignment) => ({ ...assignment, personIds: [...assignment.personIds] }))
  for (const terminal of next.filter((assignment) => assignment.positionCode === 'TERMINAL')) {
    const taken = new Set<string>()
    next
      .filter((assignment) => assignment.slotCode === terminal.slotCode && assignment.groupCode === terminal.groupCode && assignment.positionCode !== 'TERMINAL')
      .forEach((assignment) => assignment.personIds.forEach((id) => taken.add(id)))
    const available = people.filter((person) => person.enabled && person.groupCode === terminal.groupCode
      && !taken.has(person.id)
      && !next.some((item) => item !== terminal && item.fixedPersonId === person.id))
    const fixedPerson = people.find((person) => person.id === terminal.fixedPersonId && person.enabled)
    if (fixedPerson && !taken.has(fixedPerson.id) && !available.some((person) => person.id === fixedPerson.id)) {
      available.push(fixedPerson)
    }
    if (terminal.slotCode === 'NEXT_DAY_0530_0800' || terminal.slotCode === 'NEXT_DAY_0800_0900') {
      const previous = fixedPerson ?? terminal.personIds.map((id) => available.find((person) => person.id === id)).find(Boolean)
      const replacement = previous ?? available.find((person) => !person.qualifiedPositionCodes.includes('BOMB')) ?? available[0]
      terminal.personIds = replacement ? [replacement.id] : []
      const bomb = next.find((assignment) => assignment.slotCode === terminal.slotCode
        && assignment.groupCode === terminal.groupCode && assignment.positionCode === 'BOMB')
      if (bomb) {
        const assignedElsewhere = new Set(next
          .filter((assignment) => assignment.slotCode === terminal.slotCode && assignment.groupCode === terminal.groupCode
            && assignment.positionCode !== 'BOMB' && assignment.positionCode !== 'TERMINAL')
          .flatMap((assignment) => assignment.personIds))
        bomb.personIds = people
          .filter((person) => person.enabled && (person.groupCode === terminal.groupCode || person.id === bomb.fixedPersonId)
            && person.qualifiedPositionCodes.includes('BOMB') && !assignedElsewhere.has(person.id)
            && !next.some((item) => item !== bomb && item.fixedPersonId === person.id)
            && person.id !== replacement?.id)
          .map((person) => person.id)
      }
    } else {
      terminal.personIds = available.map((person) => person.id)
    }
  }
  const supervisors = new Set<string>()
  for (const terminal of next.filter((assignment) => assignment.positionCode === 'TERMINAL').sort((a, b) => a.slotSequence - b.slotSequence)) {
    const oldSupervisor = terminal.terminalSupervisorId
    if (oldSupervisor && terminal.personIds.includes(oldSupervisor) && !supervisors.has(oldSupervisor)) {
      supervisors.add(oldSupervisor)
      continue
    }
    const replacement = terminal.personIds.find((id) => !supervisors.has(id)) ?? null
    terminal.terminalSupervisorId = replacement
    if (replacement) supervisors.add(replacement)
  }
  return next
}

function Workbench({ apiBase, setApiBase, session, onSessionUpdated, onLogout }: {
  apiBase: string
  setApiBase: (value: string) => void
  session: AuthSession
  onSessionUpdated: (session: AuthSession) => void
  onLogout: () => Promise<void>
}) {
  const { modal } = AntApp.useApp()
  const [selectedDate, setSelectedDate] = useState(today)
  const [day, setDay] = useState<DayData>(EMPTY_DAY)
  const [draft, setDraft] = useState<Assignment[] | null>(null)
  const [powerBankDraft, setPowerBankDraft] = useState<PowerBankDuty[] | null>(null)
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [addKey, setAddKey] = useState<string | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [activeModule, setActiveModule] = useState<AppModule>('home')
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [avatarVersion, setAvatarVersion] = useState(0)
  const [apiInput, setApiInput] = useState(apiBase)
  const [notice, setNotice] = useState('')
  const [reloadKey, setReloadKey] = useState(0)
  const sequence = useRef(0)

  useEffect(() => {
    let active = true
    void apiRequest<UserProfile>(apiBase, '/api/auth/profile')
      .then((result) => { if (active) setProfile(result) })
      .catch(() => { if (active) setProfile(null) })
    return () => { active = false }
  }, [apiBase, session.username])

  useEffect(() => {
    const current = ++sequence.current
    setDay({ ...EMPTY_DAY, loading: true })
    const load = async () => {
      const requests = await Promise.allSettled([
        apiRequest<Group[]>(apiBase, '/api/groups'),
        apiRequest<Position[]>(apiBase, '/api/positions'),
        apiRequest<Person[]>(apiBase, `/api/people?asOf=${selectedDate}`),
        apiRequest<TimeSlot[]>(apiBase, '/api/schedules/time-slots'),
        apiRequest<RotationTemplate>(apiBase, '/api/schedules/requirements-template'),
        apiRequest<Requirement[]>(apiBase, `/api/schedules/${selectedDate}/requirements`),
      ])
      if (sequence.current !== current) return
      const errors: string[] = []
      const value = <T,>(index: number, fallback: T, label: string) => {
        const result = requests[index]
        if (result.status === 'fulfilled') return result.value as T
        errors.push(`${label}：${result.reason instanceof Error ? result.reason.message : '请求失败'}`)
        return fallback
      }
      const groups = value(0, [] as Group[], '组别')
      const positions = value(1, [] as Position[], '岗位')
      const people = value(2, [] as Person[], '人员')
      const slots = value(3, [] as TimeSlot[], '时段')
      const template = value(4, null as RotationTemplate | null, '轮班模板')
      const currentRequirements = value(5, [] as Requirement[], '日期需求')
      const meta = readCandidateMeta(selectedDate)
      let candidate: ScheduleRecord | null = null
      let requirements = currentRequirements
      let messages: string[] = []
      if (meta?.scheduleId) {
        try {
          const saved = await apiRequest<ScheduleRecord>(apiBase, `/api/schedules/${meta.scheduleId}`)
          if (sequence.current !== current) return
          if (saved.scheduleDate === selectedDate) {
            candidate = saved
            if (saved.requirements?.length) requirements = saved.requirements
            else if (meta.requirements?.length) requirements = meta.requirements
          } else {
            localStorage.removeItem(`shiftbalance.candidate.${selectedDate}`)
          }
        } catch (error) {
          if (sequence.current !== current) return
          localStorage.removeItem(`shiftbalance.candidate.${selectedDate}`)
          errors.push(`已保存排班读取失败：${error instanceof Error ? error.message : '请求失败'}`)
        }
      }
      if (candidate && candidate.status !== 'CANDIDATE' && candidate.status !== 'PUBLISHED') {
        localStorage.removeItem(`shiftbalance.candidate.${selectedDate}`)
        candidate = null
      }
      const assignments = makeAssignments(requirements, slots, candidate?.assignments)
      setDay({ groups, positions, people, slots, template, requirements, candidate, assignments, messages, loading: false, errors })
      setDraft(null)
      setPowerBankDraft(null)
      setEditing(false)
      setAddKey(null)
      setNotice('')
    }
    void load()
    return () => {
      sequence.current += 1
    }
  }, [selectedDate, apiBase, reloadKey])

  const cycle = useMemo(() => groupForDate(selectedDate, day.template, day.groups), [selectedDate, day.template, day.groups])
  const activeAssignments = draft ?? day.assignments
  const activePowerBankDuties = editing
    ? powerBankDraft ?? day.candidate?.powerBankDuties ?? []
    : day.candidate?.powerBankDuties ?? []
  const slotRows = useMemo(() => {
    const codes = new Set(day.requirements.map((item) => item.slotCode))
    return day.slots.filter((slot) => codes.has(slot.code)).sort((a, b) => a.sequence - b.sequence)
  }, [day.requirements, day.slots])
  const groupMap = useMemo(() => new Map(day.groups.map((item) => [item.code, item.name])), [day.groups])
  const positionMap = useMemo(() => new Map(day.positions.map((item) => [item.code, item.name])), [day.positions])
  const errors = useMemo(() => editing
    ? [...validateAssignments(activeAssignments, day.people), ...validatePowerBankDuties(activePowerBankDuties, day.people)]
    : [], [activeAssignments, activePowerBankDuties, day.people, editing])
  const assignedPeopleCount = useMemo(() => {
    const ids = new Set(activeAssignments.flatMap((assignment) => assignment.personIds))
    return ids.size
  }, [activeAssignments])
  const satisfiedRows = useMemo(() => activeAssignments.filter((assignment) => assignment.personIds.length >= assignment.minCount && (assignment.maxCount === null || assignment.personIds.length <= assignment.maxCount)).length, [activeAssignments])
  const positionStats = useMemo(() => HISTORY_POSITION_ORDER
    .map((code) => ({
      code,
      name: positionMap.get(code) ?? (code === 'LEADER' ? '负责人' : null),
      count: day.people.reduce((total, person) => total + (person.historicalCounts[code] ?? 0), 0),
    }))
    .filter((item) => item.name), [day.people, positionMap])
  const maxHistoricalPositionCount = Math.max(...positionStats.map((stat) => stat.count), 1)

  const confirmAction = useCallback((title: string, content: string) => new Promise<boolean>((resolve) => {
    modal.confirm({
      title,
      content,
      okText: '继续',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  }), [modal])

  const changeDate = useCallback(async (date: string) => {
    if (editing && !(await confirmAction('切换日期', '当前排班有未保存的调整，放弃修改并切换日期吗？'))) return
    setSelectedDate(date)
  }, [editing, confirmAction])

  useEffect(() => {
    if (!editing) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [editing])

  const updateDraft = (change: (items: Assignment[]) => Assignment[]) => {
    setDraft((previous) => reconcileTerminals(change(previous ?? day.assignments), day.people))
  }

  const startEditing = () => {
    if (day.candidate?.status !== 'CANDIDATE') return
    setDraft(day.assignments.map((assignment) => ({ ...assignment, personIds: [...assignment.personIds] })))
    setPowerBankDraft((day.candidate.powerBankDuties ?? []).map((duty) => ({ ...duty })))
    setEditing(true)
    setNotice('')
  }

  const cancelEditing = async () => {
    if (editing && !(await confirmAction('取消调整', '放弃当前未保存的排班调整吗？'))) return
    setDraft(null)
    setPowerBankDraft(null)
    setEditing(false)
    setAddKey(null)
  }

  const generate = async () => {
    if (cycle?.isWorkday === false || day.requirements.length === 0) return
    const periodStart = workPeriodStartDate(selectedDate, day.template)
    if (!periodStart) return
    const periodDates = [periodStart, shiftDate(periodStart, 1)]
    const hasExistingSchedule = periodDates.some((date) => readCandidateMeta(date) !== null)
    if (hasExistingSchedule && !(await confirmAction('重新生成两天排班', '当前待发布版本和手动调整会转入历史记录；已发布版本会保留，直到新版本发布。新生成的排班仍需人工发布才计入统计。'))) return
    setGenerating(true)
    setNotice('')
    try {
      const result = await apiRequest<WorkPeriodGenerateResponse>(apiBase, '/api/schedules/generate-work-period-from-database', {
        method: 'POST',
        body: JSON.stringify({ scheduleDate: selectedDate }),
      })
      if (!['OPTIMAL', 'FEASIBLE'].includes(result.status) || result.days.some((item) => !item.schedule.scheduleId)) {
        setNotice(result.messages?.join('；') || `排班未生成（${result.status}）`)
        return
      }
      result.days.forEach((item) => writeCandidateMeta(item.scheduleDate, {
        scheduleId: item.schedule.scheduleId!,
        requirements: item.requirements,
      }))
      const selected = result.days.find((item) => item.scheduleDate === selectedDate)
      if (!selected?.schedule.scheduleId) throw new Error('接口没有返回所选日期的排班编号。')
      const record = candidateRecord({
        ...selected.schedule,
        status: 'CANDIDATE',
        scheduleId: selected.schedule.scheduleId,
        version: selected.schedule.version ?? 1,
      })
      const assignments = makeAssignments(selected.requirements, day.slots, selected.schedule.assignments)
      setDay((previous) => ({ ...previous, requirements: selected.requirements, candidate: record, assignments, messages: selected.schedule.messages ?? [], errors: [] }))
      setDraft(null)
      setPowerBankDraft(null)
      setEditing(false)
      setNotice(`${result.workPeriodStartDate} 至 ${result.workPeriodEndDate} 的两天排班已生成并保存为待发布版本。`)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '排班生成失败')
    } finally {
      setGenerating(false)
    }
  }

  const save = async () => {
    if (!day.candidate || !draft || errors.length) return
    setSaving(true)
    setNotice('')
    try {
      const saved = await apiRequest<ScheduleRecord>(apiBase, `/api/schedules/${day.candidate.scheduleId}`, {
        method: 'PUT',
        body: JSON.stringify({
          assignments: draft.map((assignment) => ({
            slotCode: assignment.slotCode,
            positionCode: assignment.positionCode,
            groupCode: assignment.groupCode,
            personIds: assignment.personIds,
            terminalSupervisorId: assignment.positionCode === 'TERMINAL' ? assignment.terminalSupervisorId : null,
          })),
          powerBankDuties: (powerBankDraft ?? []).map(({ direction, groupCode, personId }) => ({ direction, groupCode, personId })),
        }),
      })
      const assignments = makeAssignments(day.requirements, day.slots, saved.assignments)
      setDay((previous) => ({ ...previous, candidate: saved, assignments, messages: [] }))
      writeCandidateMeta(selectedDate, { scheduleId: saved.scheduleId, requirements: day.requirements })
      setDraft(null)
      setPowerBankDraft(null)
      setEditing(false)
      setAddKey(null)
      setNotice('调整已保存。')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '保存调整失败')
    } finally {
      setSaving(false)
    }
  }

  const publish = async () => {
    const candidate = day.candidate
    if (candidate?.status !== 'CANDIDATE' || editing) return
    if (!(await confirmAction('发布当前排班', '发布后会计入历史统计和后续公平性计算，并锁定当前版本。若之后需要修改，请生成新版本再发布。'))) return
    const requestSequence = sequence.current
    setPublishing(true)
    setNotice('')
    try {
      const published = await apiRequest<ScheduleRecord>(apiBase, `/api/schedules/${candidate.scheduleId}/publish`, { method: 'POST' })
      if (sequence.current !== requestSequence) return
      setDay((previous) => ({ ...previous, candidate: published }))
      setNotice('排班已发布，已纳入历史统计和后续公平性计算。')
      try {
        const people = await apiRequest<Person[]>(apiBase, `/api/people?asOf=${selectedDate}`)
        if (sequence.current === requestSequence) setDay((previous) => ({ ...previous, people }))
      } catch (error) {
        if (sequence.current === requestSequence) setNotice(`排班已发布；人员岗位统计刷新失败：${error instanceof Error ? error.message : '请求失败'}`)
      }
    } catch (error) {
      if (sequence.current === requestSequence) setNotice(error instanceof Error ? error.message : '发布排班失败')
    } finally {
      setPublishing(false)
    }
  }

  const eligibleFor = (assignment: Assignment, assignments: Assignment[] = activeAssignments, excludedIds: string[] = []) => {
    const assignedElsewhere = new Set(
      assignments
        .filter((item) => item.slotCode === assignment.slotCode && item.positionCode !== 'TERMINAL' && item !== assignment)
        .flatMap((item) => item.personIds),
    )
    const flowUsed = new Set(
      assignment.positionCode === 'FLOW'
        ? assignments.filter((item) => item.positionCode === 'FLOW' && item !== assignment).flatMap((item) => item.personIds)
        : [],
    )
    const fixedElsewhere = new Set(
      assignments
        .filter((item) => item.fixedPersonId && item !== assignment)
        .map((item) => item.fixedPersonId as string),
    )
    return day.people.filter((person) =>
      person.enabled &&
      (person.groupCode === assignment.groupCode || person.id === assignment.fixedPersonId) &&
      !fixedElsewhere.has(person.id) &&
      !excludedIds.includes(person.id) &&
      !assignment.personIds.includes(person.id) &&
      !assignedElsewhere.has(person.id) &&
      !flowUsed.has(person.id) &&
      (person.qualifiedPositionCodes.includes(assignment.positionCode) || assignment.positionCode === 'TERMINAL'),
    )
  }

  const addPerson = (assignment: Assignment, personId: string) => {
    if (!personId) return
    updateDraft((items) => items.map((item) => item === assignment ? { ...item, personIds: [...item.personIds, personId] } : item))
    setAddKey(null)
  }

  const removePerson = (assignment: Assignment, personId: string) => {
    if (personId === assignment.fixedPersonId) return
    updateDraft((items) => {
      const next = items.map((item) => item === assignment
        ? { ...item, personIds: item.personIds.filter((id) => id !== personId) }
        : item)
      if (assignment.positionCode !== 'MEAL') return next

      const meal = next.find((item) => item.slotCode === assignment.slotCode
        && item.positionCode === assignment.positionCode && item.groupCode === assignment.groupCode)
      if (!meal) return next
      // ponytail: refill this meal minimum by meal history only; day-wide cross-post fairness stays in the solver.
      while (meal.personIds.length < meal.minCount) {
        const replacement = eligibleFor(meal, next, [personId])
          .sort((a, b) => (a.historicalCounts.MEAL ?? 0) - (b.historicalCounts.MEAL ?? 0)
            || a.name.localeCompare(b.name, 'zh-CN'))[0]
        if (!replacement) break
        meal.personIds.push(replacement.id)
      }
      return next
    })
  }

  const changePowerBankPerson = (direction: PowerBankDuty['direction'], personId: string) => {
    const person = day.people.find((item) => item.id === personId)
    setPowerBankDraft((items) => (items ?? []).map((item) => item.direction === direction
      ? { ...item, personId, personName: person?.name ?? item.personName }
      : item))
  }

  const saveApiSettings = async () => {
    if (editing && !(await confirmAction('更改接口地址', '更改接口地址会放弃当前未保存的排班调整。'))) return
    const normalized = normalizeApiBase(apiInput)
    localStorage.setItem('shiftbalance.apiBase', normalized)
    setApiBase(normalized)
    setDraft(null)
    setEditing(false)
    setSettingsOpen(false)
  }

  const navigateToDate = (date: string) => changeDate(date)
  const openCalendarSchedule = async (date: string, entry: { scheduleId: number; requirements: Requirement[] } | null) => {
    if (editing && !(await confirmAction('离开排班调整', '当前调整尚未保存，切换到排班管理会放弃这些修改。'))) return
    if (entry) writeCandidateMeta(date, { scheduleId: entry.scheduleId, requirements: entry.requirements })
    setSelectedDate(date)
    if (date === selectedDate) setReloadKey((value) => value + 1)
    setDraft(null)
    setPowerBankDraft(null)
    setEditing(false)
    setActiveModule('schedule')
  }
  const displayAssignments = activeAssignments
  const overnightAssignment = displayAssignments.find((assignment) => assignment.positionCode === 'OVERNIGHT')
  const powerBankDuties = activePowerBankDuties
  const scheduleRows: ScheduleRow[] = [
    ...slotRows.filter((slot) => slot.dayOffset === 0).map((slot) => ({ key: slot.code, kind: 'slot' as const, slot })),
    ...(overnightAssignment ? [{ key: 'overnight', kind: 'overnight' as const, slot: null }] : []),
    ...(powerBankDuties.length ? [{ key: 'powerbank', kind: 'powerbank' as const, slot: null }] : []),
    ...slotRows.filter((slot) => slot.dayOffset > 0).map((slot) => ({ key: slot.code, kind: 'slot' as const, slot })),
  ]
  const assignmentPeople = (assignment: Assignment, role: string) => <div className="chip-list">{assignment.personIds.map((id) => <div className="chip-with-remove" key={id}>{personChip(id, day.people, `${ROLE_CLASS[role]}-chip`)}{editing && <Button className="remove-chip" type="text" danger size="small" aria-label={`移除 ${personName(id, day.people)}`} icon={<Icon name="close" size={12} />} onClick={() => removePerson(assignment, id)} />}</div>)}{!assignment.personIds.length && <span className="empty-mark">—</span>}</div>
  const assignmentEditor = (assignment: Assignment, role: string) => editing && <div className="add-person-control">{addKey === `${assignment.slotCode}:${role}` ? <Select autoFocus open showSearch size="small" placeholder="选择人员…" options={eligibleFor(assignment).map((person) => ({ value: person.id, label: person.name }))} onChange={(value) => addPerson(assignment, value)} onOpenChange={(open) => { if (!open) setAddKey(null) }} /> : <Button className="add-person-button" type="dashed" size="small" disabled={eligibleFor(assignment).length === 0} icon={<Icon name="plus" size={13} />} onClick={() => setAddKey(`${assignment.slotCode}:${role}`)}>添加</Button>}</div>
  const scheduleColumns: TableColumnsType<ScheduleRow> = [
    {
      key: 'time',
      title: '时间段',
      width: 110,
      className: 'time-column',
      onCell: (row) => ({ className: `time-cell${row.kind === 'overnight' ? ' overnight-time-cell' : row.kind === 'powerbank' ? ' powerbank-time-cell' : ''}` }),
      render: (_value, row) => row.kind === 'overnight'
        ? <><strong>过夜安排</strong><small>接广州航班</small></>
        : row.kind === 'powerbank'
          ? <><strong>推电宝</strong><small>组内轮换</small></>
          : <><strong>{row.slot?.label}</strong>{row.slot && row.slot.dayOffset > 0 && <small>次日</small>}</>,
    },
    ...ROLE_ORDER.map((role) => ({
      key: role,
      onHeaderCell: () => ({ className: `role-head role-${ROLE_CLASS[role]}` }),
      onCell: (row: ScheduleRow) => row.kind === 'overnight' || row.kind === 'powerbank'
        ? role === 'BOMB'
          ? { className: `role-cell ${row.kind}-summary-cell`, colSpan: ROLE_ORDER.length }
          : { colSpan: 0 }
        : ({
        className: `role-cell role-${ROLE_CLASS[role]}${role === 'TERMINAL' ? ' terminal-cell' : ''}${role === 'REST' ? ' rest-cell' : ''}`,
        'data-role': ROLE_LABELS[role],
      }),
      title: role === 'REST'
        ? <div className="schedule-column-title rest-column-title"><span>休 / 维 / 备勤</span><Tag color="red">打饭</Tag></div>
        : role === 'TERMINAL'
          ? <div className="schedule-column-title"><span>候机楼</span><small>{demandHeader(role, day.requirements)} · 蓝色标记负责人</small></div>
        : <div className="schedule-column-title"><span>{ROLE_LABELS[role]}</span><small>{demandHeader(role, day.requirements)}</small></div>,
      render: (_value: unknown, row: ScheduleRow) => {
        if (row.kind === 'overnight') {
          if (role !== 'BOMB' || !overnightAssignment) return null
          return <div className="overnight-row-content"><div className="overnight-row-heading"><Tag color="purple">过夜人员</Tag><span>{groupMap.get(overnightAssignment.groupCode) ?? `${overnightAssignment.groupCode}组`} · {overnightAssignment.slotLabel}</span></div>{assignmentPeople(overnightAssignment, 'OVERNIGHT')}{assignmentEditor(overnightAssignment, 'OVERNIGHT')}</div>
        }
        if (row.kind === 'powerbank') {
          if (role !== 'BOMB') return null
          return <div className="powerbank-row-content">{powerBankDuties.map((duty) => <div className="powerbank-duty" key={duty.direction}>
            <Tag color={duty.direction === 'TO_WAREHOUSE' ? 'orange' : 'cyan'}>{duty.direction === 'TO_WAREHOUSE' ? '晚下组晚上送仓' : '早下组早上推回'}</Tag>
            {editing ? <>
              <span>{groupMap.get(duty.groupCode) ?? `${duty.groupCode}组`} · {duty.direction === 'TO_WAREHOUSE' ? '19:30–结束' : '05:30–08:00'}</span>
              <Select
                aria-label={`${duty.direction === 'TO_WAREHOUSE' ? '晚下组晚上推入仓库' : '早下组早上推回'}人员`}
                size="small"
                showSearch
                optionFilterProp="label"
                value={duty.personId}
                options={day.people
                  .filter((person) => person.enabled && person.groupCode === duty.groupCode)
                  .map((person) => ({ value: person.id, label: person.name }))}
                onChange={(personId) => changePowerBankPerson(duty.direction, personId)}
              />
            </> : <span>{groupMap.get(duty.groupCode) ?? `${duty.groupCode}组`} · {duty.direction === 'TO_WAREHOUSE' ? '19:30–结束' : '05:30–08:00'} · <strong>{duty.personName}</strong></span>}
          </div>)}</div>
        }
        const slot = row.slot!
        const slotAssignments = displayAssignments.filter((assignment) => assignment.slotCode === slot.code)
        const terminal = slotAssignments.find((assignment) => assignment.positionCode === 'TERMINAL')
        const used = uniquePeople(displayAssignments, slot.code)
        const freePeople = day.candidate || editing ? day.people.filter((person) => person.enabled && !used.has(person.id)) : []
        if (role === 'REST') {
          const meal = slotAssignments.find((item) => item.positionCode === 'MEAL')
          return <div className="combined-rest-cell">
            {meal && <div className="meal-duty">{assignmentPeople(meal, 'MEAL')}{assignmentEditor(meal, 'MEAL')}</div>}
            {freePeople.length ? <div className="chip-list">{freePeople.map((person) => personChip(person.id, day.people, 'rest-chip'))}</div> : !meal && <span className="empty-mark">—</span>}
          </div>
        }
        const assignment = slotAssignments.find((item) => item.positionCode === role)
        if (!assignment) return <span className="empty-mark">—</span>
        if (role === 'TERMINAL') {
          const terminalPeople = assignment.personIds
          const supervisorOptions = terminal?.personIds
            .filter((id) => !activeAssignments.some((other) => other.positionCode === 'TERMINAL' && other.slotCode !== terminal.slotCode && other.terminalSupervisorId === id))
            .map((id) => ({ value: id, label: personName(id, day.people) })) ?? []
          return <>
            {terminalPeople.length ? <div className="chip-list">{terminalPeople.map((id) => id === assignment.terminalSupervisorId
              ? personChip(id, day.people, 'supervisor-chip', '负责人')
              : personChip(id, day.people, 'terminal-chip'))}</div> : <span className="empty-mark">—</span>}
            {editing && <small className="derived-note">按剩余在岗人员自动计算</small>}
            {editing && terminal?.personIds.length ? <div className="supervisor-select-wrap"><span>监管</span><Select aria-label={`${slot.label}监管人员`} size="small" placeholder="选择监管人员" value={terminal.terminalSupervisorId ?? undefined} options={supervisorOptions} onChange={(value) => updateDraft((items) => items.map((item) => item === terminal ? { ...item, terminalSupervisorId: value } : item))} /></div> : null}
          </>
        }
        return <>{assignmentPeople(assignment, role)}{assignmentEditor(assignment, role)}</>
      },
    })),
  ]
  const emptySchedule = day.loading
    ? <Spin size="small" description="正在读取当天的岗位需求…" />
    : cycle?.isWorkday === false
      ? <Empty description="今天是休息日" />
      : day.errors.length
        ? <Empty description="暂时无法显示岗位需求" />
        : <Empty description="没有配置当天岗位需求" />
  const navItems = [
    { key: 'home', icon: <Icon name="home" />, label: '首页' },
    { key: 'schedule', icon: <Icon name="calendar" />, label: '排班管理' },
    { key: 'calendar', icon: <Icon name="calendar" />, label: '排班日历' },
    { key: 'history', icon: <Icon name="history" />, label: '历史记录' },
    { key: 'people', icon: <Icon name="people" />, label: '人员管理' },
    { key: 'groups', icon: <Icon name="group" />, label: '分组管理' },
    { key: 'positions', icon: <Icon name="briefcase" />, label: '岗位管理' },
    { key: 'rules', icon: <Icon name="rules" />, label: '排班规则' },
    { key: 'analytics', icon: <Icon name="chart" />, label: '统计分析' },
  ]

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-icon"><img src="/favicon.svg" alt="" /></span><span>排班系统</span></div>
        <Menu className="side-nav" mode="inline" theme="dark" selectedKeys={[activeModule]} items={navItems} onClick={({ key }) => {
          if (key === 'home' || key === 'schedule' || key === 'calendar' || key === 'history' || key === 'people' || key === 'groups' || key === 'positions' || key === 'analytics' || key === 'rules') setActiveModule(key)
        }} />
        <div className="sidebar-bottom">
          <Button type="text" className="nav-item settings-link" icon={<Icon name="settings" />} onClick={() => { setApiInput(apiBase); setSettingsOpen(true) }}>接口设置<span className="side-chevron">›</span></Button>
          <button type="button" className="account-card" aria-label="打开用户资料" onClick={() => setActiveModule('profile')}>
            <Avatar className="account-avatar" src={profile?.avatarAvailable ? `${normalizeApiBase(apiBase)}/api/auth/profile/avatar?v=${avatarVersion}` : undefined} crossOrigin="use-credentials">{(session.username ?? '管').slice(0, 1)}</Avatar>
            <div><strong>{session.username}</strong><small>用户资料</small></div><span className="account-more">···</span>
          </button>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="breadcrumb">工作台 <span>/</span> <strong>{{ home: '首页', schedule: '排班管理', calendar: '排班日历', history: '历史记录', people: '人员管理', groups: '分组管理', positions: '岗位管理', analytics: '统计分析', rules: '排班规则', profile: '用户资料' }[activeModule]}</strong></div>
          <div className="topbar-tools">
            <Badge className={`connection-state ${day.errors.length ? 'offline' : day.loading ? 'connecting' : 'online'}`} status={day.errors.length ? 'error' : day.loading ? 'processing' : 'success'} text={day.errors.length ? '接口异常' : day.loading ? '连接中' : '已连接'} />
            <Button type="text" className="icon-button" aria-label="接口设置" icon={<Icon name="settings" />} onClick={() => { setApiInput(apiBase); setSettingsOpen(true) }} />
            <div className="top-date"><span className="date-dot" />{displayDate(today, { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })}</div>
          </div>
        </header>

        <div className="page-content">
          {activeModule === 'home' ? <DashboardPage
            date={selectedDate}
            groups={day.groups}
            people={day.people}
            requirements={day.requirements}
            assignments={day.assignments}
            slots={day.slots}
            isLoading={day.loading}
            errors={day.errors}
            isWorkday={cycle?.isWorkday ?? null}
            earlyGroup={cycle?.early ?? null}
            lateGroup={cycle?.late ?? null}
            scheduleStatus={day.candidate?.status ?? null}
            onOpenSchedule={() => setActiveModule('schedule')}
            onNavigate={setActiveModule}
            onRetry={() => setReloadKey((value) => value + 1)}
          /> : activeModule === 'schedule' ? <>
          {day.errors.length > 0 && <Alert className="error-banner" type="error" showIcon message="部分接口请求失败" description={day.errors.join('；')} action={<Space><Button size="small" onClick={() => setReloadKey((value) => value + 1)}>重试</Button><Button size="small" type="link" onClick={() => { setApiInput(apiBase); setSettingsOpen(true) }}>检查连接</Button></Space>} />}
          {notice && <Alert className="notice-banner" type={notice.includes('失败') || notice.includes('不能') || notice.includes('至少') || notice.includes('HTTP') ? 'error' : 'success'} showIcon closable message={notice} onClose={() => setNotice('')} />}

          <div className="workspace-grid">
            <div className="primary-column">
              <section className="page-heading">
                <div>
                  <p className="eyebrow">排班工作台</p>
                  <h1>排班管理</h1>
                  <p className="page-subtitle">生成后为待发布版本；人工调整并确认发布后才纳入历史统计。</p>
                </div>
                <div className="heading-actions">
                  <Button className="button button-quiet" disabled title="当前没有历史排班导入接口">导入历史排班 <span className="soon-tag">未接入</span></Button>
                  {editing ? (
                    <>
                      <Button className="button button-quiet" onClick={() => void cancelEditing()}>取消调整</Button>
                      <Button className="button button-primary" type="primary" disabled={saving || errors.length > 0} loading={saving} onClick={() => void save()} icon={<Icon name="check" size={17} />}>{saving ? '保存中…' : '保存调整'}</Button>
                    </>
                  ) : day.candidate?.status === 'CANDIDATE' ? (
                    <>
                      <Button className="button button-quiet" onClick={startEditing} icon={<Icon name="edit" size={17} />}>调整排班</Button>
                      <Button className="button button-quiet" disabled={generating || publishing || day.loading || cycle?.isWorkday === false} loading={generating} onClick={() => void generate()} icon={<Icon name="spark" size={17} />}>{generating ? '生成中…' : '重新生成两天'}</Button>
                      <Button className="button button-primary" type="primary" disabled={saving || publishing || errors.length > 0} loading={publishing} onClick={() => void publish()} icon={<Icon name="check" size={17} />}>{publishing ? '发布中…' : '发布当前排班'}</Button>
                    </>
                  ) : day.candidate?.status === 'PUBLISHED' ? (
                    <Button className="button button-primary" type="primary" disabled={generating || day.loading || cycle?.isWorkday === false} loading={generating} onClick={() => void generate()} icon={<Icon name="spark" size={17} />}>{generating ? '生成中…' : '生成新版本'}</Button>
                  ) : (
                    <Button className="button button-primary" type="primary" disabled={generating || day.loading || cycle?.isWorkday === false || day.requirements.length === 0} loading={generating} onClick={() => void generate()} icon={<Icon name="spark" size={17} />}>{generating ? '生成中…' : '生成两天排班'}</Button>
                  )}
                </div>
              </section>

              <section className="date-panel surface">
                <div className="month-control">
                  <Button type="text" className="icon-button subtle" aria-label="上个月" icon={<Icon name="chevronLeft" />} onClick={() => void navigateToDate(shiftMonth(selectedDate, -1))} />
                  <div className="month-title">{displayDate(selectedDate, { year: 'numeric', month: 'long' })}<span className="month-caret">⌄</span></div>
                  <Button type="text" className="icon-button subtle" aria-label="下个月" icon={<Icon name="chevronRight" />} onClick={() => void navigateToDate(shiftMonth(selectedDate, 1))} />
                  <DatePicker aria-label="选择排班日期" className="date-picker-control" size="small" value={dayjs(selectedDate)} format="YYYY-MM-DD" allowClear={false} onChange={(date) => { if (date) void navigateToDate(date.format('YYYY-MM-DD')) }} />
                  <Button className="today-button" onClick={() => void navigateToDate(today)}>今天</Button>
                </div>
                <div className="date-strip" role="list" aria-label="选择排班日期">
                  {Array.from({ length: 9 }, (_, index) => shiftDate(selectedDate, index - 4)).map((date) => {
                    const type = groupForDate(date, day.template, day.groups)
                    const isSelected = date === selectedDate
                    return (
                      <Button key={date} role="listitem" className={`date-card ${isSelected ? 'selected' : ''} ${date === today ? 'is-today' : ''}`} type={isSelected ? 'primary' : 'default'} onClick={() => void changeDate(date)}>
                        <span className="date-card-month">{fromIsoDate(date).getMonth() + 1}/{String(fromIsoDate(date).getDate()).padStart(2, '0')}</span>
                        <strong>{fromIsoDate(date).toLocaleDateString('zh-CN', { weekday: 'short' })}</strong>
                        <small>{type ? type.isWorkday ? `${type.early} 早下` : '休息日' : '未配置'}</small>
                      </Button>
                    )
                  })}
                </div>
              </section>

              <Card className="schedule-panel surface" styles={{ body: { padding: 0 } }}>
                <div className="panel-heading schedule-heading">
                  <div className="schedule-title-wrap">
                    <div>
                      <p className="eyebrow">当日岗位安排</p>
                      <h2>{displayDate(selectedDate, { month: 'long', day: 'numeric', weekday: 'long' })} 排班表</h2>
                    </div>
                    <Tag className="status-pill" color={day.candidate?.status === 'PUBLISHED' ? 'success' : day.candidate ? 'processing' : cycle?.isWorkday === false ? 'default' : 'processing'}>{day.loading ? '加载中' : day.candidate?.status === 'PUBLISHED' ? '已发布' : day.candidate ? '待发布' : cycle?.isWorkday === false ? '休息日' : '未生成'}</Tag>
                    {cycle?.isWorkday && <Tag className="rotation-pill" color="blue">{cycle.early} 早下 · {cycle.late} 晚下</Tag>}
                  </div>
                  <div className="version-meta">{day.candidate ? <><span>版本 {day.candidate.version}</span><span className="meta-dot">·</span><span>ID {day.candidate.scheduleId}</span></> : <span>{cycle?.isWorkday === false ? '当日无常规工作时段' : '等待生成排班'}</span>}</div>
                </div>
                {editing && <Alert className="editing-hint" type="info" showIcon message="编辑中：过夜人员可在过夜行内添加或移除；推电宝人员可在对应组内更换，不占正式岗位名额。其他岗位受组别、资格和同段规则限制，候机楼人员会自动调整。" />}
                {editing && errors.length > 0 && <Alert className="validation-banner" type="error" showIcon message={`保存前需处理 ${errors.length} 项规则问题`} description={<>{errors.slice(0, 3).join('；')}{errors.length > 3 ? `；另有 ${errors.length - 3} 项` : ''}</>} />}
                <Table<ScheduleRow> className="schedule-table" columns={scheduleColumns} dataSource={scheduleRows} rowKey="key" rowClassName={(row) => [row.kind === 'overnight' ? 'overnight-row' : row.kind === 'powerbank' ? 'powerbank-row' : '', !day.candidate ? 'empty-candidate-row' : ''].filter(Boolean).join(' ')} size="small" bordered pagination={false} tableLayout="fixed" locale={{ emptyText: emptySchedule }} />
                {day.messages.length > 0 && <div className="solver-messages">{day.messages.map((message, index) => <p key={`${index}-${message}`}><Icon name="alert" size={15} />{message}</p>)}</div>}
              </Card>

              <section className="group-grid">
                {day.groups.map((group, index) => {
                  const members = day.people.filter((person) => person.groupCode === group.code && person.enabled)
                  const isEarly = cycle?.early === group.code
                  const isLate = cycle?.late === group.code
                  return <Card size="small" className={`group-card ${index === 1 ? 'group-card-violet' : ''}`} key={group.code}>
                    <div className="group-card-heading"><span className="group-mark">{group.code}</span><div><h3>{group.name}</h3><small>{cycle?.isWorkday ? isEarly ? '本日早下' : isLate ? '本日晚下' : '工作日' : cycle ? '休息日' : '轮班状态待配置'}</small></div><Tag className="member-count" color={index === 1 ? 'purple' : 'blue'}>{members.length} 人</Tag></div>
                    <div className="member-list">{members.length ? members.map((person) => <Tag className="member-pill" key={person.id} title={`${person.name} · ${person.qualifiedPositionCodes.map((code) => positionMap.get(code) ?? code).join('、') || '暂无岗位资格'}`}>{person.name}</Tag>) : <span className="muted-text">暂无启用人员</span>}</div>
                    <div className="group-card-footer"><Icon name="people" size={16} /><span>按所选日期的有效组别和资格显示</span></div>
                  </Card>
                })}
                {!day.groups.length && <Card size="small" className="group-placeholder"><Icon name="group" /><span>连接组别接口后显示 A / B 组人员名单。</span></Card>}
              </section>

              <Card size="small" className="notes-panel">
                <div className="notes-icon"><Icon name="rules" size={18} /></div>
                <div className="notes-title"><strong>排班说明</strong><small>由模板和排班规则提供</small></div>
                <div className="notes-content">
                  <p><span className="note-index">01</span>日期轮换按模板锚点和上二休二规则计算。</p>
                  <p><span className="note-index">02</span>防爆、夹层和回流资格由人员岗位资格管理。</p>
                  <p><span className="note-index">03</span>常规回流由轮休组承担，夜间回流按需求模板指定轮岗组。</p>
                </div>
              </Card>
            </div>

            <aside className="right-rail">
              <Card className="summary-card surface" size="small">
                <div className="rail-heading"><span className="rail-icon blue-rail-icon"><Icon name="calendar" size={19} /></span><div><h3>当天概览</h3><small>{displayDate(selectedDate, { month: 'long', day: 'numeric', weekday: 'short' })}</small></div></div>
                <div className="overview-grid">
                  <div className="overview-tile blue-tile"><Statistic title={cycle?.isWorkday ? '早下组' : '日期状态'} value={cycle?.isWorkday ? cycle.early ?? '—' : cycle ? '休息日' : '未配置'} styles={{ content: { color: '#2374ee' } }} /><small>{cycle?.isWorkday ? '承担对应轮班时段' : '按模板锚点计算'}</small></div>
                  <div className="overview-tile violet-tile"><Statistic title={cycle?.isWorkday ? '晚下组' : '启用组别'} value={cycle?.isWorkday ? cycle.late ?? '—' : day.groups.length} styles={{ content: { color: '#8059c2' } }} /><small>{cycle?.isWorkday ? '负责过夜安排' : '读取自后端'}</small></div>
                  <div className="overview-tile neutral-tile"><Statistic title="有效人员" value={day.loading ? '—' : day.people.filter((person) => person.enabled).length} /><small>按所选日期</small></div>
                  <div className="overview-tile neutral-tile"><Statistic title="已满足岗位" value={day.candidate ? `${satisfiedRows}/${day.requirements.length}` : '—'} /><small>{day.candidate ? '按岗位人数范围核对' : '生成后计算'}</small></div>
                </div>
                <div className="overview-footer"><span className={`status-dot ${day.candidate?.status === 'PUBLISHED' ? 'green-status' : ''}`} />{day.candidate ? `${assignedPeopleCount} 人参与岗位分配 · ${day.candidate.status === 'PUBLISHED' ? '已纳入历史统计' : '待发布，不计入历史'}` : day.loading ? '正在读取后端数据' : '暂无已生成排班'}</div>
              </Card>

              <Card className="coverage-card surface" size="small">
                <div className="rail-section-heading"><div><h3>岗位次数统计</h3><small>已发布排班累计</small></div><Tag className="coverage-count">{positionStats.length} 项</Tag></div>
                {positionStats.length ? <div className="history-stat-list">
                  {positionStats.map((item) => {
                    return <div className="history-stat-row" key={item.code}>
                      <span className={`coverage-icon ${ROLE_CLASS[item.code] ?? ''}`}><Icon name={item.code === 'FLOW' ? 'arrow' : item.code === 'OVERNIGHT' ? 'history' : 'briefcase'} size={14} /></span>
                      <strong>{item.name}</strong>
                      <span className="history-stat-track"><span className={`history-stat-fill ${ROLE_CLASS[item.code] ?? ''}`} style={{ width: `${Math.max(5, item.count / maxHistoricalPositionCount * 100)}%` }} /></span>
                      <span className="history-stat-value">{item.count}</span>
                    </div>
                  })}
                  <div className="coverage-legend"><span className="status-dot green-status" />统计范围为已发布的排班</div>
                </div> : <div className="rail-empty"><Icon name="briefcase" size={20} /><span>{day.loading ? '正在读取岗位次数…' : '暂无已保存的排班记录'}</span></div>}
              </Card>

              <Card className="rotation-card surface" size="small">
                <div className="rail-section-heading"><div><h3>轮班安排</h3><small>按模板锚点自动推算</small></div><span className="rotation-orbit"><Icon name="history" size={18} /></span></div>
                {cycle?.isWorkday ? <div className="rotation-flow">
                  <div className="rotation-step"><span className="rotation-step-label">早下组</span><strong>{groupMap.get(cycle.early ?? '') ?? cycle.early}</strong><small>19:30 下班</small></div>
                  <span className="rotation-arrow"><Icon name="arrow" size={17} /></span>
                  <div className="rotation-step late-step"><span className="rotation-step-label">晚下组</span><strong>{groupMap.get(cycle.late ?? '') ?? cycle.late}</strong><small>负责过夜</small></div>
                </div> : <div className="rail-empty rotation-empty"><Icon name="clock" size={20} /><span>{cycle ? '今天按上二休二为休息日' : '请配置排班模板中的轮换锚点'}</span></div>}
                <div className="rotation-foot"><span className="rotation-line" />不逐日手动调用轮换接口</div>
              </Card>

              <Card className="person-order-card surface" size="small">
                <div className="rail-section-heading"><div><h3>人员顺序</h3><small>过夜与推电宝轮序备注</small></div></div>
                <div className="order-section overnight-order">
                  <div className="order-section-heading"><Tag color="purple">过夜</Tag><small>按顺序轮换</small></div>
                  <ol className="order-list" aria-label="过夜人员顺序">
                    {OVERNIGHT_PERSON_ORDER.map((name, index) => <li key={name}><span className="order-index">{String(index + 1).padStart(2, '0')}</span><span>{name}</span></li>)}
                  </ol>
                </div>
                <div className="order-section powerbank-order">
                  <div className="order-section-heading"><Tag color="orange">推电宝</Tag><small>按顺序轮换</small></div>
                  <ol className="order-list" aria-label="推电宝人员顺序">
                    {POWER_BANK_PERSON_ORDER.map((name, index) => <li key={name}><span className="order-index">{String(index + 1).padStart(2, '0')}</span><span>{name}</span></li>)}
                  </ol>
                </div>
              </Card>

              <Card className="quick-card surface" size="small">
                <div className="rail-section-heading"><div><h3>快捷操作</h3><small>人员与岗位资料维护</small></div></div>
                <div className="quick-grid"><Button icon={<Icon name="people" size={16} />} onClick={() => setActiveModule('people')}>人员管理</Button><Button icon={<Icon name="group" size={16} />} onClick={() => setActiveModule('groups')}>分组设置</Button><Button icon={<Icon name="briefcase" size={16} />} onClick={() => setActiveModule('positions')}>岗位管理</Button><Button icon={<Icon name="chart" size={16} />} onClick={() => setActiveModule('analytics')}>统计分析</Button></div>
              </Card>
            </aside>
          </div>
          </> : activeModule === 'calendar'
            ? <ScheduleCalendarPage
                apiBase={apiBase}
                apiRequest={apiRequest}
                selectedDate={selectedDate}
                groups={day.groups}
                template={day.template}
                onOpenSchedule={(date, entry) => void openCalendarSchedule(date, entry)}
              />
            : activeModule === 'history'
              ? <ScheduleHistoryPage
                  apiBase={apiBase}
                  apiRequest={apiRequest}
                  onOpenSchedule={(date, scheduleId, requirements) => void openCalendarSchedule(date, { scheduleId, requirements })}
                  onScheduleDeleted={(entry) => {
                    if (readCandidateMeta(entry.scheduleDate)?.scheduleId === entry.scheduleId) {
                      localStorage.removeItem(`shiftbalance.candidate.${entry.scheduleDate}`)
                    }
                    if (entry.scheduleDate === selectedDate) setReloadKey((value) => value + 1)
                  }}
                />
            : activeModule === 'analytics'
            ? <StatisticsPage apiBase={apiBase} apiRequest={apiRequest} />
            : activeModule === 'rules'
              ? <RulesPage apiBase={apiBase} apiRequest={apiRequest} onDataChanged={() => setReloadKey((value) => value + 1)} />
              : activeModule === 'profile'
                ? <ProfilePage
                    apiBase={apiBase}
                    apiRequest={apiRequest}
                    username={session.username ?? ''}
                    avatarVersion={avatarVersion}
                    onProfileLoaded={setProfile}
                    onAvatarUpdated={setAvatarVersion}
                    onUsernameUpdated={(username) => onSessionUpdated({ ...session, username })}
                    onLogout={onLogout}
                  />
                : <ManagementPages module={activeModule} apiBase={apiBase} apiRequest={apiRequest} onDataChanged={() => setReloadKey((value) => value + 1)} />}

          {activeModule !== 'home' && <footer className="page-footer"><span>ShiftBalance</span><span>{activeModule === 'schedule' ? '生成和调整后的排班需人工发布，发布后进入历史统计。' : activeModule === 'calendar' ? '轮班状态按标准模板锚点推算；排班记录显示待发布或最新已发布版本。' : activeModule === 'history' ? '删除已发布版本会同步改变历史岗位统计与公平性计算。' : activeModule === 'analytics' ? '公平次数按人员资格、组别和排班时段需求共同核算。' : activeModule === 'rules' ? '标准规则自动应用；日期例外只覆盖所选日期。' : activeModule === 'profile' ? '账号资料仅包含用户名、头像和密码。' : '管理数据与排班服务共享，并按生效日期保留人员资格和组别变更记录。'}</span><Button type="link" onClick={() => { setApiInput(apiBase); setSettingsOpen(true) }}>API 设置 <Icon name="arrow" size={14} /></Button></footer>}
        </div>
      </main>

      <Modal open={settingsOpen} title="后端接口连接" onCancel={() => setSettingsOpen(false)} footer={[
        <Button key="cancel" onClick={() => setSettingsOpen(false)}>取消</Button>,
        <Button key="save" type="primary" onClick={() => void saveApiSettings()}>保存并重连</Button>,
      ]}>
        <p>本地开发默认通过 Vite 代理访问 <code>localhost:8080</code>。需要直连时填写 Spring Boot 地址。</p>
        <label className="api-base-label" htmlFor="api-base">API 根地址</label>
        <Input id="api-base" value={apiInput} onChange={(event) => setApiInput(event.target.value)} placeholder="留空使用本地开发代理，或填 http://localhost:8080" />
        <div className="dialog-hint">例如：<code>http://localhost:8080</code> · 设置保存在当前浏览器。</div>
      </Modal>
    </div>
  )

  function personChip(id: string, people: Person[], className: string, tag?: string) {
    const person = people.find((item) => item.id === id)
    const name = person?.name ?? id
    return <Tag className={`person-chip ${className}`} key={`${className}-${id}`} title={`${name}${person ? ` · ${person.groupCode}组` : ''}`}><span className="person-name">{name}</span>{tag && <small>{tag}</small>}</Tag>
  }
}

function App() {
  const { message } = AntApp.useApp()
  const [apiBase, setApiBase] = useState(() => localStorage.getItem('shiftbalance.apiBase') ?? envApiBase)
  const [session, setSession] = useState<AuthSession | null>(null)
  const [authLoading, setAuthLoading] = useState(true)
  const [loginLoading, setLoginLoading] = useState(false)
  const [loginError, setLoginError] = useState('')
  useEffect(() => {
    let active = true
    setAuthLoading(true)
    void apiRequest<AuthSession>(apiBase, '/api/auth/session')
      .then((result) => { if (active) { setSession(result.authenticated ? result : null); setLoginError('') } })
      .catch((error) => { if (active) { setSession(null); setLoginError(error instanceof Error ? `无法连接登录服务：${error.message}` : '无法连接登录服务') } })
      .finally(() => { if (active) setAuthLoading(false) })
    return () => { active = false }
  }, [apiBase])

  useEffect(() => {
    const expired = () => {
      setSession(null)
      setLoginError('登录状态已失效，请重新登录。')
    }
    window.addEventListener('shiftbalance:unauthorized', expired)
    return () => window.removeEventListener('shiftbalance:unauthorized', expired)
  }, [])

  const login = async (credentials: { username: string; password: string }) => {
    setLoginLoading(true)
    setLoginError('')
    try {
      const result = await apiRequest<AuthSession>(apiBase, '/api/auth/login', {
        method: 'POST', body: JSON.stringify(credentials),
      })
      setSession(result)
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : '登录失败，请检查账号和密码。')
    } finally {
      setLoginLoading(false)
    }
  }

  const logout = async () => {
    try {
      await apiRequest<void>(apiBase, '/api/auth/logout', { method: 'POST' })
      setSession(null)
      setLoginError('')
    } catch (error) {
      message.error(error instanceof Error ? `退出登录失败：${error.message}` : '退出登录失败')
    }
  }

  if (authLoading) return <div className="auth-loading"><Spin size="large" tip="正在验证登录状态…" /></div>
  if (!session) return <LoginPage
    onSubmit={login}
    loading={loginLoading}
    error={loginError}
    apiBase={apiBase}
    onApplyApiBase={(value) => {
      const normalized = normalizeApiBase(value)
      localStorage.setItem('shiftbalance.apiBase', normalized)
      setApiBase(normalized)
    }}
  />
  return <Workbench apiBase={apiBase} setApiBase={setApiBase} session={session} onSessionUpdated={setSession} onLogout={logout} />
}

function personName(id: string, people: Person[]) {
  return people.find((person) => person.id === id)?.name ?? id
}

function demandHeader(role: string, requirements: Requirement[]) {
  const matches = requirements.filter((item) => item.positionCode === role)
  if (!matches.length) return '未配置'
  const minimum = Math.max(...matches.map((item) => item.minCount))
  const maximums = matches.map((item) => item.maxCount).filter((value): value is number => value !== null)
  if (role === 'BOMB' && maximums.length === 0) return `至少 ${minimum} 人`
  if (maximums.length && minimum === Math.min(...maximums)) return `${minimum} 人`
  if (role === 'TERMINAL' && minimum === 0 && maximums.length === 0) return '按剩余人员计算'
  const count = matches.length > 1 ? `${matches.length} 段` : `${minimum}+ 人`
  return count
}

export default App
