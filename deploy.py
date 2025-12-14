#!/usr/bin/env python
"""
RMS Discord Deployment Script

Version is read from current.version file in project root.
Format:
    version=1.0.0
    code=1

Usage:
    python deploy.py --release   # Release deploy (frontend + backend + Android), creates tag
    python deploy.py --release --web-only  # Release deploy (frontend + backend only), creates tag
    python deploy.py --hot-fix   # Hot-fix deploy (requires x.x.x-fix-x version), creates tag
    python deploy.py --hot-fix --web-only  # Hot-fix deploy (web only), creates tag
    python deploy.py --debug     # Debug deploy (frontend + backend only, no Android)
    python deploy.py --dry-run --debug  # Test packaging without upload
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import subprocess
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

# Version file paths
VERSION_FILE = PROJECT_ROOT / "current.version"
FRONTEND_VERSION_FILE = PROJECT_ROOT / "frontend" / "src" / "version.ts"
BACKEND_VERSION_FILE = PROJECT_ROOT / "backend" / "version.py"

# Android paths
ANDROID_DIR = PROJECT_ROOT / "android"
ANDROID_GRADLE_FILE = ANDROID_DIR / "app" / "build.gradle.kts"
ANDROID_APK_OUTPUT = ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
ANDROID_APK_DEST = PROJECT_ROOT / "backend" / "android_app" / "rms-chatroom.apk"
ANDROID_VERSION_JSON = PROJECT_ROOT / "backend" / "android_app" / "version.json"

# Frontend directory
FRONTEND_DIR = PROJECT_ROOT / "frontend"

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
    "music-service",
    "android",
    "debug",
    "fabric-mod",
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


def validate_version_format(version: str, mode: str) -> tuple[bool, str]:
    """Validate version format based on deploy mode."""
    if mode == "hot-fix":
        # Must match x.x.x-fix-x (e.g., 1.0.6-fix-1)
        if not re.match(r'^\d+\.\d+\.\d+-fix-\d+$', version):
            return False, f"Hot-fix requires version format x.x.x-fix-x, got: {version}"
    elif mode == "debug":
        # Must contain x.x.x-dev (e.g., 1.0.6-dev, 1.0.6-dev-1)
        if not re.match(r'^\d+\.\d+\.\d+-dev', version):
            return False, f"Debug requires version format x.x.x-dev*, got: {version}"
    return True, ""


def check_android_version_json(version_name: str, version_code: str) -> tuple[bool, str]:
    """Check that android_app/version.json matches current.version."""
    if not ANDROID_VERSION_JSON.exists():
        return False, f"Android version.json not found: {ANDROID_VERSION_JSON}"
    
    try:
        with open(ANDROID_VERSION_JSON, encoding="utf-8") as f:
            data = json.load(f)
        
        json_name = data.get("version_name", "")
        json_code = str(data.get("version_code", ""))
        
        if json_name != version_name:
            return False, f"version.json version_name mismatch: {json_name} != {version_name}"
        if json_code != version_code:
            return False, f"version.json version_code mismatch: {json_code} != {version_code}"
        
        return True, ""
    except json.JSONDecodeError as e:
        return False, f"Invalid version.json: {e}"
    except Exception as e:
        return False, f"Failed to read version.json: {e}"


def get_last_release_version() -> str | None:
    """Get the last release version from git tags (excluding -fix- and -dev)."""
    try:
        result = subprocess.run(
            ["git", "tag", "-l", "v*"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            return None
        
        tags = result.stdout.strip().splitlines()
        release_versions = []
        
        for tag in tags:
            # Match v{version}({code}) format, excluding -fix- and -dev
            match = re.match(r'^v(\d+\.\d+\.\d+)\(\d+\)$', tag)
            if match:
                release_versions.append(match.group(1))
        
        if not release_versions:
            return None
        
        # Sort by version number and return the latest
        def version_key(v):
            return tuple(int(x) for x in v.split('.'))
        
        release_versions.sort(key=version_key, reverse=True)
        return release_versions[0]
    except Exception:
        return None


def create_git_tag(version: str, code: str) -> tuple[bool, str]:
    """Create git tag: v{version}({code})."""
    tag_name = f"v{version}({code})"
    try:
        result = subprocess.run(
            ["git", "tag", tag_name],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            return False, f"Failed to create tag: {result.stderr.strip()}"
        return True, tag_name
    except Exception as e:
        return False, f"Failed to create tag: {e}"


def git_push(with_tags: bool = False) -> tuple[bool, str]:
    """Push commits to remote, optionally with tags."""
    try:
        # Push commits
        result = subprocess.run(
            ["git", "push"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            return False, f"Failed to push commits: {result.stderr.strip()}"
        
        if with_tags:
            # Push tags
            result = subprocess.run(
                ["git", "push", "--tags"],
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
            )
            if result.returncode != 0:
                return False, f"Failed to push tags: {result.stderr.strip()}"
        
        return True, ""
    except Exception as e:
        return False, f"Failed to push: {e}"


def build_frontend() -> tuple[bool, str]:
    """Build frontend locally to verify before deploy."""
    if not FRONTEND_DIR.exists():
        return False, f"Frontend directory not found: {FRONTEND_DIR}"
    
    try:
        result = subprocess.run(
            ["npm", "run", "build"],
            cwd=FRONTEND_DIR,
            capture_output=True,
            text=True,
            timeout=300,  # 5 minutes timeout
        )
        if result.returncode != 0:
            return False, f"Frontend build failed:\n{result.stderr[-2000:]}"
        return True, ""
    except subprocess.TimeoutExpired:
        return False, "Frontend build timed out (5 minutes)"
    except Exception as e:
        return False, f"Frontend build error: {e}"


def check_git_clean() -> tuple[bool, str]:
    """Check if git working directory is clean."""
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            return False, "Failed to run git status"
        if result.stdout.strip():
            return False, f"Working directory not clean:\n{result.stdout}"
        return True, ""
    except FileNotFoundError:
        return False, "git not found"


def get_commit_hash() -> str:
    """Get current git commit short hash."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except FileNotFoundError:
        pass
    return "unknown"


def read_version_file() -> tuple[str, str]:
    """Read version info from current.version file.
    
    Returns:
        Tuple of (version_name, version_code)
    """
    if not VERSION_FILE.exists():
        print(f"Error: Version file not found: {VERSION_FILE}")
        print("       Create current.version with format:")
        print("       version=1.0.0")
        print("       code=1")
        sys.exit(1)
    
    content = VERSION_FILE.read_text()
    version_name = None
    version_code = None
    
    for line in content.strip().splitlines():
        line = line.strip()
        if line.startswith("version="):
            version_name = line.split("=", 1)[1].strip()
        elif line.startswith("code="):
            version_code = line.split("=", 1)[1].strip()
    
    if not version_name:
        print("Error: 'version=' not found in current.version")
        sys.exit(1)
    if not version_code:
        print("Error: 'code=' not found in current.version")
        sys.exit(1)
    
    return version_name, version_code


def generate_version_files(version_name: str, version_code: str, commit_hash: str) -> None:
    """Generate version files for frontend and backend."""
    # Frontend version.ts
    frontend_content = f'''// Auto-generated by deploy.py - DO NOT EDIT
export const VERSION_NAME = "{version_name}"
export const VERSION_CODE = "{version_code}"
export const COMMIT_HASH = "{commit_hash}"
'''
    FRONTEND_VERSION_FILE.write_text(frontend_content)
    
    # Backend version.py
    backend_content = f'''# Auto-generated by deploy.py - DO NOT EDIT
VERSION_NAME = "{version_name}"
VERSION_CODE = "{version_code}"
COMMIT_HASH = "{commit_hash}"
'''
    BACKEND_VERSION_FILE.write_text(backend_content)


def cleanup_version_files() -> None:
    """Remove generated version files after deployment."""
    # Reset to default values instead of deleting
    default_frontend = '''// Auto-generated by deploy.py - DO NOT EDIT
export const VERSION_NAME = "0.0.0"
export const VERSION_CODE = "0"
export const COMMIT_HASH = "unknown"
'''
    default_backend = '''# Auto-generated by deploy.py - DO NOT EDIT
VERSION_NAME = "0.0.0"
VERSION_CODE = "0"
COMMIT_HASH = "unknown"
'''
    FRONTEND_VERSION_FILE.write_text(default_frontend)
    BACKEND_VERSION_FILE.write_text(default_backend)


def update_android_version(version_name: str, version_code: str) -> tuple[bool, str]:
    """Update version in Android build.gradle.kts."""
    if not ANDROID_GRADLE_FILE.exists():
        return False, f"Android gradle file not found: {ANDROID_GRADLE_FILE}"
    
    content = ANDROID_GRADLE_FILE.read_text()
    
    # Check patterns exist
    if not re.search(r'val appVersionCode = \d+', content):
        return False, "appVersionCode not found in build.gradle.kts"
    if not re.search(r'val appVersionName = "[^"]+"', content):
        return False, "appVersionName not found in build.gradle.kts"
    
    # Update appVersionCode
    content = re.sub(
        r'val appVersionCode = \d+',
        f'val appVersionCode = {version_code}',
        content
    )
    
    # Update appVersionName
    content = re.sub(
        r'val appVersionName = "[^"]+"',
        f'val appVersionName = "{version_name}"',
        content
    )
    
    ANDROID_GRADLE_FILE.write_text(content)
    return True, ""


def build_android_release() -> tuple[bool, str]:
    """Build Android release APK."""
    if not ANDROID_DIR.exists():
        return False, f"Android directory not found: {ANDROID_DIR}"
    
    gradlew = ANDROID_DIR / "gradlew"
    if not gradlew.exists():
        return False, f"gradlew not found: {gradlew}"
    
    try:
        result = subprocess.run(
            ["./gradlew", "assembleRelease"],
            cwd=ANDROID_DIR,
            capture_output=True,
            text=True,
            timeout=600,  # 10 minutes timeout
        )
        if result.returncode != 0:
            return False, f"Gradle build failed:\n{result.stderr[-2000:]}"
        return True, ""
    except subprocess.TimeoutExpired:
        return False, "Android build timed out (10 minutes)"
    except Exception as e:
        return False, f"Android build error: {e}"


def copy_android_apk() -> tuple[bool, str]:
    """Copy built APK to backend/android_app/."""
    if not ANDROID_APK_OUTPUT.exists():
        return False, f"APK not found: {ANDROID_APK_OUTPUT}"
    
    ANDROID_APK_DEST.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ANDROID_APK_OUTPUT, ANDROID_APK_DEST)
    return True, ""


def cleanup_android_apk() -> None:
    """Remove APK from backend/android_app/."""
    if ANDROID_APK_DEST.exists():
        ANDROID_APK_DEST.unlink()


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


def deploy(server_url: str, token: str, dry_run: bool = False, step_offset: int = 0, total_steps: int = 3) -> bool:
    """Deploy to server."""
    step = step_offset + 1
    print(f"\n[{step}/{total_steps}] Packing files...")
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
    
    step += 1
    print(f"\n[{step}/{total_steps}] Uploading to {server_url}/api/system/update...")
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
    
    step += 1
    print(f"\n[{step}/{total_steps}] Server processing result:")
    
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
    
    # Required mode argument (mutually exclusive)
    mode_group = parser.add_mutually_exclusive_group(required=True)
    mode_group.add_argument(
        "--release",
        action="store_true",
        help="Release deploy (frontend + backend + Android), creates tag",
    )
    mode_group.add_argument(
        "--hot-fix",
        action="store_true",
        help="Hot-fix deploy (requires x.x.x-fix-x version), creates tag",
    )
    mode_group.add_argument(
        "--debug",
        action="store_true",
        help="Debug deploy (frontend + backend only, no Android, requires x.x.x-dev version)",
    )
    
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
    parser.add_argument(
        "--web-only",
        action="store_true",
        help="Deploy frontend + backend only, skip Android build (only for --release and --hot-fix)",
    )
    
    args = parser.parse_args()
    
    # Determine mode
    if args.release:
        mode = "release"
    elif args.hot_fix:
        mode = "hot-fix"
    else:
        mode = "debug"
    
    # Validate --web-only usage
    if args.web_only and mode == "debug":
        print("Error: --web-only is only valid with --release or --hot-fix")
        sys.exit(1)
    
    build_android = mode in ("release", "hot-fix") and not args.web_only
    create_tag = mode in ("release", "hot-fix")
    
    # Check git working directory is clean (skip for dry-run)
    if not args.dry_run:
        is_clean, msg = check_git_clean()
        if not is_clean:
            print(f"Error: {msg}")
            print("       Please commit or stash your changes before deploying.")
            sys.exit(1)
    
    if args.server == "https://your-server.com" and not args.dry_run:
        print("Error: Please configure SERVER_URL in deploy.py or use --server")
        print("       Or set DEPLOY_SERVER environment variable")
        sys.exit(1)
    
    if args.token == "your-secret-token" and not args.dry_run:
        print("Error: Please configure DEPLOY_TOKEN in deploy.py or use --token")
        print("       Or set DEPLOY_TOKEN environment variable")
        sys.exit(1)
    
    # Read version from file
    version_name, version_code = read_version_file()
    version_name = version_name.lstrip("v")  # Remove leading 'v' if present
    commit_hash = get_commit_hash()
    
    # Calculate total steps based on mode
    # Steps: validate + version + frontend_build + (android) + (tag) + push + pack + upload + result
    total_steps = 7  # base: validate + version + frontend + push + pack + upload + result
    if build_android:
        total_steps += 1  # android build
    if create_tag:
        total_steps += 1  # tag creation
    
    step = 0
    
    # Step 1: Validate version format
    step += 1
    print(f"[{step}/{total_steps}] Validating version format...")
    mode_desc = f"{mode} mode" + (" (web-only)" if args.web_only else "")
    print(f"      Version: v{version_name}({version_code}) [{mode_desc}]")
    
    valid, err = validate_version_format(version_name, mode)
    if not valid:
        print(f"      ERROR: {err}")
        sys.exit(1)
    
    # For release mode, check if version already released
    if mode == "release":
        last_release = get_last_release_version()
        if last_release and last_release == version_name:
            print(f"      ERROR: Version {version_name} already released.")
            print(f"             Use --hot-fix for fixes to this version.")
            sys.exit(1)
        if last_release:
            print(f"      Last release: v{last_release}")
    
    # For release/hot-fix, check android_app/version.json matches
    if mode in ("release", "hot-fix"):
        valid, err = check_android_version_json(version_name, version_code)
        if not valid:
            print(f"      ERROR: {err}")
            print(f"             Update backend/android_app/version.json to match current.version")
            sys.exit(1)
        print(f"      android_app/version.json matches")
    
    print(f"      Version format valid")
    
    # Step 2: Generate version files
    step += 1
    print(f"\n[{step}/{total_steps}] Generating version files...")
    generate_version_files(version_name, version_code, commit_hash)
    print(f"      Generated frontend/src/version.ts")
    print(f"      Generated backend/version.py")
    
    try:
        # Step 3: Build frontend locally
        step += 1
        print(f"\n[{step}/{total_steps}] Building frontend locally...")
        success, err = build_frontend()
        if not success:
            print(f"      ERROR: {err}")
            sys.exit(1)
        print(f"      Frontend build successful")
        
        # Step 4 (optional): Android build
        if build_android:
            step += 1
            print(f"\n[{step}/{total_steps}] Building Android release...")
            
            # Update Android version
            success, err = update_android_version(version_name, version_code)
            if not success:
                print(f"      ERROR: {err}")
                sys.exit(1)
            print(f"      Updated build.gradle.kts")
            
            # Build APK
            print(f"      Running gradle assembleRelease (this may take a while)...")
            success, err = build_android_release()
            if not success:
                print(f"      ERROR: {err}")
                sys.exit(1)
            print(f"      Build successful")
            
            # Copy APK
            success, err = copy_android_apk()
            if not success:
                print(f"      ERROR: {err}")
                sys.exit(1)
            print(f"      Copied APK to {ANDROID_APK_DEST.relative_to(PROJECT_ROOT)}")
        
        # Step 5 (optional): Create git tag
        tag_name = None
        if create_tag:
            step += 1
            print(f"\n[{step}/{total_steps}] Creating git tag...")
            success, result = create_git_tag(version_name, version_code)
            if not success:
                print(f"      ERROR: {result}")
                sys.exit(1)
            tag_name = result
            print(f"      Tag created: {tag_name}")
        
        # Step 6: Git push
        step += 1
        print(f"\n[{step}/{total_steps}] Pushing to remote...")
        if args.dry_run:
            print(f"      [DRY RUN] Skipping git push")
        else:
            success, err = git_push(with_tags=create_tag)
            if not success:
                print(f"      ERROR: {err}")
                sys.exit(1)
            if create_tag:
                print(f"      Pushed commits and tags")
            else:
                print(f"      Pushed commits")
        
        # Deploy (pack, upload, result)
        success = deploy(args.server, args.token, args.dry_run, step, total_steps)
        
    finally:
        # Cleanup
        cleanup_version_files()
        if build_android:
            cleanup_android_apk()
    
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
