import type { Message } from '../../types'
import type { Keys } from '../zh/time'

export const messages: Record<Keys, Message> = {
  'time.yesterday': 'Yesterday',
  // English has no compact word for the day before yesterday; show the weekday.
  'time.dayBeforeYesterday': ({ weekday }) => String(weekday ?? ''),
  'time.monthDay': ({ month, d }) => `${month} ${d}`,
  'time.yearMonthDay': ({ month, d, y }) => `${month} ${d}, ${y}`,
}
