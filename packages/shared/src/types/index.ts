export interface User {
  id: number
  username: string
  nickname: string
  email: string
  avatar_url?: string
  permission_level: number
  is_active: boolean
}

export interface Server {
  id: number
  name: string
  icon: string | null
  owner_id: number
  channels?: Channel[]
  channelGroups?: ChannelGroup[]
}

export interface ChannelGroup {
  id: number
  server_id: number
  name: string
  position: number
}

export interface Channel {
  id: number
  server_id: number
  group_id: number | null
  name: string
  type: 'TEXT' | 'VOICE'
  position: number
  top_position: number
}

export interface Attachment {
  id: number
  filename: string
  content_type: string
  size: number
  url: string
}

export interface ReplyTo {
  id: number
  user_id: number
  username: string
  content: string  // Truncated preview
}

export interface Mention {
  id: number
  username: string
}

export interface ReactionUser {
  id: number
  username: string
}

export interface ReactionGroup {
  emoji: string
  count: number
  users: ReactionUser[]
  reacted?: boolean  // Whether current user has reacted (set by frontend)
}

export interface Message {
  id: number
  channel_id: number
  user_id: number
  username: string
  avatar_url?: string
  content: string
  created_at: string
  attachments?: Attachment[]
  // Message management fields
  is_deleted?: boolean
  deleted_by?: number
  deleted_by_username?: string
  edited_at?: string
  // Reply feature
  reply_to_id?: number
  reply_to?: ReplyTo
  // Mentions feature
  mentions?: Mention[]
  // Reactions feature
  reactions?: ReactionGroup[]
}

export interface MuteRecord {
  id: number
  scope: 'global' | 'server' | 'channel'
  server_id?: number
  channel_id?: number
  muted_until?: string  // ISO datetime or null for permanent
  reason?: string
}

export interface VoiceUser {
  id: number
  username: string
  avatar_url?: string
  muted: boolean
  deafened: boolean
}
