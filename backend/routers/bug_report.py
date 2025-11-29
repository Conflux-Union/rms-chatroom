"""Bug report routes for collecting and downloading debug reports."""
from __future__ import annotations

import uuid
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, Query, UploadFile, status
from fastapi.responses import FileResponse

from ..core.config import RUNTIME_ROOT
from ..services.sso_client import SSOClient


router = APIRouter(prefix="/api/bug", tags=["bug"])

BUG_REPORTS_DIR = RUNTIME_ROOT / "bug_reports"
BUG_REPORTS_DIR.mkdir(exist_ok=True)

MAX_FILE_SIZE = 10 * 1024 * 1024  # 10MB


@router.post("/report")
async def submit_bug_report(
    file: UploadFile = File(..., description="Zip archive containing logs and system info"),
) -> dict[str, str]:
    """
    Submit a bug report.
    
    Accepts a zip file containing device info and logs.
    Returns a report_id for later retrieval.
    """
    if not file.filename or not file.filename.endswith(".zip"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File must be a .zip archive",
        )
    
    content = await file.read()
    if len(content) > MAX_FILE_SIZE:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"File too large. Maximum size is {MAX_FILE_SIZE // 1024 // 1024}MB",
        )
    
    report_id = str(uuid.uuid4())
    report_path = BUG_REPORTS_DIR / f"{report_id}.zip"
    report_path.write_bytes(content)
    
    return {"report_id": report_id}


@router.get("/report/{report_id}")
async def download_bug_report(
    report_id: str,
    token: str = Query(..., description="SSO token for authentication"),
) -> FileResponse:
    """
    Download a bug report by ID.
    
    Requires a valid SSO token.
    """
    user = await SSOClient.verify_token(token)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
        )
    
    report_path = BUG_REPORTS_DIR / f"{report_id}.zip"
    if not report_path.exists():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Report not found",
        )
    
    return FileResponse(
        path=report_path,
        filename=f"bug_report_{report_id}.zip",
        media_type="application/zip",
    )
