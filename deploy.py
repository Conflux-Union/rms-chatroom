#!/usr/bin/env python
"""
RMS Discord Deployment Script

Usage:
    python deploy.py                    # Deploy to configured server
    python deploy.py --dry-run          # Pack files without uploading
    python deploy.py --server URL       # Override server URL
"""
from __future__ import annotations

import argparse
import io
import os
import sys
import tarfile
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("Error: requests library not found. Install with: pip install requests")
    sys.exit(1)


# Configuration - modify these for your deployment
SERVER_URL = os.environ.get("DEPLOY_SERVER", "https://preview-chatroom.rms.net.cn")
DEPLOY_TOKEN = os.environ.get("DEPLOY_TOKEN", "rmstoken")

PROJECT_ROOT = Path(__file__).parent.resolve()

# Patterns to exclude from deployment package
EXCLUDE_PATTERNS = {
    ".venv",
    "node_modules",
    "__pycache__",
    ".git",
    ".backup",
    "dist",
    ".vite",
    "*.pyc",
    "*.pyo",
    "*.db",
    "*.sqlite",
    "*.sqlite3",
    "config.json",
    ".env",
    ".env.local",
    ".DS_Store",
    "Thumbs.db",
}


def should_exclude(path: Path, rel_path: str) -> bool:
    """Check if a path should be excluded from the archive."""
    parts = rel_path.split(os.sep)
    
    for pattern in EXCLUDE_PATTERNS:
        if pattern.startswith("*"):
            # Glob pattern for extension
            ext = pattern[1:]
            if path.name.endswith(ext):
                return True
        else:
            # Direct name match
            if pattern in parts or path.name == pattern:
                return True
    
    return False


def create_archive() -> tuple[io.BytesIO, int, int]:
    """Create tar.gz archive of project files."""
    buffer = io.BytesIO()
    file_count = 0
    
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for root, dirs, files in os.walk(PROJECT_ROOT):
            # Filter directories in-place to skip excluded ones
            dirs[:] = [
                d for d in dirs 
                if not should_exclude(Path(root) / d, d)
            ]
            
            for filename in files:
                file_path = Path(root) / filename
                rel_path = str(file_path.relative_to(PROJECT_ROOT))
                
                if should_exclude(file_path, rel_path):
                    continue
                
                tar.add(file_path, arcname=rel_path)
                file_count += 1
    
    buffer.seek(0)
    size = len(buffer.getvalue())
    buffer.seek(0)
    
    return buffer, file_count, size


def format_size(size: int) -> str:
    """Format byte size to human readable string."""
    for unit in ["B", "KB", "MB", "GB"]:
        if size < 1024:
            return f"{size:.1f}{unit}"
        size /= 1024
    return f"{size:.1f}TB"


def deploy(server_url: str, token: str, dry_run: bool = False) -> bool:
    """Deploy to server."""
    print(f"[1/3] Packing files...")
    start = time.time()
    
    archive, file_count, size = create_archive()
    pack_time = time.time() - start
    
    print(f"      {file_count} files, {format_size(size)} (took {pack_time:.1f}s)")
    
    if dry_run:
        print("\n[DRY RUN] Archive created but not uploaded.")
        # Save archive locally for inspection
        output_path = PROJECT_ROOT / "deploy_package.tar.gz"
        output_path.write_bytes(archive.read())
        print(f"      Saved to: {output_path}")
        return True
    
    print(f"\n[2/3] Uploading to {server_url}/api/system/update...")
    start = time.time()
    
    try:
        response = requests.post(
            f"{server_url}/api/system/update",
            params={"token": token},
            files={"file": ("deploy.tar.gz", archive, "application/gzip")},
            timeout=300,  # 5 minute timeout for build
        )
    except requests.RequestException as e:
        print(f"\n      ERROR: Failed to connect: {e}")
        return False
    
    upload_time = time.time() - start
    print(f"      Upload completed in {upload_time:.1f}s")
    
    if response.status_code != 200:
        print(f"\n      ERROR: Server returned {response.status_code}")
        try:
            error_detail = response.json().get("detail", response.text)
        except Exception:
            error_detail = response.text
        print(f"      {error_detail}")
        return False
    
    result = response.json()
    
    print(f"\n[3/3] Server processing result:")
    
    if result.get("backup"):
        print(f"      Backup: {result['backup']}")
    
    print(f"      Files extracted: {result.get('extracted_files', 0)}")
    
    skipped = result.get("skipped_files", [])
    if skipped:
        print(f"      Skipped (protected): {len(skipped)} files")
    
    build = result.get("build", {})
    if build:
        status = "SUCCESS" if build.get("success") else "FAILED"
        print(f"      Frontend build: {status}")
        
        if not build.get("success"):
            print(f"\n      Build stderr:\n{build.get('stderr', 'N/A')}")
            return False
    
    restart = result.get("restart", {})
    if restart and restart.get("scheduled"):
        print(f"      Restart: Scheduled via {restart.get('method', 'unknown')}")
    
    print(f"\nDeploy completed successfully!")
    return True


def main():
    parser = argparse.ArgumentParser(description="Deploy RMS Discord to server")
    parser.add_argument(
        "--server",
        default=SERVER_URL,
        help=f"Server URL (default: {SERVER_URL})",
    )
    parser.add_argument(
        "--token",
        default=DEPLOY_TOKEN,
        help="Deploy token (default: from DEPLOY_TOKEN env or config)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Create archive without uploading",
    )
    
    args = parser.parse_args()
    
    if args.server == "https://your-server.com" and not args.dry_run:
        print("Error: Please configure SERVER_URL in deploy.py or use --server")
        print("       Or set DEPLOY_SERVER environment variable")
        sys.exit(1)
    
    if args.token == "your-secret-token" and not args.dry_run:
        print("Error: Please configure DEPLOY_TOKEN in deploy.py or use --token")
        print("       Or set DEPLOY_TOKEN environment variable")
        sys.exit(1)
    
    success = deploy(args.server, args.token, args.dry_run)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
