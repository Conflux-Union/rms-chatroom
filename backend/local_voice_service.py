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

SESSIONS = {}
SENTENCES = {}

class TrainRequest(BaseModel):
    callback_url: str
    audio_tracks: list
    room_config: dict
    voice_config: dict

@app.get("/health")
async def health():
    return {"status": "ok", "timestamp": datetime.now().isoformat()}

@app.post("/trainsction")
async def trainsction(req: TrainRequest):
    # create a fake session and return accepted
    session_id = str(uuid.uuid4())
    SESSIONS[session_id] = {
        "session_id": session_id,
        "room_config": req.room_config,
        "voice_config": req.voice_config,
        "created_at": datetime.now().isoformat()
    }
    SENTENCES[session_id] = []
    logger.info(f"LocalVoiceService: started transcription session {session_id}")
    return JSONResponse(status_code=202, content={"success": True, "session_id": session_id})

@app.post("/stoptran")
async def stoptran(data: dict):
    session_id = data.get("session_id")
    if session_id in SESSIONS:
        del SESSIONS[session_id]
        SENTENCES.pop(session_id, None)
        return {"success": True}
    return {"success": False, "error": "Session not found"}

@app.get("/sentences")
async def get_sentences(session_id: str, include_unassigned: str = "true", speaker_id: str = None, last_timestamp: str = None):
    sentences = SENTENCES.get(session_id, [])
    return {"success": True, "sentences": sentences}

@app.post("/streams/push")
async def push_stream(data: dict):
    session_id = data.get("session_id")
    audio_b64 = data.get("audio_data")
    # For mock, append a fake sentence
    if session_id and session_id in SENTENCES:
        sent = {
            "id": str(uuid.uuid4()),
            "speaker": data.get("speaker_id", "mock_speaker"),
            "text": "(mock) audio received",
            "timestamp_ms": int(datetime.now().timestamp() * 1000)
        }
        SENTENCES[session_id].append(sent)
        return {"success": True}
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
