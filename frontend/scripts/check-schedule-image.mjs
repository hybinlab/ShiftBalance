import assert from 'node:assert/strict'
import { scheduleImageRows } from '../src/scheduleImage.ts'

export const people = ['廖家史', '黄勇斌', '唐龙', '刘雨鑫', '吴世富', '石蓉昌', '骆灿', '甘春霖', '黎哲成', '宾昕宇', '阳菲莉', '李文锦', '王小曼', '刘芳全', '黄在显', '钟绍凌', '邹运谷', '莫少波'].map((name, i) => ({ id: String(i), name, enabled: true, groupCode: i < 9 ? 'A' : 'B', historicalCounts: {} }))
const slots = ['DAY_0900_1130', 'DAY_1130_1430', 'DAY_1430_1730', 'DAY_1730_1930', 'NIGHT_UNTIL_END', 'NEXT_DAY_0530_0800', 'NEXT_DAY_0800_0900']
const labels = ['09:00–11:30', '11:30–14:30', '14:30–17:30', '17:30–19:30', '19:30–结束', '05:30–08:00', '08:00–09:00']
export const schedule = {
  scheduleId: 123, scheduleDate: '2026-11-07', version: 2, status: 'PUBLISHED',
  bombReplacements: { DAY_0900_1130: '1', DAY_1430_1730: '1' },
  assignments: slots.flatMap((slotCode, i) => ['BOMB', 'TERMINAL', 'MEZZANINE', 'FLOW', 'MEAL'].map((positionCode, col) => ({
    slotCode, slotLabel: labels[i], slotSequence: i, positionCode, groupCode: 'A',
    people: (col === 0 ? [0, 2, 3] : col === 1 ? [1, 4, 5, 6, 7] : col === 2 ? [8] : col === 3 ? [9, 10] : [11, 12, 13])
      .map((id) => ({ ...people[id], terminalSupervisor: col === 1 && id === 1 })),
  }))),
  powerBankDuties: [
    { direction: 'TO_WAREHOUSE', groupCode: 'B', personName: '刘芳全' },
    { direction: 'FROM_WAREHOUSE', groupCode: 'B', personName: '王小曼' },
  ],
}
schedule.assignments.push({ slotCode: slots[4], slotLabel: labels[4], slotSequence: 4, positionCode: 'OVERNIGHT', groupCode: 'A', people: [people[0], people[2]] })
const before = JSON.stringify(schedule)
const rows = scheduleImageRows(schedule, people)
assert.equal(rows.length, 10)
assert.deepEqual(rows.slice(5, 8).map((row) => row.label), ['过夜安排', '推电宝', '推电宝'])
assert.match(rows[6].cells[0][0].text, /当天早上推回/)
assert.match(rows[8].label, /次日/)
assert.deepEqual(rows[0].cells[1][0], { text: '黄勇斌 监管 换廖', tone: 'replacement' })
assert.equal(rows[0].cells[4].filter((chip) => chip.tone === 'MEAL').length, 3)
assert.equal(rows[4].cells[0].filter((chip) => chip.text === '廖家史').length, 1, 'Overnight duty must retain the regular post')
assert.throws(() => scheduleImageRows({ ...schedule, status: 'CANDIDATE' }, people), /已发布/)
assert.equal(JSON.stringify(schedule), before)
console.log('Schedule PNG row checks passed')
