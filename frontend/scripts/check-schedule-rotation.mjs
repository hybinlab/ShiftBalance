import assert from 'node:assert/strict'
import { groupForDate, workPeriodStartDate } from '../src/scheduleRotation.ts'

const anchor = { anchorWorkDate: '2026-10-12', firstEarlyDownGroup: 'A' }
const groups = [{ code: 'A' }, { code: 'B' }]
assert.deepEqual(groupForDate('2026-10-12', anchor, groups), { isWorkday: true, early: 'A', late: 'B' })
assert.deepEqual(groupForDate('2026-10-13', anchor, groups), { isWorkday: true, early: 'B', late: 'A' })
assert.deepEqual(groupForDate('2026-10-14', anchor, groups), { isWorkday: false, early: null, late: null })
assert.deepEqual(groupForDate('2026-10-16', anchor, groups), { isWorkday: true, early: 'B', late: 'A' })
assert.equal(workPeriodStartDate('2026-10-13', anchor), '2026-10-12')
assert.equal(workPeriodStartDate('2026-10-14', anchor), null)
