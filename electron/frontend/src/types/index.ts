export interface User {
  id: number
  username: string
  nickname: string
  email: string
  permission_level: number
  is_active: boolean
}

export interface Server {
  id: number
  name: string
  icon: string | null
  owner_id: number
  channels?: Channel[]
}

export interface Channel {
  id: number
  server_id: number
  name: string
  type: 'text' | 'voice'
  position: number
}

export interface Attachment {
  id: number
  filename: string
  content_type: string
  size: number
  url: string
}

export interface Message {
  id: number
  channel_id: number
  user_id: number
  username: string
  content: string
  created_at: string
  attachments?: Attachment[]
}

export interface VoiceUser {
  id: number
  username: string
  muted: boolean
  deafened: boolean
}
