import type { SchedulingRules } from './schedulingRules'
export type RotationAnchor = { anchorWorkDate: string; firstEarlyDownGroup: string; rules?: SchedulingRules }
export type RotationGroup = { code: string }
export type RotationDay = { isWorkday: boolean; early: string | null; late: string | null }

function dayDifference(from: string, to: string) {
  const [fromYear, fromMonth, fromDay] = from.split('-').map(Number)
  const [toYear, toMonth, toDay] = to.split('-').map(Number)
  return Math.round((Date.UTC(toYear, toMonth - 1, toDay) - Date.UTC(fromYear, fromMonth - 1, fromDay)) / 86_400_000)
}

function mod(value: number, divisor: number) {
  return ((value % divisor) + divisor) % divisor
}

export function groupForDate(
  date: string,
  template: RotationAnchor | null,
  groups: readonly RotationGroup[],
): RotationDay | null {
  if (!template || groups.length !== 2) return null
  const day = cycleForDate(date, template)
  if (day === 'REST') return { isWorkday: false, early: null, late: null }
  const other = groups.find((group) => group.code !== template.firstEarlyDownGroup)?.code
  if (!other) return null
  const early = day === 'FIRST' ? template.firstEarlyDownGroup : other
  return { isWorkday: true, early, late: early === template.firstEarlyDownGroup ? other : template.firstEarlyDownGroup }
}

function cycleForDate(date: string, template: RotationAnchor) {
  const days = template.rules?.cycle.map(d => d.earlyGroup) ?? ['FIRST', 'OTHER', 'REST', 'REST', 'OTHER', 'FIRST', 'REST', 'REST']
  return days[mod(dayDifference(template.anchorWorkDate, date), days.length)]
}

function shiftDay(date: string, offset: number) {
  const shifted = new Date(`${date}T00:00:00Z`)
  shifted.setUTCDate(shifted.getUTCDate() + offset)
  return shifted.toISOString().slice(0, 10)
}

export function workPeriodStartDate(date: string, template: RotationAnchor | null) {
  if (!template || cycleForDate(date, template) === 'REST') return null
  let start = date
  for (let i = 0; i < 60 && cycleForDate(shiftDay(start, -1), template) !== 'REST'; i++) start = shiftDay(start, -1)
  return start
}

export function workPeriodDates(date: string, template: RotationAnchor | null) {
  const start = workPeriodStartDate(date, template)
  if (!start || !template) return []
  const dates: string[] = []
  for (let i = 0; i < 60; i++) {
    const day = shiftDay(start, i)
    if (cycleForDate(day, template) === 'REST') break
    dates.push(day)
  }
  return dates
}
