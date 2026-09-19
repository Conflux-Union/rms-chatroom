import { ref, computed } from 'vue'
import axios from 'axios'
import type { Message } from '../types'
import { dialog } from '../components/ui'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { t } from '../i18n'

const API_BASE = import.meta.env.VITE_API_BASE || ''

/**
 * User muting: the mute dialog (scope/duration/reason), the mute status of the
 * current user in the open channel (REST check combined with the WS-pushed
 * state from the chat store), and the confirm/check calls.
 */
export function useMuteDialog() {
  const chat = useChatStore()
  const auth = useAuthStore()

  const muteDialog = ref<{
    visible: boolean
    userId: number | null
    username: string
    scope: 'global' | 'server' | 'channel'
    duration: 'permanent' | '10m' | '1h' | '1d' | 'custom'
    customMinutes: number
    reason: string
  }>({
    visible: false,
    userId: null,
    username: '',
    scope: 'channel',
    duration: 'permanent',
    customMinutes: 60,
    reason: '',
  })

  const scopeOptions = computed(() => [
    { label: t('voice.muteScopeChannel'), value: 'channel' },
    { label: t('voice.muteScopeServer'), value: 'server' },
    { label: t('voice.muteScopeGlobal'), value: 'global' },
  ])

  const durationOptions = computed(() => [
    { label: t('voice.muteDurationPermanent'), value: 'permanent' },
    { label: t('voice.muteDuration10m'), value: '10m' },
    { label: t('voice.muteDuration1h'), value: '1h' },
    { label: t('voice.muteDuration1d'), value: '1d' },
    { label: t('voice.muteDurationCustom'), value: 'custom' },
  ])

  // Mute status - combine local check with WebSocket error
  const localMuted = ref(false)
  const localMuteReason = ref('')
  const isMuted = computed(() => localMuted.value || chat.isMutedByWs)
  const muteReason = computed(() => localMuteReason.value || chat.muteReasonByWs)

  function showMuteDialog(message: Message) {
    muteDialog.value = {
      visible: true,
      userId: message.user_id,
      username: message.username,
      scope: 'channel',
      duration: 'permanent',
      customMinutes: 60,
      reason: '',
    }
  }

  function hideMuteDialog() {
    muteDialog.value.visible = false
    muteDialog.value.userId = null
  }

  async function confirmMute() {
    if (!muteDialog.value.userId || !chat.currentChannel) return

    let mutedUntil: string | null = null
    if (muteDialog.value.duration !== 'permanent') {
      let minutes = 0
      switch (muteDialog.value.duration) {
        case '10m': minutes = 10; break
        case '1h': minutes = 60; break
        case '1d': minutes = 1440; break
        case 'custom': minutes = muteDialog.value.customMinutes; break
      }
      const until = new Date()
      until.setMinutes(until.getMinutes() + minutes)
      mutedUntil = until.toISOString()
    }

    const payload: any = {
      user_id: muteDialog.value.userId,
      scope: muteDialog.value.scope,
      reason: muteDialog.value.reason || null,
      muted_until: mutedUntil,
    }

    // Add server_id or channel_id based on scope
    if (muteDialog.value.scope === 'server' && chat.currentChannel.server_id) {
      payload.server_id = chat.currentChannel.server_id
    } else if (muteDialog.value.scope === 'channel') {
      payload.channel_id = chat.currentChannel.id
    }

    try {
      await axios.post(`${API_BASE}/api/mute`, payload, {
        headers: { Authorization: `Bearer ${auth.token}` },
      })
      dialog.success({ title: t('voice.muteSuccessTitle'), content: t('voice.muteSuccess') })
      hideMuteDialog()
    } catch (error: any) {
      dialog.error({
        title: t('voice.errorTitle'),
        content: error.response?.data?.detail || t('voice.muteFailed'),
      })
    }
  }

  // Check mute status
  async function checkMuteStatus() {
    if (!auth.user) return

    try {
      const resp = await axios.get(`${API_BASE}/api/mute/user/${auth.user.id}`, {
        headers: { Authorization: `Bearer ${auth.token}` },
      })

      const mutes = resp.data
      const currentChannel = chat.currentChannel
      if (!currentChannel) return

      // Check if user is muted in current context
      const activeMute = mutes.find((mute: any) => {
        if (mute.scope === 'global') return true
        if (mute.scope === 'server' && mute.server_id === currentChannel.server_id) return true
        if (mute.scope === 'channel' && mute.channel_id === currentChannel.id) return true
        return false
      })

      if (activeMute) {
        localMuted.value = true
        localMuteReason.value = activeMute.reason || t('voice.youAreMuted')
      } else {
        localMuted.value = false
        localMuteReason.value = ''
        // Also clear WebSocket mute status when API says not muted
        chat.setMutedByWs(false, '')
      }
    } catch (error) {
      console.error('Failed to check mute status:', error)
    }
  }

  return {
    muteDialog,
    scopeOptions,
    durationOptions,
    isMuted,
    muteReason,
    showMuteDialog,
    hideMuteDialog,
    confirmMute,
    checkMuteStatus,
  }
}
