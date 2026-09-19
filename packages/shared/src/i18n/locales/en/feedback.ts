import type { Message } from '../../types'
import type { Keys } from '../zh/feedback'

export const messages: Record<Keys, Message> = {
  'feedback.title': 'Something went wrong',
  'feedback.retryHint': 'Please try again later. If it keeps happening, copy the ID below and send it to an admin.',
  'feedback.copyId': 'Copy ID',
  'feedback.noRequestId': '(No ID returned in this response)',
}
