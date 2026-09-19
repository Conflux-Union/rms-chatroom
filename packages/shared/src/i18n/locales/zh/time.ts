// Date/time display words used by utils/datetime.ts. Callers pass numeric
// {m}/{d}/{y} plus the en-US short {month} and {weekday} so each locale can
// pick the parts it needs.
export const messages = {
  'time.yesterday': '昨天',
  'time.dayBeforeYesterday': '前天',
  'time.monthDay': '{m}月{d}日',
  'time.yearMonthDay': '{y}年{m}月{d}日',
} as const

export type Keys = keyof typeof messages
