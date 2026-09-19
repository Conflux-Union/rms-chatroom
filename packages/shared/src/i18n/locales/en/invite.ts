import type { Message } from '../../types'
import type { Keys } from '../zh/invite'

export const messages: Record<Keys, Message> = {
  'invite.defaultChannelName': 'Voice channel',
  'invite.defaultServerName': 'Server',
  'invite.invalidLink': 'This invite link is invalid or has already been used.',
  'invite.verifyFailed': 'Failed to verify the invite link.',
  'invite.nameRequired': 'Please enter your name',
  'invite.joinFailed': 'Failed to join',
  'invite.connectFailed': 'Connection failed',
  'invite.verifying': 'Verifying invite link...',
  'invite.invalidTitle': 'Invalid invite',
  'invite.joinVoiceChannel': 'Join voice channel',
  'invite.yourName': 'Your name',
  'invite.displayNamePlaceholder': 'Enter your display name',
  'invite.joinVoice': 'Join voice',
  'invite.oneTimeNote':
    'This invite link can only be used once. You cannot rejoin after leaving.',
  'invite.connectingTo': 'Connecting to {name}...',
  'invite.connected': 'Connected',
  'invite.hosting': '{name} is hosting',
  'invite.mute': 'Mute',
  'invite.unmute': 'Unmute',
  'invite.muteSpeakers': 'Mute speakers',
  'invite.unmuteSpeakers': 'Unmute speakers',
  'invite.disconnect': 'Disconnect',
  'invite.disconnectNote': 'You cannot rejoin after disconnecting.',
  'invite.endedTitle': 'Session ended',
  'invite.endedNote': 'Thanks for joining. You can now close this page.',
}
