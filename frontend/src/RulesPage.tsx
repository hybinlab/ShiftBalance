import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, App as AntApp, Button, Card, DatePicker, Empty, InputNumber, Popconfirm, Select, Space, Spin, Table, Tabs, Tag, Tooltip, type TableColumnsType } from 'antd'
import { DeleteOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import type { ApiRequest } from './ManagementPages'
import './RulesPage.css'

type Group = { code: string; name: string }
type Position = { code: string; name: string }
type Person = { id: string; name: string; groupCode: string; enabled: boolean; qualifiedPositionCodes: string[] }
type TimeSlot = { code: string; sequence: number; label: string; dayOffset: number }
type TemplateRequirement = {
  slotCode: string
  positionCode: string
  groupRole: 'EARLY_DOWN' | 'LATE_DOWN'
  minCount: number
  maxCount: number | null
  fixedPersonId?: string | null
}
type RequirementTemplate = {
  anchorWorkDate: string
  firstEarlyDownGroup: string
  requirements: TemplateRequirement[]
}
type DailyRequirement = {
  scheduleDate: string
  slotCode: string
  positionCode: string
  positionName: string
  groupCode: string
  minCount: number
  maxCount: number | null
  fixedPersonId?: string | null
}
type Props = { apiBase: string; apiRequest: ApiRequest; onDataChanged: () => void }
type RequirementRow = TemplateRequirement & { key: string }
type DailyRow = DailyRequirement & { key: string }

const isoToday = () => {
  const date = new Date()
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
const rowKey = (row: { slotCode: string; positionCode: string }) => `${row.slotCode}:${row.positionCode}`
const roleOptions: Array<{ value: TemplateRequirement['groupRole']; label: string }> = [
  { value: 'EARLY_DOWN', label: '早下组' },
  { value: 'LATE_DOWN', label: '晚下组' },
]
const requiredGroupRole = (slotCode: string): TemplateRequirement['groupRole'] | undefined => {
  if (slotCode === 'NEXT_DAY_0530_0800') return 'EARLY_DOWN'
  if (slotCode === 'NEXT_DAY_0800_0900') return 'LATE_DOWN'
  return undefined
}
const isFixedEarlyTerminalSlot = (slotCode: string) =>
  slotCode === 'NEXT_DAY_0530_0800' || slotCode === 'NEXT_DAY_0800_0900'
const isFixedEarlyCount = (positionCode: string, slotCode: string) =>
  isFixedEarlyTerminalSlot(slotCode) && ['TERMINAL', 'MEZZANINE'].includes(positionCode)
  || slotCode === 'NEXT_DAY_0530_0800' && positionCode === 'MEAL'
const standardCount = (positionCode: string, slotCode: string) => {
  if (positionCode === 'TERMINAL' && isFixedEarlyTerminalSlot(slotCode)) return 1
  if (positionCode === 'MEAL') return 2
  if (positionCode === 'MEZZANINE' || positionCode === 'OVERNIGHT') return positionCode === 'MEZZANINE' ? 1 : 2
  if (positionCode === 'FLOW') return slotCode === 'NIGHT_UNTIL_END' ? 1 : 2
  return null
}
const minimumCount = (positionCode: string) => positionCode === 'BOMB' ? 3 : 0
const defaultCounts = (positionCode: string, slotCode: string) => {
  const expected = standardCount(positionCode, slotCode)
  return expected === null
    ? { minCount: positionCode === 'BOMB' ? 3 : 0, maxCount: null }
    : { minCount: expected, maxCount: expected }
}

export default function RulesPage({ apiBase, apiRequest, onDataChanged }: Props) {
  const { message, modal } = AntApp.useApp()
  const [groups, setGroups] = useState<Group[]>([])
  const [positions, setPositions] = useState<Position[]>([])
  const [people, setPeople] = useState<Person[]>([])
  const [slots, setSlots] = useState<TimeSlot[]>([])
  const [template, setTemplate] = useState<RequirementTemplate | null>(null)
  const [templateRows, setTemplateRows] = useState<RequirementRow[]>([])
  const [savedTemplate, setSavedTemplate] = useState<RequirementTemplate | null>(null)
  const [selectedDate, setSelectedDate] = useState(isoToday)
  const [dailyRows, setDailyRows] = useState<DailyRow[]>([])
  const [savedDailyRows, setSavedDailyRows] = useState<DailyRow[]>([])
  const [loading, setLoading] = useState(true)
  const [dailyLoading, setDailyLoading] = useState(true)
  const [savingTemplate, setSavingTemplate] = useState(false)
  const [savingDaily, setSavingDaily] = useState(false)
  const [clearingDaily, setClearingDaily] = useState(false)
  const [error, setError] = useState('')
  const [dailyError, setDailyError] = useState('')
  const dailyRequest = useRef(0)
  const templateRequest = useRef(0)

  const loadTemplate = useCallback(async () => {
    const request = ++templateRequest.current
    setLoading(true)
    setError('')
    const results = await Promise.allSettled([
      apiRequest<Group[]>(apiBase, '/api/groups'),
      apiRequest<Position[]>(apiBase, '/api/positions'),
      apiRequest<TimeSlot[]>(apiBase, '/api/schedules/time-slots'),
      apiRequest<RequirementTemplate>(apiBase, '/api/schedules/requirements-template'),
      apiRequest<Person[]>(apiBase, '/api/people?asOf=' + isoToday()),
    ])
    if (templateRequest.current !== request) return
    const failures: string[] = []
    const assign = <T,>(index: number, label: string, setter: (value: T) => void, fallback: T) => {
      const result = results[index]
      if (result.status === 'fulfilled') setter(result.value as T)
      else {
        failures.push(`${label}：${result.reason instanceof Error ? result.reason.message : '请求失败'}`)
        setter(fallback)
      }
    }
    assign(0, '组别', setGroups, [])
    assign(1, '岗位', setPositions, [])
    assign(2, '时段', setSlots, [])
    assign(4, '人员', setPeople, [])
    const templateResult = results[3]
    if (templateResult.status === 'fulfilled') {
      const nextTemplate = templateResult.value as RequirementTemplate
      const rows = nextTemplate.requirements.map((row) => ({ ...row, fixedPersonId: row.fixedPersonId ?? null, key: rowKey(row) }))
      setTemplate(nextTemplate)
      setSavedTemplate(nextTemplate)
      setTemplateRows(rows)
    } else {
      failures.push(`标准模板：${templateResult.reason instanceof Error ? templateResult.reason.message : '请求失败'}`)
      setTemplate(null)
      setSavedTemplate(null)
      setTemplateRows([])
    }
    setError(failures.join('；'))
    setLoading(false)
  }, [apiBase, apiRequest])

  const loadDaily = useCallback(async (date: string) => {
    const request = ++dailyRequest.current
    setDailyLoading(true)
    setDailyError('')
    setDailyRows([])
    setSavedDailyRows([])
    try {
      const requirements = await apiRequest<DailyRequirement[]>(apiBase, `/api/schedules/${date}/requirements`)
      if (dailyRequest.current !== request) return
      const rows = requirements.map((row) => ({ ...row, key: rowKey(row) }))
      setDailyRows(rows)
      setSavedDailyRows(rows)
    } catch (cause) {
      if (dailyRequest.current !== request) return
      setDailyError(cause instanceof Error ? cause.message : '读取当天例外规则失败')
    } finally {
      if (dailyRequest.current === request) setDailyLoading(false)
    }
  }, [apiBase, apiRequest])

  useEffect(() => { void loadTemplate() }, [loadTemplate])
  useEffect(() => { void loadDaily(selectedDate) }, [loadDaily, selectedDate])

  const slotMap = useMemo(() => new Map(slots.map((slot) => [slot.code, slot.label])), [slots])
  const positionMap = useMemo(() => new Map(positions.map((position) => [position.code, position.name])), [positions])
  const groupMap = useMemo(() => new Map(groups.map((group) => [group.code, group.name])), [groups])
  const templateDirty = useMemo(() => JSON.stringify({ ...template, requirements: templateRows.map(({ key: _key, ...row }) => row) })
    !== JSON.stringify(savedTemplate), [template, templateRows, savedTemplate])
  const dailyDirty = useMemo(() => JSON.stringify(dailyRows.map(({ key: _key, ...row }) => row))
    !== JSON.stringify(savedDailyRows.map(({ key: _key, ...row }) => row)), [dailyRows, savedDailyRows])

  const slotOptions = slots.map((slot) => ({ value: slot.code, label: slot.label }))
  const fixedPersonOptions = (positionCode: string, currentId: string | null | undefined) => {
    const eligible = people.filter((person) => person.enabled && (positionCode === 'TERMINAL'
      || person.qualifiedPositionCodes.includes(positionCode)))
    return [
      ...eligible.map((person) => ({
        value: person.id,
        label: person.name + ' · ' + person.groupCode + '组',
      })),
      ...(currentId && !eligible.some((person) => person.id === currentId)
        ? [{ value: currentId, label: '人员 ' + currentId + '（当前不可用）', disabled: true }]
        : []),
    ]
  }
  const positionOptions = (currentCode: string) => [
    ...positions.map((position) => ({ value: position.code, label: position.name })),
    ...(positionMap.has(currentCode) || !currentCode ? [] : [{ value: currentCode, label: `${currentCode}（已停用）`, disabled: true }]),
  ]
  const groupOptions = (currentCode: string) => [
    ...groups.map((group) => ({ value: group.code, label: group.name })),
    ...(groupMap.has(currentCode) || !currentCode ? [] : [{ value: currentCode, label: `${currentCode}（已停用）`, disabled: true }]),
  ]
  const changeSelectedDate = (date: string) => {
    if (date === selectedDate) return
    if (!dailyDirty) {
      setSelectedDate(date)
      return
    }
    modal.confirm({
      title: '放弃未保存的日期例外？',
      content: '切换日期会丢弃当前页面中尚未保存的修改。',
      okText: '放弃并切换',
      cancelText: '继续编辑',
      onOk: () => setSelectedDate(date),
    })
  }

  const updateTemplateRow = (key: string, changes: Partial<RequirementRow>) => {
    setTemplateRows((rows) => rows.map((row) => row.key === key ? { ...row, ...changes } : row))
  }
  const updateDailyRow = (key: string, changes: Partial<DailyRow>) => {
    setDailyRows((rows) => rows.map((row) => row.key === key ? { ...row, ...changes } : row))
  }

  const templateColumns: TableColumnsType<RequirementRow> = [
    { title: '时间段', dataIndex: 'slotCode', width: 190, render: (code: string, row) => <Select aria-label="标准规则时间段" value={code} options={slotOptions} onChange={(slotCode) => {
      const groupRole = requiredGroupRole(slotCode)
      updateTemplateRow(row.key, { slotCode, ...defaultCounts(row.positionCode, slotCode), ...(groupRole ? { groupRole } : {}) })
    }} /> },
    { title: '岗位', dataIndex: 'positionCode', width: 160, render: (code: string, row) => <Select aria-label="标准规则岗位" value={code} options={positionOptions(code)} onChange={(positionCode) => {
      updateTemplateRow(row.key, { positionCode, fixedPersonId: null, ...defaultCounts(positionCode, row.slotCode) })
    }} /> },
    { title: <Tooltip title="按轮班锚点解析成当天的 A/B 组；常规回流由轮休组负责，19:30 后由轮岗组负责。">排班组别</Tooltip>, dataIndex: 'groupRole', width: 150, render: (role: string, row) => {
      const fixedRole = requiredGroupRole(row.slotCode)
      return <Select aria-label="标准规则组别" value={role} disabled={Boolean(fixedRole)} options={roleOptions} onChange={(groupRole: string) => {
        if (groupRole === 'EARLY_DOWN' || groupRole === 'LATE_DOWN') updateTemplateRow(row.key, { groupRole })
      }} />
    } },
    { title: <Tooltip title="人员不受组别轮换影响，只在配置的固定时段和岗位上岗；仍须具备岗位资格，同一时段不能兼任其他岗位。">固定人员</Tooltip>, dataIndex: 'fixedPersonId', width: 180, render: (value: string | null, row) => <Select aria-label="标准规则固定人员" allowClear showSearch optionFilterProp="label" placeholder="不固定" value={value ?? undefined} options={fixedPersonOptions(row.positionCode, value)} onChange={(fixedPersonId) => updateTemplateRow(row.key, {
      fixedPersonId: fixedPersonId ?? null,
      ...(fixedPersonId ? { minCount: Math.max(1, row.minCount) } : {}),
    })} /> },
    { title: '最低人数', dataIndex: 'minCount', width: 125, render: (value: number, row) => <InputNumber aria-label="标准规则最低人数" min={minimumCount(row.positionCode)} precision={0} value={value} disabled={row.positionCode === 'MEAL' || isFixedEarlyCount(row.positionCode, row.slotCode)} onChange={(minCount) => minCount !== null && updateTemplateRow(row.key, { minCount })} /> },
    { title: <Tooltip title="留空代表不设上限。夹层、过夜和回流人数可按实际需要调整；防爆至少 3 人，打饭固定 2 人。">最高人数</Tooltip>, dataIndex: 'maxCount', width: 140, render: (value: number | null, row) => <InputNumber aria-label="标准规则最高人数" min={row.minCount} precision={0} value={value} placeholder="不限" disabled={row.positionCode === 'MEAL' || isFixedEarlyCount(row.positionCode, row.slotCode)} onChange={(maxCount) => updateTemplateRow(row.key, { maxCount: maxCount ?? null })} /> },
    { title: '操作', key: 'actions', width: 76, align: 'right', render: (_, row) => <Tooltip title={row.positionCode === 'BOMB' ? '防爆岗每个时段必需' : isFixedEarlyCount(row.positionCode, row.slotCode) ? '次日早班固定岗位不能删除' : ''}><Button type="text" danger aria-label="删除标准规则" icon={<DeleteOutlined />} disabled={row.positionCode === 'BOMB' || isFixedEarlyCount(row.positionCode, row.slotCode)} onClick={() => setTemplateRows((rows) => rows.filter((item) => item.key !== row.key))} /></Tooltip> },
  ]

  const dailyColumns: TableColumnsType<DailyRow> = [
    { title: '时间段', dataIndex: 'slotCode', width: 190, render: (code: string, row) => <Select aria-label="日期例外时间段" value={code} options={slotOptions} onChange={(slotCode) => updateDailyRow(row.key, { slotCode, ...defaultCounts(row.positionCode, slotCode) })} /> },
    { title: '岗位', dataIndex: 'positionCode', width: 160, render: (code: string, row) => <Select aria-label="日期例外岗位" value={code} options={positionOptions(code)} onChange={(positionCode) => updateDailyRow(row.key, { positionCode, fixedPersonId: null, positionName: positionMap.get(positionCode) ?? positionCode, ...defaultCounts(positionCode, row.slotCode) })} /> },
    { title: '组别', dataIndex: 'groupCode', width: 140, render: (code: string, row) => <Select aria-label="日期例外组别" value={code} options={groupOptions(code)} onChange={(groupCode) => updateDailyRow(row.key, { groupCode })} /> },
    { title: <Tooltip title="人员不受组别限制，只在配置的固定时段和岗位上岗；仍须具备岗位资格，同一时段不能兼任其他岗位。">固定人员</Tooltip>, dataIndex: 'fixedPersonId', width: 180, render: (value: string | null, row) => <Select aria-label="日期例外固定人员" allowClear showSearch optionFilterProp="label" placeholder="不固定" value={value ?? undefined} options={fixedPersonOptions(row.positionCode, value)} onChange={(fixedPersonId) => updateDailyRow(row.key, {
      fixedPersonId: fixedPersonId ?? null,
      ...(fixedPersonId ? { minCount: Math.max(1, row.minCount) } : {}),
    })} /> },
    { title: '最低人数', dataIndex: 'minCount', width: 125, render: (value: number, row) => <InputNumber aria-label="日期例外最低人数" min={minimumCount(row.positionCode)} precision={0} value={value} disabled={row.positionCode === 'MEAL' || isFixedEarlyCount(row.positionCode, row.slotCode)} onChange={(minCount) => minCount !== null && updateDailyRow(row.key, { minCount })} /> },
    { title: <Tooltip title="留空代表不设上限。夹层、过夜和回流人数可按实际需要调整；防爆至少 3 人，打饭固定 2 人。">最高人数</Tooltip>, dataIndex: 'maxCount', width: 140, render: (value: number | null, row) => <InputNumber aria-label="日期例外最高人数" min={row.minCount} precision={0} value={value} placeholder="不限" disabled={row.positionCode === 'MEAL' || isFixedEarlyCount(row.positionCode, row.slotCode)} onChange={(maxCount) => updateDailyRow(row.key, { maxCount: maxCount ?? null })} /> },
    { title: '操作', key: 'actions', width: 76, align: 'right', render: (_, row) => <Tooltip title={row.positionCode === 'BOMB' ? '防爆岗每个时段必需' : isFixedEarlyCount(row.positionCode, row.slotCode) ? '次日早班固定岗位不能删除' : ''}><Button type="text" danger aria-label="删除日期例外" icon={<DeleteOutlined />} disabled={row.positionCode === 'BOMB' || isFixedEarlyCount(row.positionCode, row.slotCode)} onClick={() => setDailyRows((rows) => rows.filter((item) => item.key !== row.key))} /></Tooltip> },
  ]

  const validateRows = (rows: Array<{ slotCode: string; positionCode: string; minCount: number; maxCount: number | null; fixedPersonId?: string | null }>, requiredBombSlots: string[] = []) => {
    const seen = new Set<string>()
    const fixedSeen = new Set<string>()
    for (const row of rows) {
      const key = rowKey(row)
      if (seen.has(key)) return '同一时间段不能重复配置同一岗位。'
      seen.add(key)
      if (row.fixedPersonId) {
        const fixedKey = row.slotCode + ':' + row.fixedPersonId
        if (fixedSeen.has(fixedKey)) return (slotMap.get(row.slotCode) ?? row.slotCode) + '：同一人员不能固定到多个岗位。'
        fixedSeen.add(fixedKey)
      }
      if (row.positionCode === 'BOMB' && row.minCount < 3) return `${slotMap.get(row.slotCode) ?? row.slotCode}：防爆至少需要 3 人。`
      if (row.positionCode === 'MEAL' && (row.minCount !== 2 || row.maxCount !== null && row.maxCount !== 2)) return `${slotMap.get(row.slotCode) ?? row.slotCode}：每次打饭必须安排 2 人。`
      if (row.maxCount !== null && row.maxCount < row.minCount) return `${slotMap.get(row.slotCode) ?? row.slotCode}：最高人数不能低于最低人数。`
    }
    for (const slotCode of ['NEXT_DAY_0530_0800', 'NEXT_DAY_0800_0900']) {
      const slotRows = rows.filter((row) => row.slotCode === slotCode)
      if (!slotRows.length) continue
      const rowFor = (positionCode: string) => slotRows.find((row) => row.positionCode === positionCode)
      const bomb = rowFor('BOMB')
      const terminal = rowFor('TERMINAL')
      const mezzanine = rowFor('MEZZANINE')
      const meal = rowFor('MEAL')
      if (!bomb || !terminal || !mezzanine || (slotCode === 'NEXT_DAY_0530_0800' ? !meal : Boolean(meal))) {
        return '次日早班须配置防爆、候机楼、夹层；仅 05:30–08:00 还需配置打早餐。'
      }
      if (slotRows.some((row) => !['BOMB', 'TERMINAL', 'MEZZANINE', 'MEAL'].includes(row.positionCode))) {
        return '次日早班只配置防爆、候机楼、夹层和 05:30–08:00 打早餐；其余人员全部安排防爆。'
      }
      if (bomb.minCount < 3 || bomb.maxCount !== null
          || terminal.minCount !== 1 || terminal.maxCount !== 1
          || mezzanine.minCount !== 1 || mezzanine.maxCount !== 1
          || slotCode === 'NEXT_DAY_0530_0800' && (meal!.minCount !== 2 || meal!.maxCount !== 2)) {
        return '次日早班候机楼和夹层各 1 人；05:30–08:00 打早餐 2 人，剩余人员安排防爆（至少 3 人）。'
      }
    }
    const configuredBombSlots = new Set(rows.filter((row) => row.positionCode === 'BOMB').map((row) => row.slotCode))
    const missingBombSlot = requiredBombSlots.find((slotCode) => !configuredBombSlots.has(slotCode))
    if (missingBombSlot) return `${slotMap.get(missingBombSlot) ?? missingBombSlot}：必须保留防爆岗需求。`
    return ''
  }

  const saveTemplate = async () => {
    if (!template || !groups.length) return
    if (!templateRows.length) {
      message.error('标准模板至少需要配置一条岗位需求。')
      return
    }
    const validation = validateRows(templateRows, slots.map((slot) => slot.code))
    if (validation) {
      message.error(validation)
      return
    }
    setSavingTemplate(true)
    try {
      const saved = await apiRequest<RequirementTemplate>(apiBase, '/api/schedules/requirements-template', {
        method: 'PUT',
        body: JSON.stringify({
          anchorWorkDate: template.anchorWorkDate,
          firstEarlyDownGroup: template.firstEarlyDownGroup,
          requirements: templateRows.map(({ key: _key, ...row }) => row),
        }),
      })
      const rows = saved.requirements.map((row) => ({ ...row, key: rowKey(row) }))
      setTemplate(saved)
      setSavedTemplate(saved)
      setTemplateRows(rows)
      message.success('标准排班规则已保存')
      onDataChanged()
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '保存标准规则失败')
    } finally {
      setSavingTemplate(false)
    }
  }

  const saveDaily = async () => {
    if (!dailyRows.length) {
      message.error('当天例外至少需要配置一条岗位需求；如需恢复模板，请使用“恢复标准模板”。')
      return
    }
    const validation = validateRows(dailyRows, savedDailyRows.filter((row) => row.positionCode === 'BOMB').map((row) => row.slotCode))
    if (validation) {
      message.error(validation)
      return
    }
    setSavingDaily(true)
    try {
      const requirements = await apiRequest<DailyRequirement[]>(apiBase, `/api/schedules/${selectedDate}/requirements`, {
        method: 'PUT',
        body: JSON.stringify({ requirements: dailyRows.map(({ key: _key, ...row }) => ({
          slotCode: row.slotCode,
          positionCode: row.positionCode,
          groupCode: row.groupCode,
          minCount: row.minCount,
          maxCount: row.maxCount,
          fixedPersonId: row.fixedPersonId ?? null,
        })) }),
      })
      const rows = requirements.map((row) => ({ ...row, key: rowKey(row) }))
      setDailyRows(rows)
      setSavedDailyRows(rows)
      message.success(`${selectedDate} 的岗位需求例外已保存`)
      onDataChanged()
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '保存当天例外失败')
    } finally {
      setSavingDaily(false)
    }
  }

  const clearDaily = async () => {
    setClearingDaily(true)
    try {
      const requirements = await apiRequest<DailyRequirement[]>(apiBase, `/api/schedules/${selectedDate}/requirements`, { method: 'DELETE' })
      const rows = requirements.map((row) => ({ ...row, key: rowKey(row) }))
      setDailyRows(rows)
      setSavedDailyRows(rows)
      message.success(requirements.length ? '当天例外已清除，已恢复标准模板' : '当天例外已清除；该日期没有标准需求')
      onDataChanged()
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '恢复标准模板失败')
    } finally {
      setClearingDaily(false)
    }
  }

  const addTemplateRow = () => {
    const slotCode = slots[0]?.code ?? ''
    const positionCode = positions.find((position) => !templateRows.some((row) => row.slotCode === slotCode && row.positionCode === position.code))?.code ?? positions[0]?.code ?? ''
    const counts = defaultCounts(positionCode, slotCode)
    const groupRole = requiredGroupRole(slotCode) ?? 'EARLY_DOWN'
    setTemplateRows((rows) => [...rows, { slotCode, positionCode, groupRole, ...counts, key: `new:${crypto.randomUUID()}` }])
  }
  const addDailyRow = () => {
    const slotCode = slots[0]?.code ?? ''
    const positionCode = positions.find((position) => !dailyRows.some((row) => row.slotCode === slotCode && row.positionCode === position.code))?.code ?? positions[0]?.code ?? ''
    const counts = defaultCounts(positionCode, slotCode)
    const row = { scheduleDate: selectedDate, slotCode, positionCode, positionName: positionMap.get(positionCode) ?? positionCode,
      groupCode: groups[0]?.code ?? '', ...counts, key: `new:${crypto.randomUUID()}` }
    setDailyRows((rows) => [...rows, row])
  }

  const updateAnchor = (changes: Partial<Pick<RequirementTemplate, 'anchorWorkDate' | 'firstEarlyDownGroup'>>) => {
    setTemplate((current) => current ? { ...current, ...changes } : current)
  }
  const earlyGroupName = groupMap.get(template?.firstEarlyDownGroup ?? '') ?? template?.firstEarlyDownGroup

  return (
    <section className="rules-page">
      <header className="page-heading management-heading">
        <div><p className="eyebrow">排班配置</p><h1>排班规则</h1><p className="page-subtitle">维护上二休二轮换、标准岗位需求，以及特定日期的临时例外。</p></div>
        <Tag color="blue">生成排班时自动应用</Tag>
      </header>

      {(error || dailyError) && <Alert className="management-alert" type="error" showIcon message="部分排班规则读取失败" description={[error, dailyError].filter(Boolean).join('；')} action={<Button size="small" onClick={() => { void loadTemplate(); void loadDaily(selectedDate) }}>重试</Button>} />}

      <Tabs items={[
        { key: 'template', label: '标准规则模板', children: <Spin spinning={loading}>
          {template ? <div className="rules-content">
            <Card className="surface rules-cycle-card" title="上二休二轮换" extra={<Tag color="processing">长期生效</Tag>}>
              <div className="rules-cycle-controls">
                <label><span>周期锚点工作日</span><DatePicker value={dayjs(template.anchorWorkDate)} format="YYYY-MM-DD" allowClear={false} onChange={(date) => date && updateAnchor({ anchorWorkDate: date.format('YYYY-MM-DD') })} /></label>
                <label><span>锚点日早下组</span><Select value={template.firstEarlyDownGroup} options={groupOptions(template.firstEarlyDownGroup)} onChange={(firstEarlyDownGroup) => updateAnchor({ firstEarlyDownGroup })} /></label>
                <div className="rules-cycle-summary"><strong>{template.anchorWorkDate} · {earlyGroupName} 早下</strong><small>该日和次日上班，随后两天休息；下一工作周期早下组交替。</small></div>
              </div>
              <Alert type="info" showIcon message="锚点日期应选择一段工作周期的第一天。轮换按锚点自动推算，无需逐日维护。" />
            </Card>

            <Card className="surface rules-table-card" title="标准岗位需求" extra={<Space wrap><Button icon={<PlusOutlined />} onClick={addTemplateRow} disabled={!slots.length || !positions.length}>添加需求</Button><Button disabled={!templateDirty || !savedTemplate} onClick={() => { if (savedTemplate) { setTemplate(savedTemplate); setTemplateRows(savedTemplate.requirements.map((row) => ({ ...row, key: rowKey(row) }))) } }}>撤销修改</Button><Button type="primary" icon={<SaveOutlined />} loading={savingTemplate} disabled={!templateDirty || !templateRows.length || !groups.length || !slots.length || !positions.length} onClick={() => void saveTemplate()}>保存标准规则</Button></Space>}>
              <div className="rules-table-intro"><span>组别按“早下组 / 晚下组”配置，随轮班锚点自动映射到 A、B 组。</span><span>可选固定一名人员在指定时段执行该岗位；人员不受组别轮换影响，最高人数留空表示不设上限。</span></div>
              <Table<RequirementRow> rowKey="key" size="small" columns={templateColumns} dataSource={templateRows} pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条规则` }} scroll={{ x: 1030 }} locale={{ emptyText: <Empty description="模板没有岗位需求，请添加后保存" /> }} />
              <div className="rules-footnotes"><span><Tag color="blue">固定要求</Tag>防爆至少 3 人；每次打饭 2 人。</span><span>固定人员仍须符合岗位资格，同一时段不能重复安排；日期例外也可单独指定固定人员。</span><span>常规回流由轮休组承担；19:30 后回流由轮岗组承担。</span><span>次日 05:30–08:00：早下组 2 人打早餐，候机楼/负责人和夹层各 1 人，其他人员全部防爆；08:00–09:00 由另一组接班，候机楼/负责人和夹层各 1 人，其他人员全部防爆。</span><span><Tag color="orange">独立轮换</Tag>每个工作日分别安排：早下组早上推回电宝，晚下组晚上推入仓库；不占正式岗位需求、资格和岗位统计名额，轮换进度以已发布排班为准。</span></div>
            </Card>
          </div> : <Card className="surface"><Empty description="尚未配置标准规则模板。请检查后端数据库是否已导入排班规则模板。" /></Card>}
        </Spin> },
        { key: 'exceptions', label: '日期例外', children: <div className="rules-content">
          <Card className="surface rules-exception-card" title="指定日期的岗位需求" extra={<Space><span className="muted-text">日期</span><DatePicker value={dayjs(selectedDate)} format="YYYY-MM-DD" allowClear={false} onChange={(date) => date && changeSelectedDate(date.format('YYYY-MM-DD'))} /></Space>}>
            <Alert className="rules-exception-alert" type="info" showIcon message="这里显示该日期当前生效的需求。保存会整体替换该日期规则；恢复标准模板会删除日期例外。" />
            <Spin spinning={dailyLoading}>
              <div className="rules-table-toolbar"><span>{selectedDate} · {dailyRows.length} 项生效需求</span><Space wrap><Button onClick={addDailyRow} icon={<PlusOutlined />} disabled={!slots.length || !positions.length}>添加需求</Button><Button disabled={!dailyDirty} onClick={() => setDailyRows(savedDailyRows)}>撤销修改</Button><Popconfirm title="清除此日期的例外规则？" description="清除后将恢复标准模板；休息日没有默认岗位需求。" okText="恢复" cancelText="取消" onConfirm={() => void clearDaily()}><Button loading={clearingDaily} disabled={dailyLoading}>恢复标准模板</Button></Popconfirm><Button type="primary" icon={<SaveOutlined />} loading={savingDaily} disabled={!dailyDirty || !dailyRows.length} onClick={() => void saveDaily()}>保存当天例外</Button></Space></div>
              <Table<DailyRow> rowKey="key" size="small" columns={dailyColumns} dataSource={dailyRows} pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 项` }} scroll={{ x: 1030 }} locale={{ emptyText: <Empty description="该日期没有岗位需求；休息日通常如此，可添加例外需求" /> }} />
            </Spin>
            <div className="rules-footnotes"><span>更改组别、需求人数或时段后，保存即覆盖当天的完整需求清单。</span><span>防爆至少 3 人；打饭必须 2 人。常规时段候机楼由剩余人员计算；次日两个早班时段固定候机楼/负责人和夹层各 1 人，其余人员排防爆。</span></div>
          </Card>
        </div> },
      ]} />
    </section>
  )
}
