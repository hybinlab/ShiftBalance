import type { Person, ScheduleRecord } from './App'
import { chooseBombReplacements } from './bombReplacements.ts'

type Chip = { text: string; tone: string }
type ImageRow = { label: string; cells: Chip[][]; merged?: boolean }
// Use the publish response, so exports cannot accidentally include an unsaved draft.
export function scheduleImageRows(schedule: ScheduleRecord, people: Person[]): ImageRow[] {
  if (schedule.status !== 'PUBLISHED') throw new Error('仅支持导出已发布排班')
  const assignments = schedule.assignments
  const replacements = chooseBombReplacements(assignments.map((item) => ({ ...item, personIds: item.people.map((p) => p.id) })), people, schedule.bombReplacements)
  const slots = [...new Map(assignments.filter((a) => a.positionCode !== 'OVERNIGHT').map((a) => [a.slotCode, a])).values()]
    .sort((a, b) => a.slotSequence - b.slotSequence)
  const rows = slots.map((slot): ImageRow => {
    const current = assignments.filter((a) => a.slotCode === slot.slotCode)
    const used = new Set(current.filter((a) => a.positionCode !== 'OVERNIGHT').flatMap((a) => a.people.map((p) => p.id)))
    const chips = (role: string): Chip[] => current.filter((a) => a.positionCode === role).flatMap((a) => a.people.map((p) => {
      const replacement = role === 'TERMINAL' && replacements.get(slot.slotCode) === p.id
      const supervisor = role === 'TERMINAL' && p.terminalSupervisor
      return { text: `${p.name}${supervisor ? ' 监管' : ''}${replacement ? ' 换廖' : ''}`, tone: replacement ? 'replacement' : supervisor ? 'supervisor' : role }
    }))
    return {
      label: slot.slotCode.startsWith('NEXT_DAY') && !slot.slotLabel.includes('次日') ? `次日 ${slot.slotLabel}` : slot.slotLabel,
      cells: [chips('BOMB'), chips('TERMINAL'), chips('MEZZANINE'), chips('FLOW'), [
        ...chips('MEAL').map((chip) => ({ ...chip, text: `${chip.text} 打饭` })),
        ...people.filter((p) => p.enabled && !used.has(p.id)).map((p) => ({ text: p.name, tone: 'REST' })),
      ]],
    }
  })
  const extra: ImageRow[] = []
  for (const duty of assignments.filter((a) => a.positionCode === 'OVERNIGHT')) {
    extra.push({ label: '过夜安排', merged: true, cells: [[{ text: `${duty.groupCode}组 · ${duty.slotLabel}`, tone: 'REST' }, ...duty.people.map((p) => ({ text: p.name, tone: 'OVERNIGHT' }))]] })
  }
  for (const duty of [...(schedule.powerBankDuties ?? [])].sort((a, b) => Number(a.direction === 'TO_WAREHOUSE') - Number(b.direction === 'TO_WAREHOUSE'))) {
    extra.push({ label: '推电宝', merged: true, cells: [[{ text: `${duty.direction === 'TO_WAREHOUSE' ? '当天晚上送仓' : '当天早上推回'} · ${duty.groupCode}组 · ${duty.personName}`, tone: 'power' }]] })
  }
  const nextDay = slots.findIndex((slot) => slot.slotCode.startsWith('NEXT_DAY'))
  rows.splice(nextDay < 0 ? rows.length : nextDay, 0, ...extra)
  return rows
}

export async function downloadScheduleImage(schedule: ScheduleRecord, people: Person[], rotation: string) {
  const rows = scheduleImageRows(schedule, people)
  await document.fonts.ready
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('浏览器无法生成图片，请更换浏览器后重试')
  const width = 1600, margin = 48, timeWidth = 180
  const widths = [230, 310, 150, 210, 424]
  const ink = '#22354a', muted = '#718091', rule = '#dce3e9'
  const colors: Record<string, string> = { supervisor: '#2468bb', replacement: '#986515', MEAL: '#be4253', OVERNIGHT: '#7544ab', power: '#217b80' }
  const font = (size: number, bold = false) => `${bold ? 600 : 400} ${size}px "PingFang SC", "Microsoft YaHei", sans-serif`
  function wrap(value: string, available: number, size: number) {
    ctx!.font = font(size)
    const lines: string[] = []
    let line = ''
    for (const char of value) {
      if (line && ctx!.measureText(line + char).width > available) { lines.push(line); line = '' }
      line += char
    }
    if (line) lines.push(line)
    return lines
  }
  const layouts = rows.map((row) => row.cells.map((cell, column) => {
    if (row.merged) {
      const lines = wrap(cell.map((chip) => chip.text).join('    '), width - margin * 2 - timeWidth - 40, 23)
      return { items: [], lines, height: lines.length * 34 }
    }
    const columns = column === 2 ? 1 : column === 4 ? 3 : 2
    const cellWidth = (widths[column]! - 24) / columns
    const items = cell.map((chip) => {
      const suffix = chip.text.match(/(?: (?:监管|换廖|打饭))+$/)?.[0] ?? ''
      const name = suffix ? chip.text.slice(0, -suffix.length) : chip.text
      const size = column === 4 ? 21 : 27
      return { name: wrap(name, cellWidth - 12, size), role: suffix.trim().replaceAll(' ', ' · '), tone: chip.tone, size }
    })
    let offset = 0
    const placed = items.map((item, i) => {
      const start = Math.floor(i / columns) * columns
      const lineHeight = Math.max(...items.slice(start, start + columns).map((entry) => entry.name.length * (entry.size + 7) + (entry.role ? 22 : 0))) + 10
      const result = { ...item, x: 12 + (i % columns + 0.5) * cellWidth, y: offset, height: lineHeight }
      if (i % columns === columns - 1 || i === items.length - 1) offset += lineHeight
      return result
    })
    return { items: placed, lines: [], height: offset }
  }))
  const heights = layouts.map((cells, i) => Math.max(rows[i]!.merged ? 66 : 110, ...cells.map((cell) => cell.height + 26)))
  const top = 250, headerHeight = 62
  const height = top + headerHeight + heights.reduce((sum, h) => sum + h, 0) + 106
  // ponytail: a fixed 1600px layout at 2x keeps exports independent of viewport and device pixel ratio.
  canvas.width = width * 2
  canvas.height = height * 2
  ctx.scale(2, 2)
  const text = (value: string, x: number, y: number, size = 22, color = ink, bold = false, align: CanvasTextAlign = 'left') => {
    ctx.font = font(size, bold); ctx.fillStyle = color; ctx.textBaseline = 'middle'; ctx.textAlign = align; ctx.fillText(value, x, y)
  }
  const line = (x: number, y: number, endX: number, endY: number, color = rule) => {
    ctx.strokeStyle = color; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(endX, endY); ctx.stroke()
  }
  ctx.fillStyle = '#ffffff'; ctx.fillRect(0, 0, width, height)
  ctx.fillStyle = '#254e72'; ctx.fillRect(margin, 42, 6, 114)
  text('排班安排', margin + 26, 62, 25, ink, true)
  const date = new Date(`${schedule.scheduleDate}T12:00:00`)
  text(`${date.getMonth() + 1}月${date.getDate()}日`, margin + 24, 121, 56, ink, true)
  text(`${date.getFullYear()} / ${date.toLocaleDateString('zh-CN', { weekday: 'long' })}`, margin + 320, 128, 23, muted)
  text(rotation, width - margin, 77, 25, ink, true, 'right')
  text(`已发布  /  版本 ${schedule.version}  /  #${schedule.scheduleId}`, width - margin, 120, 19, muted, false, 'right')
  line(margin, 180, width - margin, 180)
  const legend = [['supervisor', '监管'], ['replacement', '换廖'], ['MEAL', '打饭'], ['OVERNIGHT', '过夜']]
  legend.forEach(([tone, label], i) => {
    const x = margin + i * 125
    ctx.fillStyle = colors[tone!]!; ctx.beginPath(); ctx.arc(x + 5, 215, 4, 0, Math.PI * 2); ctx.fill()
    text(label!, x + 20, 215, 19, muted)
  })
  text('当日岗位与次日早班', width - margin, 215, 19, muted, false, 'right')
  const headers = ['时间段', '防爆', '候机楼', '夹层', '回流 / 拉包', '休 / 维 / 备勤 / 打饭']
  ctx.fillStyle = '#254e72'; ctx.fillRect(margin, top, width - margin * 2, headerHeight)
  let hx = margin
  headers.forEach((label, i) => {
    const w = i === 0 ? timeWidth : widths[i - 1]!
    text(label, hx + w / 2, top + headerHeight / 2, 23, '#ffffff', true, 'center')
    hx += w
  })
  let y = top + headerHeight
  rows.forEach((row, index) => {
    const h = heights[index]!
    const nextDay = row.label.startsWith('次日')
    ctx.fillStyle = row.merged ? '#f1f5f7' : index % 2 ? '#f8fafb' : '#ffffff'
    ctx.fillRect(margin, y, width - margin * 2, h)
    if (nextDay) text('次日', margin + timeWidth / 2, y + h / 2 - 24, 16, muted, false, 'center')
    text(row.label.replace(/^次日\s*/, ''), margin + timeWidth / 2, y + h / 2 + (nextDay ? 8 : 0), 22, ink, true, 'center')
    let x = margin + timeWidth
    layouts[index]!.forEach((cell, i) => {
      const cellWidth = row.merged ? width - margin * 2 - timeWidth : widths[i]!
      if (!row.merged) line(x, y, x, y + h, '#e8edf1')
      if (row.merged) {
        cell.lines.forEach((value, n) => text(value, x + 24, y + (h - cell.height) / 2 + 17 + n * 34, 23, row.label === '过夜安排' ? colors.OVERNIGHT : colors.power))
      } else {
        if (!cell.items.length) text('—', x + cellWidth / 2, y + h / 2, 20, '#a4aeb9', false, 'center')
        cell.items.forEach((item) => {
          const cx = x + item.x, cy = y + (h - cell.height) / 2 + item.y
          const color = colors[item.tone] ?? (i === 4 ? '#75838e' : ink)
          item.name.forEach((value, n) => text(value, cx, cy + (n + 0.5) * (item.size + 7), item.size, color, !!item.role, 'center'))
          if (item.role) {
            const ry = cy + item.name.length * (item.size + 7) + 9
            ctx.font = font(15)
            const badgeWidth = ctx.measureText(item.role).width + 18
            ctx.fillStyle = item.tone === 'replacement' ? '#fff0cc' : item.tone === 'MEAL' ? '#fcecef' : '#e8f1fc'
            ctx.beginPath(); ctx.roundRect(cx - badgeWidth / 2, ry - 10, badgeWidth, 21, 3); ctx.fill()
            text(item.role, cx, ry, 15, color, false, 'center')
          }
        })
      }
      x += cellWidth
    })
    y += h
    line(margin, y, width - margin, y)
  })
  text('过夜与推电宝为独立职责，可同时承担常规岗位。', margin, y + 43, 18, muted)
  text('SHIFTBALANCE', width - margin, y + 43, 17, muted, false, 'right')
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((result) => result ? resolve(result) : reject(new Error('图片生成失败，请重试')), 'image/png'))
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = `排班表_${schedule.scheduleDate}_v${schedule.version}.png`
  document.body.appendChild(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
