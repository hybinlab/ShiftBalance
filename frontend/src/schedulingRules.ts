export type CycleDay = { earlyGroup: 'FIRST' | 'OTHER' | 'REST'; morningPowerGroup: 'FIRST' | 'OTHER' | 'NONE'; eveningPowerGroup: 'FIRST' | 'OTHER' | 'NONE' }
export type RuleSlot = { code: string; start: string; end: string | null; dayOffset: number; onlyBeforeWorkday: boolean; remainderPosition: '' | 'TERMINAL' | 'BOMB' }
export type SchedulingRules = {
  cycle: CycleDay[]
  slots: RuleSlot[]
  flowNoRepeat: boolean
  supervisorRequired: boolean
  supervisorNoRepeat: boolean
  mealPreferDistinct: boolean
  balanceHistory: boolean
  positionPreferDistinct: boolean
  rolePreferences: boolean
  fixedExclusive: boolean
  replacementEnabled: boolean
  replacementPersonId: string | null
  replacementLabel: string
  replacementSlots: string[]
  powerBankEnabled: boolean
  powerBankOrder: Record<string, string[]>
  powerBankMorningStart: string
  powerBankMorningEnd: string
  powerBankEveningSlot: string
}

export const slotLabel = (s: RuleSlot) => `${s.dayOffset ? '次日 ' : ''}${s.start}–${s.end ?? '结束'}`
export const ruleTimeSlots = (rules: SchedulingRules) => rules.slots.map((s, i) => ({ code: s.code, sequence: i + 1, label: slotLabel(s), dayOffset: s.dayOffset }))
// Older schedule versions have no policy snapshot. Preserve their original meaning.
export const remainderPosition = (code: string, rules?: SchedulingRules) => rules
  ? rules.slots.find(s => s.code === code)?.remainderPosition ?? ''
  : code.startsWith('NEXT_DAY_') ? 'BOMB' : 'TERMINAL'
export const powerDutyLabel = (direction: string, rules?: SchedulingRules) => direction === 'TO_WAREHOUSE'
  ? (rules?.slots.find(s => s.code === rules.powerBankEveningSlot) ? slotLabel(rules.slots.find(s => s.code === rules.powerBankEveningSlot)!) : '19:30–结束')
  : `${rules?.powerBankMorningStart ?? '05:30'}–${rules?.powerBankMorningEnd ?? '08:00'}`
