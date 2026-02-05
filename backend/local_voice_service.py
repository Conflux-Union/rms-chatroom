from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel
import uvicorn
import threading
import uuid
from datetime import datetime
import logging

logger = logging.getLogger(__name__)
app = FastAPI(title="LocalVoiceService")

SESSIONS: dict[str, dict] = {}
SENTENCES: dict[str, list] = {}

# 全局锁 - 确保一次只有一个房间可以使用语音识别服务
_active_room_id = None
_service_lock = threading.Lock()

class TrainRequest(BaseModel):
    callback_url: str
    audio_tracks: list
    room_config: dict
    voice_config: dict

@app.get("/health")
async def health():
    return {"status": "ok", "timestamp": datetime.now().isoformat()}

@app.post("/transcription")
async def transcription(req: TrainRequest):
    global _active_room_id
    
    room_id = req.room_config.get("room_id", "unknown")
    
    with _service_lock:
        # 检查是否已有其他房间在使用服务
        if _active_room_id and _active_room_id != room_id:
            logger.warning(f"LocalVoiceService: rejected request for room {room_id}, service busy with room {_active_room_id}")
            return JSONResponse(
                status_code=409,
                content={
                    "success": False,
                    "error": f"语音识别服务正在被房间 {_active_room_id} 使用",
                    "busy": True,
                    "active_room_id": _active_room_id
                }
            )
        
        # 创建会话
        session_id = str(uuid.uuid4())
        SESSIONS[session_id] = {
            "session_id": session_id,
            "room_config": req.room_config,
            "voice_config": req.voice_config,
            "created_at": datetime.now().isoformat(),
            "room_id": room_id
        }
        SENTENCES[session_id] = []
        
        # 标记房间为活跃状态
        _active_room_id = room_id
        
        logger.info(f"LocalVoiceService: started transcription session {session_id} for room {room_id}")
        return JSONResponse(status_code=202, content={"success": True, "session_id": session_id})

@app.post("/stoptran")
async def stoptran(data: dict):
    global _active_room_id
    
    session_id = data.get("session_id")
    
    with _service_lock:
        if session_id in SESSIONS:
            session = SESSIONS[session_id]
            room_id = session.get("room_id")
            
            # 清理会话
            del SESSIONS[session_id]
            SENTENCES.pop(session_id, None)
            
            # 如果这是活跃房间的会话，释放锁
            if _active_room_id == room_id:
                _active_room_id = None
                logger.info(f"LocalVoiceService: released service lock for room {room_id}")
            
            return {"success": True}
        
        return {"success": False, "error": "Session not found"}

@app.get("/status")
async def get_status():
    """获取服务状态"""
    with _service_lock:
        return {
            "success": True,
            "global_lock": {
                "is_locked": _active_room_id is not None,
                "active_room_id": _active_room_id,
                "message": f"服务正在被房间 {_active_room_id} 使用" if _active_room_id else "服务可用"
            },
            "stats": {
                "active_sessions": len(SESSIONS),
                "total_sentences": sum(len(sentences) for sentences in SENTENCES.values())
            },
            "timestamp": datetime.now().isoformat()
        }

@app.get("/sentences")
async def get_sentences(session_id: str, include_unassigned: str = "true", speaker_id: str | None = None, last_timestamp: str | None = None):
    logger.info(f"LocalVoiceService: get_sentences for session {session_id}")
    sentences = SENTENCES.get(session_id, [])
    logger.info(f"LocalVoiceService: returning {len(sentences)} sentences for session {session_id}")
    return {"success": True, "sentences": sentences}

@app.get("/debug/sessions")
async def debug_sessions():
    """调试端点：显示当前所有活跃会话"""
    return {
        "active_sessions": list(SESSIONS.keys()),
        "sessions_detail": SESSIONS,
        "sentences_count": {sid: len(sentences) for sid, sentences in SENTENCES.items()},
        "active_room_id": _active_room_id
    }

@app.post("/streams/push")
async def push_stream(data: dict):
    session_id = data.get("session_id")
    audio_b64 = data.get("audio_data")
    stream_index = data.get("stream_index", 0)
    
    logger.info(f"LocalVoiceService: received audio push for session {session_id}, stream_index {stream_index}, audio_size: {len(audio_b64) if audio_b64 else 0}")
    
    # For mock, append a fake sentence
    if session_id and session_id in SESSIONS:
        sent = {
            "id": str(uuid.uuid4()),
            "speaker": data.get("speaker_id", "mock_speaker"),
            "text": f"(mock) audio received at {datetime.now().strftime('%H:%M:%S')}",
            "timestamp_ms": int(datetime.now().timestamp() * 1000)
        }
        SENTENCES[session_id].append(sent)
        logger.info(f"LocalVoiceService: added mock sentence for session {session_id}")
        return {"success": True, "message": "Audio data received"}
    else:
        logger.warning(f"LocalVoiceService: session {session_id} not found in active sessions: {list(SESSIONS.keys())}")
        return {"success": False, "error": "Session not found"}

@app.post("/speakerstart")
async def speaker_start(data: dict):
    return {"status": "ok"}

@app.post("/speakerstop")
async def speaker_stop(data: dict):
    return {"status": "ok"}


def run_local_voice_service():
    """Run the local voice service on port 5000 (blocking call)."""
    uvicorn.run("backend.local_voice_service:app", host="127.0.0.1", port=5000, log_level="info")


def start_in_background():
    thread = threading.Thread(target=run_local_voice_service, daemon=True)
    thread.start()
    logger.info("Local voice service started in background thread")
    return thread
