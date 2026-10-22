export type RotationAnchor = { anchorWorkDate: string; firstEarlyDownGroup: string }
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
  const offset = dayDifference(template.anchorWorkDate, date)
  const cycleDay = mod(offset, 4)
  if (cycleDay >= 2) return { isWorkday: false, early: null, late: null }
  const workdayIndex = Math.floor(offset / 4) * 2 + cycleDay
  const rotationIndex = mod(workdayIndex, 4)
  const other = groups.find((group) => group.code !== template.firstEarlyDownGroup)?.code
  if (!other) return null
  const early = rotationIndex === 0 || rotationIndex === 3 ? template.firstEarlyDownGroup : other
  return { isWorkday: true, early, late: early === template.firstEarlyDownGroup ? other : template.firstEarlyDownGroup }
}

export function workPeriodStartDate(date: string, template: RotationAnchor | null) {
  if (!template) return null
  const dayInCycle = mod(dayDifference(template.anchorWorkDate, date), 4)
  if (dayInCycle >= 2) return null
  const shifted = new Date(`${date}T00:00:00Z`)
  shifted.setUTCDate(shifted.getUTCDate() - dayInCycle)
  return shifted.toISOString().slice(0, 10)
}
