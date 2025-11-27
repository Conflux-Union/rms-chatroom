#!/bin/bash
# Production mode: build frontend and serve from backend

set -e

echo "Building frontend..."
cd frontend
npm run build
cd ..

echo "Starting server..."
cd backend
source .venv/bin/activate
python -m uvicorn backend.app:app --host 0.0.0.0 --port 8000
