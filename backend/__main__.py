import uvicorn
from .core.config import get_settings

settings = get_settings()

if __name__ == "__main__":
    uvicorn.run(
        "backend.app:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
    )
