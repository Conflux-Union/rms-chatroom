package cn.net.rms.rmsChatroom.client.api;

import java.util.List;
import java.util.Map;

public class Models {

    public record User(
            long id,
            String username,
            String nickname,
            String email,
            int permissionLevel,
            boolean isActive
    ) {}

    public record Server(
            long id,
            String name,
            String icon,
            long ownerId,
            List<Channel> channels
    ) {}

    public record Channel(
            long id,
            long serverId,
            String name,
            String type,
            int position
    ) {
        public boolean isVoice() {
            return "voice".equals(type);
        }
    }

    public record VoiceUser(
            String id,
            String name,
            boolean isMuted,
            boolean isHost
    ) {}

    public record VoiceTokenResponse(
            String token,
            String url,
            String roomName
    ) {}

    public record AllVoiceUsersResponse(
            Map<Long, List<VoiceUser>> users
    ) {}

    public record AuthMeResponse(
            boolean success,
            User user
    ) {}

    public record ApiError(
            String detail
    ) {}
}
