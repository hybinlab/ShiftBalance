import assert from 'node:assert/strict'
import { chooseBombReplacements } from '../src/bombReplacements.ts'

const morning = 'DAY_0900_1130'
const afternoon = 'DAY_1430_1730'
const people = [
  { id: '1', name: '廖家史', enabled: true, historicalCounts: {} },
  { id: '2', name: 'A', enabled: true, historicalCounts: { BOMB: 0 } },
  { id: '3', name: 'B', enabled: true, historicalCounts: { BOMB: 0 } },
  { id: '4', name: 'C', enabled: true, historicalCounts: { BOMB: 10 } },
  { id: '5', name: 'D', enabled: false, historicalCounts: {} },
]
const assignments = [morning, afternoon].flatMap((slotCode) => [
  { slotCode, positionCode: 'BOMB', groupCode: 'A', personIds: ['1'] },
  { slotCode, positionCode: 'TERMINAL', groupCode: 'A', personIds: ['2', '3', '4', '5'] },
])
const before = structuredClone(assignments)
const choose = (preferred = {}, roster = assignments, staff = people) =>
  Object.fromEntries(chooseBombReplacements(roster, staff, preferred))

assert.deepEqual(choose(), { [morning]: '2', [afternoon]: '3' })
// A manual choice wins over fairness and survives a save/reload with changed history.
const saved = JSON.parse(JSON.stringify(choose({ [morning]: '4', [afternoon]: '3' })))
assert.deepEqual(saved, { [morning]: '4', [afternoon]: '3' })
assert.deepEqual(choose(saved, assignments, people.map((person) => ({
  ...person, historicalCounts: { BOMB: person.id === '4' ? 100 : 0 },
}))), saved)
// Moving the chosen person out of that terminal roster must replace the stale marker.
const moved = assignments.map((item) => item.slotCode === morning && item.positionCode === 'TERMINAL'
  ? { ...item, personIds: ['2', '3', '5'] } : item)
assert.deepEqual(choose(saved, moved), { [morning]: '2', [afternoon]: '3' })
const withoutLiao = assignments.map((item) => item.slotCode === morning && item.positionCode === 'BOMB'
  ? { ...item, personIds: [] } : item)
assert.deepEqual(choose(saved, withoutLiao), { [afternoon]: '3' })
assert.deepEqual(choose({ [morning]: '5', [afternoon]: '1', NIGHT_UNTIL_END: '4' }), choose())
const otherGroup = assignments.map((item) => item.positionCode === 'TERMINAL'
  ? { ...item, groupCode: 'B' } : item)
assert.deepEqual(choose(saved, otherGroup), {})
assert.deepEqual(assignments, before, 'Replacement markers must never swap formal job assignments')
console.log('Bomb replacement selection checks passed')
