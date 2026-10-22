import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, App as AntApp, Avatar, Button, Card, Empty, Form, Input, Modal, Select, Space, Switch, Table, Tag, type TableColumnsType } from 'antd'
import { EditOutlined, PlusOutlined, TeamOutlined, ToolOutlined } from '@ant-design/icons'

export type Group = { code: string; name: string }
export type Position = { code: string; name: string }
export type Person = {
  id: string
  name: string
  groupCode: string
  enabled: boolean
  remark: string | null
  qualifiedPositionCodes: string[]
  historicalCounts: Record<string, number>
}
export type ManagementModule = 'people' | 'groups' | 'positions'
export type ApiRequest = <T,>(base: string, path: string, init?: RequestInit) => Promise<T>

type Props = {
  module: ManagementModule
  apiBase: string
  apiRequest: ApiRequest
  onDataChanged: () => void
}
type PersonFormValues = {
  name: string
  groupCode: string
  enabled: boolean
  remark?: string
  qualifiedPositionCodes: string[]
}

const today = () => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

export default function ManagementPages({ module, apiBase, apiRequest, onDataChanged }: Props) {
  const { message } = AntApp.useApp()
  const [groups, setGroups] = useState<Group[]>([])
  const [positions, setPositions] = useState<Position[]>([])
  const [people, setPeople] = useState<Person[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyPersonId, setBusyPersonId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [groupFilter, setGroupFilter] = useState<string>()
  const [selectedPosition, setSelectedPosition] = useState('')
  const [editingPerson, setEditingPerson] = useState<Person | null>(null)
  const [personModalOpen, setPersonModalOpen] = useState(false)
  const [savingPerson, setSavingPerson] = useState(false)
  const [form] = Form.useForm<PersonFormValues>()

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextGroups, nextPositions, nextPeople] = await Promise.all([
        apiRequest<Group[]>(apiBase, '/api/groups'),
        apiRequest<Position[]>(apiBase, '/api/positions'),
        apiRequest<Person[]>(apiBase, `/api/people?asOf=${today()}`),
      ])
      setGroups(nextGroups)
      setPositions(nextPositions)
      setPeople(nextPeople)
      setSelectedPosition((current) => nextPositions.some((item) => item.code === current) ? current : nextPositions[0]?.code ?? '')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取管理数据失败')
    } finally {
      setLoading(false)
    }
  }, [apiBase, apiRequest])

  useEffect(() => { void load() }, [load, module])

  const enabledPeople = useMemo(() => people.filter((person) => person.enabled), [people])
  const visiblePeople = useMemo(() => {
    const query = search.trim().toLocaleLowerCase()
    return people.filter((person) => (!groupFilter || person.groupCode === groupFilter)
      && (!query || person.name.toLocaleLowerCase().includes(query) || person.remark?.toLocaleLowerCase().includes(query)))
  }, [people, groupFilter, search])
  const positionMap = useMemo(() => new Map(positions.map((position) => [position.code, position.name])), [positions])
  const groupMap = useMemo(() => new Map(groups.map((group) => [group.code, group.name])), [groups])
  const formPositions = [
    ...positions,
    ...(editingPerson?.qualifiedPositionCodes ?? [])
      .filter((code) => !positionMap.has(code))
      .map((code) => ({ code, name: `${code}（已停用）` })),
  ]

  const savePerson = async (person: Person, changes: Partial<PersonFormValues>) => {
    setBusyPersonId(person.id)
    try {
      const body: Record<string, unknown> = {
        name: changes.name ?? person.name,
        enabled: changes.enabled ?? person.enabled,
        remark: changes.remark === undefined ? person.remark : changes.remark.trim() || null,
        groupCode: changes.groupCode ?? person.groupCode,
      }
      // Group-only edits must not re-submit a person's entire qualification history.
      if (changes.qualifiedPositionCodes !== undefined) body.qualifiedPositionCodes = changes.qualifiedPositionCodes
      await apiRequest<Person>(apiBase, `/api/people/${person.id}?asOf=${today()}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      })
      await load()
      onDataChanged()
      return true
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '保存失败')
      return false
    } finally {
      setBusyPersonId(null)
    }
  }

  const openPerson = (person?: Person) => {
    setEditingPerson(person ?? null)
    form.setFieldsValue(person ? {
      name: person.name,
      groupCode: person.groupCode,
      enabled: person.enabled,
      remark: person.remark ?? '',
      qualifiedPositionCodes: person.qualifiedPositionCodes,
    } : {
      name: '',
      groupCode: groups[0]?.code,
      enabled: true,
      remark: '',
      qualifiedPositionCodes: [],
    })
    setPersonModalOpen(true)
  }

  const submitPerson = async () => {
    const values = await form.validateFields()
    setSavingPerson(true)
    try {
      if (editingPerson) {
        const saved = await savePerson(editingPerson, values)
        if (!saved) return
      } else {
        await apiRequest<Person>(apiBase, '/api/people', {
          method: 'POST',
          body: JSON.stringify({ ...values, remark: values.remark?.trim() || null, effectiveFrom: today() }),
        })
        await load()
        onDataChanged()
      }
      setPersonModalOpen(false)
      message.success(editingPerson ? '人员资料已更新' : '人员已添加')
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '保存人员失败')
    } finally {
      setSavingPerson(false)
    }
  }

  const peopleColumns: TableColumnsType<Person> = [
    { title: '人员', dataIndex: 'name', render: (name: string) => <Space><Avatar size="small" className="management-avatar">{name.slice(0, 1)}</Avatar><span>{name}</span></Space> },
    { title: '所在组', dataIndex: 'groupCode', width: 110, render: (code: string) => <Tag color={code === 'A' ? 'blue' : 'purple'}>{groupMap.get(code) ?? `${code}组`}</Tag> },
    { title: '岗位资格', dataIndex: 'qualifiedPositionCodes', render: (codes: string[]) => codes.length ? <Space size={[0, 4]} wrap>{codes.map((code) => <Tag key={code}>{positionMap.get(code) ?? code}</Tag>)}</Space> : <span className="muted-text">未配置</span> },
    { title: '状态', dataIndex: 'enabled', width: 90, render: (enabled: boolean) => <Tag color={enabled ? 'success' : 'default'}>{enabled ? '在岗' : '停用'}</Tag> },
    { title: '操作', key: 'actions', width: 90, align: 'right', render: (_, person) => <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openPerson(person)}>编辑</Button> },
  ]

  const moduleTitles: Record<ManagementModule, { title: string; subtitle: string }> = {
    people: { title: '人员管理', subtitle: '维护人员信息、所在组别、在岗状态和岗位资格。' },
    groups: { title: '分组管理', subtitle: '查看 A/B 轮班组人员，并调整人员当前归属。组别变更从今天起生效。' },
    positions: { title: '岗位管理', subtitle: '按岗位查看并维护人员资格；岗位目录遵循当前排班规则。' },
  }

  return (
    <section className="management-page">
      <header className="page-heading management-heading">
        <div><p className="eyebrow">基础资料</p><h1>{moduleTitles[module].title}</h1><p className="page-subtitle">{moduleTitles[module].subtitle}</p></div>
        {module === 'people' && <Button type="primary" icon={<PlusOutlined />} onClick={() => openPerson()}>新增人员</Button>}
      </header>

      {error && <Alert className="management-alert" type="error" showIcon message="管理数据读取失败" description={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}

      {module === 'people' && <Card className="surface management-card" loading={loading}>
        <div className="management-toolbar"><Input.Search allowClear placeholder="搜索姓名或备注" value={search} onChange={(event) => setSearch(event.target.value)} /><Select allowClear placeholder="全部组别" value={groupFilter} onChange={setGroupFilter} options={groups.map((group) => ({ value: group.code, label: group.name }))} /></div>
        <Table<Person> rowKey="id" size="middle" columns={peopleColumns} dataSource={visiblePeople} pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 人` }} locale={{ emptyText: <Empty description="暂无人员资料" /> }} />
      </Card>}

      {module === 'groups' && <>
        <div className="management-group-grid">
          {groups.map((group) => {
            const members = people.filter((person) => person.groupCode === group.code)
            return <Card key={group.code} className={`surface management-group-card ${group.code === 'B' ? 'violet' : ''}`}>
              <div className="management-group-heading"><span className="management-group-icon"><TeamOutlined /></span><div><h2>{group.name}</h2><small>{members.length} 人 · {members.filter((person) => person.enabled).length} 人在岗</small></div><Tag color={group.code === 'A' ? 'blue' : 'purple'}>{group.code} 组</Tag></div>
              <Space size={[0, 8]} wrap className="management-member-list">{members.map((person) => <Tag key={person.id} color={person.enabled ? undefined : 'default'}>{person.name}</Tag>)}</Space>
            </Card>
          })}
          {!groups.length && !loading && <Card className="surface"><Empty description="没有启用的组别" /></Card>}
        </div>
        <Card className="surface management-card" title="调整人员所属组" extra={<span className="muted-text">共 {enabledPeople.length} 名在岗人员</span>} loading={loading}>
          <Table<Person> rowKey="id" size="middle" dataSource={people} pagination={{ pageSize: 10, showSizeChanger: false }} columns={[
            { title: '人员', dataIndex: 'name', render: (name: string, person) => <Space><Avatar size="small" className="management-avatar">{name.slice(0, 1)}</Avatar><span>{name}</span>{!person.enabled && <Tag>停用</Tag>}</Space> },
            { title: '当前组别', dataIndex: 'groupCode', width: 150, render: (code: string) => groupMap.get(code) ?? code },
            { title: '岗位资格数', dataIndex: 'qualifiedPositionCodes', width: 130, render: (codes: string[]) => `${codes.length} 个岗位` },
            { title: '调整到', key: 'move', width: 180, render: (_, person) => <Select aria-label={`调整${person.name}的组别`} size="small" value={person.groupCode} disabled={busyPersonId === person.id || groups.length < 2} options={groups.map((group) => ({ value: group.code, label: group.name }))} onChange={(groupCode) => void savePerson(person, { groupCode })} /> },
          ]} locale={{ emptyText: <Empty description="暂无人员" /> }} />
        </Card>
      </>}

      {module === 'positions' && <>
        <div className="management-position-grid">
          {positions.map((position) => {
            const qualified = people.filter((person) => person.qualifiedPositionCodes.includes(position.code))
            return <button type="button" key={position.code} className={`management-position-card ${selectedPosition === position.code ? 'selected' : ''}`} onClick={() => setSelectedPosition(position.code)}>
              <span className="management-position-icon"><ToolOutlined /></span><span><strong>{position.name}</strong><small>{qualified.length} 人具备资格</small></span>
            </button>
          })}
        </div>
        <Card className="surface management-card" loading={loading} title={positions.find((position) => position.code === selectedPosition)?.name ?? '岗位资格'} extra={<span className="muted-text">开关用于维护人员资格</span>}>
          {selectedPosition ? <Table<Person> rowKey="id" size="middle" dataSource={visiblePeople} pagination={{ pageSize: 10, showSizeChanger: false }} columns={[
            { title: '人员', dataIndex: 'name', render: (name: string, person) => <Space><Avatar size="small" className="management-avatar">{name.slice(0, 1)}</Avatar><span>{name}</span>{!person.enabled && <Tag>停用</Tag>}</Space> },
            { title: '所在组', dataIndex: 'groupCode', width: 130, render: (code: string) => groupMap.get(code) ?? code },
            { title: '当前资格', key: 'qualification', width: 140, render: (_, person) => {
              const hasQualification = person.qualifiedPositionCodes.includes(selectedPosition)
              return <Switch checked={hasQualification} loading={busyPersonId === person.id} aria-label={`${person.name}${hasQualification ? '取消' : '添加'}${positionMap.get(selectedPosition) ?? ''}资格`} onChange={(checked) => {
                const codes = new Set(person.qualifiedPositionCodes)
                checked ? codes.add(selectedPosition) : codes.delete(selectedPosition)
                void savePerson(person, { qualifiedPositionCodes: [...codes] })
              }} />
            } },
          ]} locale={{ emptyText: <Empty description="没有符合筛选条件的人员" /> }} /> : <Empty description="暂无可维护的岗位" />}
        </Card>
      </>}

      <Modal open={personModalOpen} title={editingPerson ? '编辑人员' : '新增人员'} onCancel={() => setPersonModalOpen(false)} onOk={() => void submitPerson()} confirmLoading={savingPerson} okText="保存" cancelText="取消" destroyOnHidden forceRender>
        <Form<PersonFormValues> form={form} layout="vertical" initialValues={{ enabled: true, qualifiedPositionCodes: [] }}>
          <Form.Item name="name" label="姓名" rules={[{ required: true, whitespace: true, message: '请输入姓名' }, { max: 100, message: '姓名最多 100 个字符' }]}><Input maxLength={100} /></Form.Item>
          <Space className="management-form-row" align="start" size={16}>
            <Form.Item name="groupCode" label="所在组" rules={[{ required: true, message: '请选择组别' }]}><Select style={{ minWidth: 180 }} options={groups.map((group) => ({ value: group.code, label: group.name }))} /></Form.Item>
            <Form.Item name="enabled" label="在岗状态" valuePropName="checked"><Switch checkedChildren="在岗" unCheckedChildren="停用" /></Form.Item>
          </Space>
          <Form.Item name="qualifiedPositionCodes" label="岗位资格"><Select mode="multiple" allowClear placeholder="选择该人员具备资格的岗位" options={formPositions.map((position) => ({ value: position.code, label: position.name }))} /></Form.Item>
          <Form.Item name="remark" label="备注" rules={[{ max: 500, message: '备注最多 500 个字符' }]}><Input.TextArea rows={3} maxLength={500} showCount /></Form.Item>
        </Form>
      </Modal>
    </section>
  )
}
