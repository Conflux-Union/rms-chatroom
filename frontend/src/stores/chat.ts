import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Server, Channel, Message } from '../types'
import axios from 'axios'
import { useAuthStore } from './auth'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8000'

// Voice channel user info from API
interface VoiceChannelUser {
  id: string
  name: string
  is_muted: boolean
  is_host: boolean
}

export const useChatStore = defineStore('chat', () => {
  const servers = ref<Server[]>([])
  const currentServer = ref<Server | null>(null)
  const currentChannel = ref<Channel | null>(null)
  const messages = ref<Message[]>([])
  // Map: channelId -> users in that voice channel
  const voiceChannelUsers = ref<Map<number, VoiceChannelUser[]>>(new Map())

  function getAuthHeaders() {
    const auth = useAuthStore()
    return { Authorization: `Bearer ${auth.token}` }
  }

  async function fetchServers() {
    try {
      const resp = await axios.get(`${API_BASE}/api/servers`, {
        headers: getAuthHeaders(),
      })
      servers.value = resp.data
    } catch (e) {
      console.error('Failed to fetch servers:', e)
    }
  }

  async function fetchServer(serverId: number) {
    try {
      const resp = await axios.get(`${API_BASE}/api/servers/${serverId}`, {
        headers: getAuthHeaders(),
      })
      currentServer.value = resp.data
      return resp.data
    } catch (e) {
      console.error('Failed to fetch server:', e)
      return null
    }
  }

  async function createServer(name: string) {
    try {
      const resp = await axios.post(
        `${API_BASE}/api/servers`,
        { name },
        { headers: getAuthHeaders() }
      )
      await fetchServers()
      return resp.data
    } catch (e) {
      console.error('Failed to create server:', e)
      return null
    }
  }

  async function createChannel(serverId: number, name: string, type: 'text' | 'voice') {
    try {
      const resp = await axios.post(
        `${API_BASE}/api/servers/${serverId}/channels`,
        { name, type },
        { headers: getAuthHeaders() }
      )
      await fetchServer(serverId)
      return resp.data
    } catch (e) {
      console.error('Failed to create channel:', e)
      return null
    }
  }

  async function deleteServer(serverId: number) {
    try {
      await axios.delete(`${API_BASE}/api/servers/${serverId}`, {
        headers: getAuthHeaders(),
      })
      if (currentServer.value?.id === serverId) {
        currentServer.value = null
        currentChannel.value = null
        messages.value = []
      }
      await fetchServers()
      return true
    } catch (e) {
      console.error('Failed to delete server:', e)
      return false
    }
  }

  async function deleteChannel(serverId: number, channelId: number) {
    try {
      await axios.delete(`${API_BASE}/api/servers/${serverId}/channels/${channelId}`, {
        headers: getAuthHeaders(),
      })
      if (currentChannel.value?.id === channelId) {
        currentChannel.value = null
        messages.value = []
      }
      await fetchServer(serverId)
      return true
    } catch (e) {
      console.error('Failed to delete channel:', e)
      return false
    }
  }

  async function fetchMessages(channelId: number, before?: number) {
    try {
      const params: Record<string, any> = { limit: 50 }
      if (before) params.before = before

      const resp = await axios.get(`${API_BASE}/api/channels/${channelId}/messages`, {
        headers: getAuthHeaders(),
        params,
      })
      if (before) {
        messages.value = [...resp.data, ...messages.value]
      } else {
        messages.value = resp.data
      }
      return resp.data
    } catch (e) {
      console.error('Failed to fetch messages:', e)
      return []
    }
  }

  function setCurrentChannel(channel: Channel | null) {
    currentChannel.value = channel
    if (channel) {
      messages.value = []
    }
  }

  function addMessage(message: Message) {
    messages.value.push(message)
  }

  async function fetchVoiceChannelUsers(channelId: number) {
    try {
      const resp = await axios.get<VoiceChannelUser[]>(
        `${API_BASE}/api/voice/${channelId}/users`,
        { headers: getAuthHeaders() }
      )
      const newMap = new Map(voiceChannelUsers.value)
      newMap.set(channelId, resp.data)
      voiceChannelUsers.value = newMap
    } catch {
      const newMap = new Map(voiceChannelUsers.value)
      newMap.set(channelId, [])
      voiceChannelUsers.value = newMap
    }
  }

  async function fetchAllVoiceChannelUsers() {
    if (!currentServer.value?.channels) return
    try {
      const resp = await axios.get<{ users: Record<string, VoiceChannelUser[]> }>(
        `${API_BASE}/api/voice/user/all`,
        { headers: getAuthHeaders() }
      )
      const newMap = new Map<number, VoiceChannelUser[]>()
      for (const [channelId, users] of Object.entries(resp.data.users)) {
        newMap.set(Number(channelId), users)
      }
      // Set empty array for voice channels not in response
      const voiceChannels = currentServer.value.channels.filter(c => c.type === 'voice')
      for (const ch of voiceChannels) {
        if (!newMap.has(ch.id)) {
          newMap.set(ch.id, [])
        }
      }
      voiceChannelUsers.value = newMap
    } catch {
      // Fallback: clear all voice channel users
      const newMap = new Map<number, VoiceChannelUser[]>()
      const voiceChannels = currentServer.value.channels.filter(c => c.type === 'voice')
      for (const ch of voiceChannels) {
        newMap.set(ch.id, [])
      }
      voiceChannelUsers.value = newMap
    }
  }

  function getVoiceChannelUsers(channelId: number): VoiceChannelUser[] {
    return voiceChannelUsers.value.get(channelId) || []
  }

  return {
    servers,
    currentServer,
    currentChannel,
    messages,
    voiceChannelUsers,
    fetchServers,
    fetchServer,
    createServer,
    createChannel,
    deleteServer,
    deleteChannel,
    fetchMessages,
    setCurrentChannel,
    addMessage,
    fetchVoiceChannelUsers,
    fetchAllVoiceChannelUsers,
    getVoiceChannelUsers,
  }
})
