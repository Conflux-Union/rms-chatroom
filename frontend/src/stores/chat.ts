import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Server, Channel, Message } from '../types'
import axios from 'axios'
import { useAuthStore } from './auth'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8000'

export const useChatStore = defineStore('chat', () => {
  const servers = ref<Server[]>([])
  const currentServer = ref<Server | null>(null)
  const currentChannel = ref<Channel | null>(null)
  const messages = ref<Message[]>([])

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

  return {
    servers,
    currentServer,
    currentChannel,
    messages,
    fetchServers,
    fetchServer,
    createServer,
    createChannel,
    fetchMessages,
    setCurrentChannel,
    addMessage,
  }
})
