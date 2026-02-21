import sys
import os
import logging
import uvicorn

# 添加项目根目录到路径
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.core.config import get_settings

settings = get_settings()

LOG_FORMAT = "%(asctime)s %(levelname)s [%(name)s] %(message)s"

# Pass this to uvicorn so it doesn't wipe our handlers with dictConfig
UVICORN_LOG_CONFIG = {
    "version": 1,
    "disable_existing_loggers": False,  # critical: keep app loggers alive
    "formatters": {
        "default": {"format": LOG_FORMAT},
    },
    "handlers": {
        "default": {
            "class": "logging.StreamHandler",
            "stream": "ext://sys.stdout",
            "formatter": "default",
        },
    },
    "root": {"level": "INFO", "handlers": ["default"]},
    "loggers": {
        "uvicorn": {"handlers": ["default"], "level": "INFO", "propagate": False},
        "uvicorn.error": {"handlers": ["default"], "level": "INFO", "propagate": False},
        "uvicorn.access": {"handlers": ["default"], "level": "INFO", "propagate": False},
    },
}

logger = logging.getLogger(__name__)


if __name__ == "__main__":
    verbose = "--verbose" in sys.argv
    if verbose:
        sys.argv.remove("--verbose")

    log_level = "debug" if verbose else "info"
    if verbose:
        UVICORN_LOG_CONFIG["root"]["level"] = "DEBUG"

    try:
        uvicorn.run(
            "backend.app:app",
            host=settings.host,
            port=settings.port,
            reload=settings.debug,
            log_level=log_level,
            log_config=UVICORN_LOG_CONFIG,
        )
    except KeyboardInterrupt:
        logger.info("Shutting down on interrupt")
    except Exception as e:
        logger.exception(f"Server failed to start: {e}")
    finally:
        logger.info("Server stopped")
