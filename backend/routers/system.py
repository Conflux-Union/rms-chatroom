"""System management routes for deployment and updates."""
from __future__ import annotations

import asyncio
import os
import shutil
import tarfile
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import APIRouter, File, HTTPException, Query, UploadFile, status

from ..core.config import get_settings, RUNTIME_ROOT


router = APIRouter(prefix="/api/system", tags=["system"])

PROJECT_ROOT = RUNTIME_ROOT.parent
BACKUP_DIR = PROJECT_ROOT / ".backup"
MAX_BACKUPS = 3

# Files and directories that should never be overwritten
PROTECTED_PATTERNS = {
    "backend/config.json",
    "backend/.venv",
    "frontend/.env",
    "discord.db",
    ".backup",
    ".git",
}


def _verify_token(token: str) -> bool:
    """Verify deploy token."""
    settings = get_settings()
    expected = getattr(settings, "deploy_token", None)
    if not expected:
        return False
    return token == expected


def _create_backup() -> Path | None:
    """Create a backup of current source code."""
    BACKUP_DIR.mkdir(exist_ok=True)
    
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_path = BACKUP_DIR / f"backup_{timestamp}"
    
    try:
        # Backup backend (excluding .venv and __pycache__)
        backend_src = PROJECT_ROOT / "backend"
        backend_dst = backup_path / "backend"
        shutil.copytree(
            backend_src,
            backend_dst,
            ignore=shutil.ignore_patterns(".venv", "__pycache__", "*.pyc", "*.db"),
        )
        
        # Backup frontend src (excluding node_modules and dist)
        frontend_src = PROJECT_ROOT / "frontend"
        frontend_dst = backup_path / "frontend"
        shutil.copytree(
            frontend_src,
            frontend_dst,
            ignore=shutil.ignore_patterns("node_modules", "dist", ".vite"),
        )
        
        # Cleanup old backups
        backups = sorted(BACKUP_DIR.glob("backup_*"), reverse=True)
        for old_backup in backups[MAX_BACKUPS:]:
            shutil.rmtree(old_backup, ignore_errors=True)
        
        return backup_path
    except Exception:
        return None


def _is_protected(rel_path: str) -> bool:
    """Check if a path should be protected from overwrite."""
    for pattern in PROTECTED_PATTERNS:
        if rel_path.startswith(pattern) or rel_path == pattern:
            return True
    return False


async def _run_command(cmd: list[str], cwd: Path) -> tuple[int, str, str]:
    """Run a shell command asynchronously."""
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        cwd=cwd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()
    return proc.returncode or 0, stdout.decode(), stderr.decode()


async def _build_frontend() -> dict[str, Any]:
    """Build frontend assets."""
    frontend_dir = PROJECT_ROOT / "frontend"
    
    if not (frontend_dir / "package.json").exists():
        return {"success": False, "error": "Frontend not found"}
    
    # Run npm run build
    returncode, stdout, stderr = await _run_command(
        ["npm", "run", "build"],
        frontend_dir,
    )
    
    return {
        "success": returncode == 0,
        "returncode": returncode,
        "stdout": stdout[-2000:] if len(stdout) > 2000 else stdout,
        "stderr": stderr[-2000:] if len(stderr) > 2000 else stderr,
    }


@router.post("/update")
async def update_system(
    token: str = Query(..., description="Deploy token for authentication"),
    file: UploadFile = File(..., description="Tar.gz archive of source code"),
) -> dict[str, Any]:
    """
    Update system with new source code.
    
    Expects a tar.gz archive containing the project structure.
    Protected files (config.json, .venv, *.db) will not be overwritten.
    """
    # Verify token
    if not _verify_token(token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid deploy token",
        )
    
    # Verify file type
    if not file.filename or not file.filename.endswith((".tar.gz", ".tgz")):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File must be a .tar.gz archive",
        )
    
    result: dict[str, Any] = {
        "backup": None,
        "extracted_files": 0,
        "skipped_files": [],
        "build": None,
        "restart": None,
    }
    
    # Create backup
    backup_path = _create_backup()
    if backup_path:
        result["backup"] = str(backup_path.relative_to(PROJECT_ROOT))
    
    # Extract to temp directory first
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = Path(tmpdir)
        archive_path = tmp_path / "upload.tar.gz"
        
        # Save uploaded file
        content = await file.read()
        archive_path.write_bytes(content)
        
        # Extract archive
        try:
            with tarfile.open(archive_path, "r:gz") as tar:
                tar.extractall(tmp_path / "extracted", filter="data")
        except tarfile.TarError as e:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Failed to extract archive: {e}",
            )
        
        extracted_root = tmp_path / "extracted"
        
        # Find the actual root (might be nested in a directory)
        contents = list(extracted_root.iterdir())
        if len(contents) == 1 and contents[0].is_dir():
            extracted_root = contents[0]
        
        # Copy files to project, respecting protected paths
        for src_file in extracted_root.rglob("*"):
            if not src_file.is_file():
                continue
            
            rel_path = str(src_file.relative_to(extracted_root))
            
            if _is_protected(rel_path):
                result["skipped_files"].append(rel_path)
                continue
            
            dst_file = PROJECT_ROOT / rel_path
            dst_file.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src_file, dst_file)
            result["extracted_files"] += 1
    
    # Build frontend
    result["build"] = await _build_frontend()
    
    # Schedule restart after response is sent
    if result["build"]["success"]:
        result["restart"] = {"scheduled": True, "method": "systemd", "delay": 2}
        asyncio.get_event_loop().call_later(2, _schedule_restart)
    
    return result


def _schedule_restart():
    """Exit process to trigger systemd restart."""
    import os
    import signal
    os.kill(os.getpid(), signal.SIGTERM)


@router.get("/health")
async def system_health() -> dict[str, Any]:
    """Extended health check with system info."""
    settings = get_settings()
    frontend_dist = Path(settings.frontend_dist_path)
    
    return {
        "status": "ok",
        "frontend_built": frontend_dist.exists(),
        "backup_count": len(list(BACKUP_DIR.glob("backup_*"))) if BACKUP_DIR.exists() else 0,
    }
