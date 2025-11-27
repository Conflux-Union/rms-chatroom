#!/bin/bash
# Production mode: build frontend and serve from backend

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

echo "Building frontend..."
cd frontend
npm run build
cd ..

echo "Starting server..."
backend/.venv/bin/python -m uvicorn backend.app:app --host 0.0.0.0 --port 8000
