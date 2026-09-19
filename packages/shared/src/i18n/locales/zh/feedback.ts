// Namespace: request-id error feedback card (utils/requestIdFeedback.ts).
export const messages = {
  'feedback.title': '操作出现问题',
  'feedback.retryHint': '请稍后重试；若持续出现，可复制下方 ID 发给管理员排查。',
  'feedback.copyId': '复制 ID',
  'feedback.noRequestId': '(本次响应未返回 ID)',
} as const

export type Keys = keyof typeof messages
