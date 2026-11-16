import { Alert, Button, Card, Checkbox, Input, Popconfirm, Select, Space, Switch, Table, Tag } from 'antd'
import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { slotLabel, type SchedulingRules } from './schedulingRules'

type Props = {
  rules: SchedulingRules
  groups: { code: string; name: string }[]
  firstGroup: string
  people: { id: string; name: string; groupCode: string; enabled: boolean }[]
  onChange: (rules: SchedulingRules) => void
}

export default function RulePolicyEditor({ rules, groups, firstGroup, people, onChange }: Props) {
  const change = (patch: Partial<SchedulingRules>) => onChange({ ...rules, ...patch })
  const firstName = groups.find(g => g.code === firstGroup)?.name ?? firstGroup
  const otherName = groups.find(g => g.code !== firstGroup)?.name ?? '另一组'
  const groupOptions = [{ value: 'FIRST', label: firstName }, { value: 'OTHER', label: otherName }]
  const slotOptions = rules.slots.map(s => ({ value: s.code, label: slotLabel(s) }))
  const switches: [keyof SchedulingRules, string, string][] = [
    ['flowNoRepeat', '同一人每天最多一次回流', '关闭后，人员可在不同时段再次安排回流。'],
    ['supervisorRequired', '候机楼必须指定一名监管', '开启时，每个有候机楼人员的时段指定一名监管。'],
    ['supervisorNoRepeat', '同一人每天最多一次监管', '人员减少时可关闭，允许跨时段重复监管。'],
    ['fixedExclusive', '固定人员只在指定时段和岗位上岗', '关闭后，固定人员仍会到指定岗位，但也可参加其他时段排班。'],
    ['mealPreferDistinct', '优先避免同一天重复打饭', '包含次日早餐；人数不足时允许重复，不阻止生成。'],
    ['balanceHistory', '按已发布历史次数均衡分配', '优先缩小同组人员在同一岗位上的累计次数差异。'],
    ['positionPreferDistinct', '尽量减少同岗位重复安排', '在满足人数和历史公平性后，尽量让人员轮换岗位。'],
    ['rolePreferences', '启用岗位优先人选', '打饭优先具备防爆资格的人；回流优先当天有候机楼任务的人。'],
  ]
  return <>
    <Card className="surface" title="轮班周期" extra={<Button icon={<PlusOutlined />} disabled={rules.cycle.length >= 60} onClick={() => change({ cycle: [...rules.cycle, { earlyGroup: 'REST', morningPowerGroup: 'NONE', eveningPowerGroup: 'NONE' }] })}>添加一天</Button>}>
      <p className="rules-description">从锚点日开始按下表循环，共 {rules.cycle.length} 天。工作日指定早下组，对组为晚下组。周期以工作日开始、休息日结束；生成时覆盖所选日期所在的连续工作日。</p>
      <Table size="small" pagination={false} rowKey="index" scroll={{ x: 640 }} dataSource={rules.cycle.map((day, index) => ({ ...day, index }))} columns={[
        { title: '周期日', render: (_, row) => `第 ${row.index + 1} 天`, width: 90 },
        { title: '早下组 / 休息', render: (_, row) => <Select aria-label={`第${row.index + 1}天早下组`} value={row.earlyGroup} options={[...groupOptions, { value: 'REST', label: '休息日' }]} onChange={earlyGroup => change({ cycle: rules.cycle.map((d, i) => i === row.index ? { ...d, earlyGroup, ...(earlyGroup === 'REST' ? { morningPowerGroup: 'NONE', eveningPowerGroup: 'NONE' } : {}) } : d) })} /> },
        ...(['morningPowerGroup', 'eveningPowerGroup'] as const).map(key => ({ title: key === 'morningPowerGroup' ? '早上推回组' : '晚上送仓组', render: (_: unknown, row: typeof rules.cycle[number] & { index: number }) => <Select aria-label={`第${row.index + 1}天${key === 'morningPowerGroup' ? '早上推回组' : '晚上送仓组'}`} disabled={!rules.powerBankEnabled || row.earlyGroup === 'REST'} value={row[key]} options={[...groupOptions, { value: 'NONE', label: '不安排' }]} onChange={value => change({ cycle: rules.cycle.map((d, i) => i === row.index ? { ...d, [key]: value } : d) })} /> })),
        { title: '操作', width: 70, render: (_, row) => <Button aria-label={`删除周期第${row.index + 1}天`} danger type="text" disabled={rules.cycle.length <= 2} icon={<DeleteOutlined />} onClick={() => change({ cycle: rules.cycle.filter((_, i) => i !== row.index) })} /> },
      ]} />
    </Card>
    <Card className="surface" title="时间段与剩余人员分配" extra={<Button icon={<PlusOutlined />} disabled={rules.slots.length >= 40} onClick={() => change({ slots: [...rules.slots, { code: 'SLOT_' + crypto.randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase(), start: '09:00', end: '10:00', dayOffset: 1, onlyBeforeWorkday: false, remainderPosition: '' }] })}>添加时段</Button>}>
      <p className="rules-description">时段按表格顺序生效，时间不能重叠。“剩余人员”只覆盖该岗位所属组，先满足其他岗位需求；选择“不自动分配”时，各岗仅按最低与最高人数安排。</p>
      <Table size="small" pagination={false} rowKey="code" scroll={{ x: 900 }} dataSource={rules.slots} columns={[
        { title: '顺序', width: 105, render: (_, row, index) => <Space size={0}><span>{index + 1}</span><Button size="small" type="text" aria-label={`上移时段${index + 1}`} icon={<ArrowUpOutlined />} disabled={!index} onClick={() => { const slots = [...rules.slots]; [slots[index - 1], slots[index]] = [row, slots[index - 1]]; change({ slots }) }} /><Button size="small" type="text" aria-label={`下移时段${index + 1}`} icon={<ArrowDownOutlined />} disabled={index === rules.slots.length - 1} onClick={() => { const slots = [...rules.slots]; [slots[index + 1], slots[index]] = [row, slots[index + 1]]; change({ slots }) }} /></Space> },
        { title: '日期', width: 100, render: (_, row) => <Select value={row.dayOffset} aria-label={`时段${row.code}日期`} options={[{ value: 0, label: '当天' }, { value: 1, label: '次日' }]} onChange={dayOffset => change({ slots: rules.slots.map(s => s === row ? { ...s, dayOffset } : s) })} /> },
        { title: '开始', width: 125, render: (_, row) => <Input type="time" aria-label={`时段${row.code}开始`} value={row.start} onChange={e => change({ slots: rules.slots.map(s => s === row ? { ...s, start: e.target.value } : s) })} /> },
        { title: '结束', width: 210, render: (_, row) => <Space><Input type="time" aria-label={`时段${row.code}结束`} disabled={row.end === null} value={row.end ?? ''} onChange={e => change({ slots: rules.slots.map(s => s === row ? { ...s, end: e.target.value } : s) })} /><Checkbox checked={row.end === null} onChange={e => change({ slots: rules.slots.map(s => s === row ? { ...s, end: e.target.checked ? null : '23:00' } : s) })}>航班结束</Checkbox></Space> },
        { title: '剩余人员安排到', width: 160, render: (_, row) => <Select value={row.remainderPosition} aria-label={`时段${row.code}剩余人员`} options={[{ value: '', label: '不自动分配' }, { value: 'TERMINAL', label: '候机楼' }, { value: 'BOMB', label: '防爆' }]} onChange={remainderPosition => change({ slots: rules.slots.map(s => s === row ? { ...s, remainderPosition } : s) })} /> },
        { title: '仅次日上班时启用', width: 155, render: (_, row) => <Switch aria-label={`时段${row.code}仅次日上班`} checked={row.onlyBeforeWorkday} onChange={onlyBeforeWorkday => change({ slots: rules.slots.map(s => s === row ? { ...s, onlyBeforeWorkday } : s) })} /> },
        { title: '操作', width: 65, render: (_, row) => <Popconfirm title="删除此时段及其标准岗位需求？" description="已有排班和日期例外不会被删除；引用旧时段的例外需重新调整。" onConfirm={() => change({ slots: rules.slots.filter(s => s !== row), replacementSlots: rules.replacementSlots.filter(c => c !== row.code) })}><Button type="text" danger aria-label={`删除时段${row.code}`} icon={<DeleteOutlined />} /></Popconfirm> },
      ]} />
    </Card>
    <Card className="surface" title="人员安排与公平性">
      <div className="rules-switches">{switches.map(([key, label, hint]) => <label key={key}><div><strong>{label}</strong><span>{hint}</span></div><Switch aria-label={label} checked={Boolean(rules[key])} onChange={value => change({ [key]: value })} /></label>)}</div>
      <Alert type="info" showIcon message="岗位资格和同一时段不能兼任正式岗位始终校验。过夜、推电宝为独立附加任务，可与正式岗位同时安排。" />
    </Card>
    <Card className="surface" title="候机楼替岗标记" extra={<Switch aria-label="启用替岗标记" checked={rules.replacementEnabled} onChange={replacementEnabled => change({ replacementEnabled })} />}>
      <div className="rules-policy-fields">
        <label><span>被替岗人员（防爆）</span><Select aria-label="被替岗人员" allowClear showSearch optionFilterProp="label" disabled={!rules.replacementEnabled} value={rules.replacementPersonId} options={people.map(p => ({ value: p.id, label: `${p.name} · ${p.groupCode}组` }))} onChange={replacementPersonId => change({ replacementPersonId: replacementPersonId ?? null })} /></label>
        <label><span>标记文字</span><Input aria-label="替岗标记文字" maxLength={10} disabled={!rules.replacementEnabled} value={rules.replacementLabel} onChange={e => change({ replacementLabel: e.target.value })} /></label>
        <label><span>适用时段</span><Select aria-label="替岗适用时段" mode="multiple" disabled={!rules.replacementEnabled} value={rules.replacementSlots} options={slotOptions} onChange={replacementSlots => change({ replacementSlots })} /></label>
      </div>
      <p className="rules-description">被替岗人员在防爆岗时，从同组候机楼名单按历史次数选择一人高亮标记；不改变正式岗位，可手动调整人选。</p>
    </Card>
    <Card className="surface" title="推电宝轮换" extra={<Switch aria-label="启用推电宝" checked={rules.powerBankEnabled} onChange={powerBankEnabled => change({ powerBankEnabled })} />}>
      <p className="rules-description">早晚由哪一组负责，在轮班周期表中设置。按下面名单顺序逐次轮换，跳过当天停用或不属于该组的人员；进度以已发布排班为准。</p>
      <div className="rules-policy-fields">
        <label><span>早上推回开始</span><Input type="time" aria-label="推回开始" value={rules.powerBankMorningStart} onChange={e => change({ powerBankMorningStart: e.target.value })} /></label>
        <label><span>早上推回结束</span><Input type="time" aria-label="推回结束" value={rules.powerBankMorningEnd} onChange={e => change({ powerBankMorningEnd: e.target.value })} /></label>
        <label><span>晚上送仓时段</span><Select aria-label="送仓时段" value={rules.powerBankEveningSlot} options={slotOptions} onChange={powerBankEveningSlot => change({ powerBankEveningSlot })} /></label>
      </div>
      <div className="rules-queues">{groups.map(group => {
        const ids = rules.powerBankOrder[group.code] ?? []
        const update = (next: string[]) => change({ powerBankOrder: { ...rules.powerBankOrder, [group.code]: next } })
        return <div key={group.code}><h3>{group.name}</h3><Select aria-label={`${group.name}添加轮换人员`} showSearch optionFilterProp="label" value={null} placeholder="添加轮换人员" options={people.filter(p => p.enabled && p.groupCode === group.code && !ids.includes(p.id)).map(p => ({ value: p.id, label: p.name }))} onChange={id => { if (id) update([...ids, id]) }} />
          <ol>{ids.map((id, index) => <li key={id}><Tag>{index + 1}</Tag><span>{people.find(p => p.id === id)?.name ?? `人员 ${id}`}</span><Space size={0}><Button type="text" aria-label={`${group.name}上移第${index + 1}人`} icon={<ArrowUpOutlined />} disabled={!index} onClick={() => { const next = [...ids]; [next[index - 1], next[index]] = [id, next[index - 1]]; update(next) }} /><Button type="text" aria-label={`${group.name}下移第${index + 1}人`} icon={<ArrowDownOutlined />} disabled={index === ids.length - 1} onClick={() => { const next = [...ids]; [next[index + 1], next[index]] = [id, next[index + 1]]; update(next) }} /><Button type="text" danger aria-label={`${group.name}移除第${index + 1}人`} icon={<DeleteOutlined />} onClick={() => update(ids.filter(p => p !== id))} /></Space></li>)}</ol>
        </div>
      })}</div>
    </Card>
  </>
}
