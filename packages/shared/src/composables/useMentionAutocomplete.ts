import { ref, computed, nextTick } from 'vue'
import type { Ref } from 'vue'
import { useChatStore } from '../stores/chat'

/**
 * @-mention autocomplete state machine for the message input: detects typing
 * after an "@", filters channel members, and inserts the selection back into
 * the textarea at the right cursor position.
 */
export function useMentionAutocomplete(options: {
  messageInput: Ref<string>
  messageInputRef: Ref<HTMLTextAreaElement | null>
  // Enter/Tab confirm a mention when the dropdown is open; otherwise Enter sends.
  onSend: () => void
}) {
  const { messageInput, messageInputRef, onSend } = options
  const chat = useChatStore()

  const mentionQuery = ref('')
  const mentionStartIndex = ref(-1)
  const showMentionDropdown = ref(false)
  const selectedMentionIndex = ref(0)

  // Extract unique users from messages for mention autocomplete
  const channelUsers = computed(() => {
    const userMap = new Map<number, { id: number; username: string }>()
    for (const msg of chat.messages) {
      if (!userMap.has(msg.user_id)) {
        userMap.set(msg.user_id, { id: msg.user_id, username: msg.username })
      }
    }
    return Array.from(userMap.values())
  })

  // Filtered users for mention autocomplete
  const filteredMentionUsers = computed(() => {
    if (!mentionQuery.value) return channelUsers.value.slice(0, 10)
    const query = mentionQuery.value.toLowerCase()
    return channelUsers.value
      .filter(u => u.username.toLowerCase().includes(query))
      .slice(0, 10)
  })

  function handleInputChange(event: Event) {
    const input = event.target as HTMLInputElement
    const value = input.value
    const cursorPos = input.selectionStart || 0

    // Find if we're in a mention context (after @)
    const textBeforeCursor = value.slice(0, cursorPos)
    const lastAtIndex = textBeforeCursor.lastIndexOf('@')

    if (lastAtIndex !== -1) {
      // Check if there's a space between @ and cursor (would end the mention)
      const textAfterAt = textBeforeCursor.slice(lastAtIndex + 1)
      if (!textAfterAt.includes(' ')) {
        mentionStartIndex.value = lastAtIndex
        mentionQuery.value = textAfterAt
        showMentionDropdown.value = true
        selectedMentionIndex.value = 0
        return
      }
    }

    // Not in mention context
    showMentionDropdown.value = false
    mentionQuery.value = ''
    mentionStartIndex.value = -1
  }

  function handleInputKeydown(event: KeyboardEvent) {
    // IME composition (e.g. Chinese input): Enter and arrow keys belong to the
    // candidate window, not to sending or mention navigation
    if (event.isComposing || event.keyCode === 229) return
    if (showMentionDropdown.value && filteredMentionUsers.value.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        selectedMentionIndex.value = Math.min(
          selectedMentionIndex.value + 1,
          filteredMentionUsers.value.length - 1
        )
      } else if (event.key === 'ArrowUp') {
        event.preventDefault()
        selectedMentionIndex.value = Math.max(selectedMentionIndex.value - 1, 0)
      } else if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault()
        const selectedUser = filteredMentionUsers.value[selectedMentionIndex.value]
        if (selectedUser) {
          selectMention(selectedUser)
        }
      } else if (event.key === 'Escape') {
        event.preventDefault()
        showMentionDropdown.value = false
      }
    } else if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      onSend()
    }
  }

  function selectMention(user: { id: number; username: string }) {
    if (mentionStartIndex.value === -1) return

    const input = messageInputRef.value
    if (!input) return

    const value = messageInput.value
    const beforeMention = value.slice(0, mentionStartIndex.value)
    const afterCursor = value.slice(input.selectionStart || mentionStartIndex.value + mentionQuery.value.length + 1)

    // Insert @username with a space after
    messageInput.value = `${beforeMention}@${user.username} ${afterCursor}`

    // Reset mention state
    showMentionDropdown.value = false
    mentionQuery.value = ''
    mentionStartIndex.value = -1

    // Focus back on input and set cursor position
    nextTick(() => {
      if (input) {
        const newCursorPos = beforeMention.length + user.username.length + 2 // +2 for @ and space
        input.focus()
        input.setSelectionRange(newCursorPos, newCursorPos)
      }
    })
  }

  return {
    showMentionDropdown,
    filteredMentionUsers,
    selectedMentionIndex,
    handleInputChange,
    handleInputKeydown,
    selectMention,
  }
}
