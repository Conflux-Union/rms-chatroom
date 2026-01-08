import sys
import os
import logging
import uvicorn

# 添加项目根目录到路径
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.core.config import get_settings

settings = get_settings()

# 设置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)s [%(name)s] %(message)s'
)
logger = logging.getLogger(__name__)


if __name__ == "__main__":
    # 检查启动参数
    verbose = '--verbose' in sys.argv
    if '--verbose' in sys.argv:
        sys.argv.remove('--verbose')
        logging.getLogger().setLevel(logging.DEBUG)
    
    if verbose:
        logger.info("启用详细日志模式")
    try:
        uvicorn.run(
            "backend.app:app",
            host=settings.host,
            port=settings.port,
            reload=settings.debug,
            log_level="info"
        )
    except KeyboardInterrupt:
        logger.info("收到中断信号，正在关闭服务...")
    except Exception as e:
        logger.exception(f"服务启动失败: {e}")
    finally:
        logger.info("服务已关闭")
