import assert from 'node:assert/strict'
import { groupForDate, workPeriodStartDate, workPeriodDates } from '../src/scheduleRotation.ts'

const anchor = { anchorWorkDate: '2026-10-12', firstEarlyDownGroup: 'A' }
const groups = [{ code: 'A' }, { code: 'B' }]
assert.deepEqual(groupForDate('2026-10-12', anchor, groups), { isWorkday: true, early: 'A', late: 'B' })
assert.deepEqual(groupForDate('2026-10-13', anchor, groups), { isWorkday: true, early: 'B', late: 'A' })
assert.deepEqual(groupForDate('2026-10-14', anchor, groups), { isWorkday: false, early: null, late: null })
assert.deepEqual(groupForDate('2026-10-16', anchor, groups), { isWorkday: true, early: 'B', late: 'A' })
assert.equal(workPeriodStartDate('2026-10-13', anchor), '2026-10-12')
assert.equal(workPeriodStartDate('2026-10-14', anchor), null)
const custom = { ...anchor, rules: { cycle: ['FIRST', 'FIRST', 'OTHER', 'REST'].map(earlyGroup => ({ earlyGroup })) } }
assert.deepEqual(workPeriodDates('2026-10-14', custom), ['2026-10-12', '2026-10-13', '2026-10-14'])
assert.deepEqual(groupForDate('2026-10-14', custom, groups), { isWorkday: true, early: 'B', late: 'A' })
assert.deepEqual(workPeriodDates('2026-10-15', custom), [])
assert.equal(workPeriodStartDate('2026-10-10', custom), '2026-10-08')
console.log('Default and configurable rotation checks passed')
