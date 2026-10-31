type Assignment = {
  slotCode: string
  positionCode: string
  groupCode: string
  personIds: string[]
}
type Person = {
  id: string
  name: string
  enabled: boolean
  historicalCounts: Record<string, number>
}

export function chooseBombReplacements(
  assignments: Assignment[],
  people: Person[],
  preferred: Record<string, string> = {},
) {
  const replacementBySlot = new Map<string, string>()
  const original = people.find((person) => person.name === '廖家史')
  if (!original) return replacementBySlot
  const candidatesBySlot = new Map<string, Person[]>()
  const selectedCounts = new Map<string, number>()

  for (const slotCode of ['DAY_0900_1130', 'DAY_1430_1730']) {
    const bomb = assignments.find((item) => item.slotCode === slotCode
      && item.positionCode === 'BOMB' && item.personIds.includes(original.id))
    if (!bomb) continue
    const terminal = assignments.find((item) => item.slotCode === slotCode
      && item.groupCode === bomb.groupCode && item.positionCode === 'TERMINAL')
    if (!terminal) continue
    const candidates = people.filter((person) => terminal.personIds.includes(person.id) && person.enabled)
    candidatesBySlot.set(slotCode, candidates)
    if (candidates.some((person) => person.id === preferred[slotCode])) {
      replacementBySlot.set(slotCode, preferred[slotCode])
      selectedCounts.set(preferred[slotCode], (selectedCounts.get(preferred[slotCode]) ?? 0) + 1)
    }
  }

  // ponytail: preserve valid saved choices; fill missing markers with the existing historical-count heuristic.
  for (const [slotCode, candidates] of candidatesBySlot) {
    if (replacementBySlot.has(slotCode)) continue
    candidates.sort((a, b) => ((a.historicalCounts.BOMB ?? 0) + (selectedCounts.get(a.id) ?? 0))
      - ((b.historicalCounts.BOMB ?? 0) + (selectedCounts.get(b.id) ?? 0))
      || a.name.localeCompare(b.name, 'zh-CN'))
    const replacement = candidates[0]
    if (!replacement) continue
    selectedCounts.set(replacement.id, (selectedCounts.get(replacement.id) ?? 0) + 1)
    replacementBySlot.set(slotCode, replacement.id)
  }
  return replacementBySlot
}
