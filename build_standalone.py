#!/usr/bin/env python
"""
Build Standalone Executable

This script builds standalone executables for RMS Discord using PyInstaller.
It can be run locally or in CI/CD environments.

Usage:
    python build_standalone.py              # Build for current platform
    python build_standalone.py --clean      # Clean build artifacts first
    python build_standalone.py --onefile    # Build single file (slower startup)
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.resolve()
BACKEND_DIR = PROJECT_ROOT / "backend"
WEB_DIST_DIR = PROJECT_ROOT / "packages" / "web" / "dist"
BUILD_DIR = PROJECT_ROOT / "build"
DIST_DIR = PROJECT_ROOT / "dist"
SPEC_FILE = PROJECT_ROOT / "rms-discord.spec"
VENV_DIR = BACKEND_DIR / ".venv"
VENV_PYTHON = VENV_DIR / "bin" / "python"
VENV_PYINSTALLER = VENV_DIR / "bin" / "pyinstaller"


def get_platform_name() -> str:
    """Get platform name for output file."""
    system = platform.system().lower()
    machine = platform.machine().lower()

    if system == "windows":
        return "windows-x64" if machine in ("amd64", "x86_64") else "windows-x86"
    elif system == "linux":
        return "linux-x64" if machine in ("amd64", "x86_64") else f"linux-{machine}"
    elif system == "darwin":
        return "macos-universal"
    else:
        return f"{system}-{machine}"


def check_dependencies() -> bool:
    """Check if required dependencies are installed."""
    print("[1/7] Checking dependencies...")

    # Check virtual environment
    if not VENV_DIR.exists():
        print(f"      ERROR: Virtual environment not found: {VENV_DIR}")
        print("      Run: cd backend && python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt")
        return False

    # Check PyInstaller in venv
    if not VENV_PYINSTALLER.exists():
        print(f"      ERROR: PyInstaller not found in virtual environment")
        print("      Run: cd backend && source .venv/bin/activate && pip install pyinstaller")
        return False

    try:
        result = subprocess.run(
            [str(VENV_PYTHON), "-c", "import PyInstaller; print(PyInstaller.__version__)"],
            capture_output=True,
            text=True,
            check=True
        )
        print(f"      PyInstaller {result.stdout.strip()} found (venv)")
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("      ERROR: PyInstaller not working in venv")
        return False

    # Check pnpm
    try:
        result = subprocess.run(
            ["pnpm", "--version"],
            capture_output=True,
            text=True,
            check=True
        )
        print(f"      pnpm {result.stdout.strip()} found")
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("      ERROR: pnpm not found")
        print("      Install with: npm install -g pnpm")
        return False

    return True


def build_frontend() -> bool:
    """Build web frontend."""
    print("\n[2/7] Building web frontend...")

    if not WEB_DIST_DIR.parent.exists():
        print(f"      ERROR: Web directory not found: {WEB_DIST_DIR.parent}")
        return False

    try:
        result = subprocess.run(
            ["pnpm", "run", "build:web"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=300
        )

        if result.returncode != 0:
            print(f"      ERROR: Build failed")
            print(result.stderr[-1000:])
            return False

        if not WEB_DIST_DIR.exists():
            print(f"      ERROR: Build output not found: {WEB_DIST_DIR}")
            return False

        # Count files
        file_count = sum(1 for _ in WEB_DIST_DIR.rglob("*") if _.is_file())
        print(f"      Frontend built successfully ({file_count} files)")
        return True

    except subprocess.TimeoutExpired:
        print("      ERROR: Build timed out (5 minutes)")
        return False
    except Exception as e:
        print(f"      ERROR: {e}")
        return False


def generate_spec_file(onefile: bool = False) -> bool:
    """Generate PyInstaller spec file."""
    print("\n[3/7] Generating PyInstaller spec file...")

    # Collect backend data files
    backend_datas = []

    # Add alembic files
    alembic_dir = BACKEND_DIR / "migrations"
    if alembic_dir.exists():
        backend_datas.append(f"('{alembic_dir}', 'backend/migrations')")

    alembic_ini = BACKEND_DIR / "alembic.ini"
    if alembic_ini.exists():
        backend_datas.append(f"('{alembic_ini}', 'backend')")

    # Add frontend dist
    if WEB_DIST_DIR.exists():
        backend_datas.append(f"('{WEB_DIST_DIR}', 'frontend_dist')")

    datas_str = ",\n    ".join(backend_datas)

    # Hidden imports (packages that PyInstaller might miss)
    hidden_imports = [
        # Uvicorn
        'uvicorn.logging',
        'uvicorn.loops',
        'uvicorn.loops.auto',
        'uvicorn.protocols',
        'uvicorn.protocols.http',
        'uvicorn.protocols.http.auto',
        'uvicorn.protocols.websockets',
        'uvicorn.protocols.websockets.auto',
        'uvicorn.lifespan',
        'uvicorn.lifespan.on',
        # SQLAlchemy core
        'sqlalchemy',
        'sqlalchemy.sql',
        'sqlalchemy.sql.default_comparator',
        'sqlalchemy.ext.asyncio',
        'sqlalchemy.ext.asyncio.engine',
        'sqlalchemy.ext.asyncio.session',
        'sqlalchemy.ext.declarative',
        'sqlalchemy.orm',
        'sqlalchemy.orm.decl_api',
        'sqlalchemy.orm.session',
        'sqlalchemy.orm.strategies',
        'sqlalchemy.orm.query',
        'sqlalchemy.orm.attributes',
        'sqlalchemy.orm.relationships',
        'sqlalchemy.orm.mapper',
        'sqlalchemy.orm.state',
        'sqlalchemy.orm.util',
        'sqlalchemy.pool',
        'sqlalchemy.engine',
        'sqlalchemy.engine.default',
        'sqlalchemy.engine.reflection',
        'sqlalchemy.dialects',
        'sqlalchemy.dialects.sqlite',
        'sqlalchemy.dialects.sqlite.aiosqlite',
        'sqlalchemy.dialects.sqlite.pysqlite',
        # Async database drivers
        'aiosqlite',
        'aiomysql',
        # WebSockets
        'websockets',
        'websockets.legacy',
        'websockets.legacy.server',
        # Alembic
        'alembic',
        'alembic.runtime',
        'alembic.runtime.migration',
        'alembic.script',
        'alembic.config',
        'alembic.operations',
        'alembic.ddl',
        # FastAPI dependencies
        'starlette.middleware',
        'starlette.middleware.cors',
        'starlette.responses',
        'starlette.staticfiles',
        # Pydantic
        'pydantic',
        'pydantic.fields',
        'pydantic.main',
        # Other
        'pkg_resources',
    ]

    hidden_imports_str = ",\n    ".join(f"'{imp}'" for imp in hidden_imports)

    # Platform-specific settings
    exe_name = "rms-discord.exe" if platform.system() == "Windows" else "rms-discord"

    if onefile:
        spec_content = f"""# -*- mode: python ; coding: utf-8 -*-
# Auto-generated PyInstaller spec file for RMS Discord (One-file mode)

block_cipher = None

a = Analysis(
    ['standalone.py'],
    pathex=['{PROJECT_ROOT}'],
    binaries=[],
    datas=[
        {datas_str}
    ],
    hiddenimports=[
        {hidden_imports_str}
    ],
    hookspath=[],
    hooksconfig={{}},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='{exe_name}',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
"""
    else:
        spec_content = f"""# -*- mode: python ; coding: utf-8 -*-
# Auto-generated PyInstaller spec file for RMS Discord (One-dir mode)

block_cipher = None

a = Analysis(
    ['standalone.py'],
    pathex=['{PROJECT_ROOT}'],
    binaries=[],
    datas=[
        {datas_str}
    ],
    hiddenimports=[
        {hidden_imports_str}
    ],
    hookspath=[],
    hooksconfig={{}},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='{exe_name}',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='rms-discord',
)
"""

    try:
        SPEC_FILE.write_text(spec_content)
        print(f"      Spec file generated: {SPEC_FILE}")
        return True
    except Exception as e:
        print(f"      ERROR: Failed to generate spec file: {e}")
        return False


def run_pyinstaller() -> bool:
    """Run PyInstaller to build executable."""
    print("\n[4/7] Running PyInstaller...")

    try:
        result = subprocess.run(
            [str(VENV_PYINSTALLER), "--clean", "-y", str(SPEC_FILE)],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            print("      ERROR: PyInstaller failed")
            print(result.stderr[-2000:])
            return False

        print("      PyInstaller completed successfully")
        return True

    except Exception as e:
        print(f"      ERROR: {e}")
        return False


def verify_build() -> bool:
    """Verify that the build output exists."""
    print("\n[5/7] Verifying build output...")

    exe_name = "rms-discord.exe" if platform.system() == "Windows" else "rms-discord"
    exe_path = DIST_DIR / "rms-discord" / exe_name

    if not exe_path.exists():
        print(f"      ERROR: Executable not found: {exe_path}")
        return False

    size_mb = exe_path.stat().st_size / (1024 * 1024)
    print(f"      Executable found: {exe_path}")
    print(f"      Size: {size_mb:.1f} MB")
    return True


def create_release_package() -> bool:
    """Create release package with README."""
    print("\n[6/7] Creating release package...")

    platform_name = get_platform_name()
    package_name = f"rms-discord-standalone-{platform_name}"
    package_dir = DIST_DIR / package_name

    try:
        # Remove old package if exists
        if package_dir.exists():
            shutil.rmtree(package_dir)

        # Copy dist files
        shutil.copytree(DIST_DIR / "rms-discord", package_dir)

        # Create README
        readme_content = """# RMS Discord Standalone

This is a standalone executable package of RMS Discord.

## First Run

1. Run the executable:
   - Windows: Double-click `rms-discord.exe`
   - Linux/macOS: `./rms-discord`

2. On first run, a `config.json` file will be created in the same directory.

3. Edit `config.json` and set:
   - `oauth_client_id`: Your OAuth client ID
   - `oauth_client_secret`: Your OAuth client secret
   - `oauth_redirect_uri`: Your callback URL (default: http://localhost:8000/api/auth/callback)

4. Restart the application.

5. Open your browser and navigate to http://localhost:8000

## Configuration

All configuration is in `config.json`. Key settings:

- `host`: Server bind address (default: 0.0.0.0)
- `port`: Server port (default: 8000)
- `database_url`: Database connection string (default: SQLite in same directory)
- `cors_origins`: Allowed CORS origins

## Data Files

- `config.json`: Configuration file
- `discord.db`: SQLite database (if using default database)
- `frontend_dist/`: Frontend static files (auto-extracted on first run)
- `uploads/`: User uploaded files

## Troubleshooting

If the application fails to start:

1. Check that `config.json` has valid OAuth credentials
2. Check that port 8000 is not in use
3. Check the console output for error messages

For more information, visit: https://github.com/RMS-Server/rms-chatroom
"""

        readme_path = package_dir / "README.md"
        readme_path.write_text(readme_content)

        # Create archive
        archive_name = f"{package_name}"
        archive_format = "zip" if platform.system() == "Windows" else "gztar"

        shutil.make_archive(
            str(DIST_DIR / archive_name),
            archive_format,
            DIST_DIR,
            package_name
        )

        archive_ext = ".zip" if platform.system() == "Windows" else ".tar.gz"
        archive_path = DIST_DIR / f"{archive_name}{archive_ext}"

        if archive_path.exists():
            size_mb = archive_path.stat().st_size / (1024 * 1024)
            print(f"      Package created: {archive_path}")
            print(f"      Size: {size_mb:.1f} MB")
            return True
        else:
            print("      ERROR: Archive not created")
            return False

    except Exception as e:
        print(f"      ERROR: {e}")
        return False


def cleanup(clean_all: bool = False):
    """Clean build artifacts."""
    print("\n[7/7] Cleaning up...")

    try:
        if BUILD_DIR.exists():
            shutil.rmtree(BUILD_DIR)
            print(f"      Removed: {BUILD_DIR}")

        if clean_all and DIST_DIR.exists():
            shutil.rmtree(DIST_DIR)
            print(f"      Removed: {DIST_DIR}")

        if SPEC_FILE.exists():
            SPEC_FILE.unlink()
            print(f"      Removed: {SPEC_FILE}")

    except Exception as e:
        print(f"      Warning: Cleanup failed: {e}")


def main():
    parser = argparse.ArgumentParser(description="Build RMS Discord standalone executable")
    parser.add_argument("--clean", action="store_true", help="Clean build artifacts before building")
    parser.add_argument("--onefile", action="store_true", help="Build single file executable (slower startup)")
    parser.add_argument("--skip-frontend", action="store_true", help="Skip frontend build (use existing)")

    args = parser.parse_args()

    print("=" * 60)
    print("RMS Discord Standalone Builder")
    print(f"Platform: {get_platform_name()}")
    print("=" * 60)

    # Clean if requested
    if args.clean:
        cleanup(clean_all=True)

    # Step 1: Check dependencies
    if not check_dependencies():
        sys.exit(1)

    # Step 2: Build frontend
    if not args.skip_frontend:
        if not build_frontend():
            sys.exit(1)
    else:
        print("\n[2/7] Skipping frontend build (--skip-frontend)")

    # Step 3: Generate spec file
    if not generate_spec_file(onefile=args.onefile):
        sys.exit(1)

    # Step 4: Run PyInstaller
    if not run_pyinstaller():
        sys.exit(1)

    # Step 5: Verify build
    if not verify_build():
        sys.exit(1)

    # Step 6: Create release package
    if not create_release_package():
        sys.exit(1)

    # Step 7: Cleanup
    cleanup(clean_all=False)

    print("\n" + "=" * 60)
    print("Build completed successfully!")
    print(f"Output: {DIST_DIR}")
    print("=" * 60)


if __name__ == "__main__":
    main()
