#!/bin/bash
# Development mode: run backend and frontend separately

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

echo "Starting backend..."
backend/.venv/bin/python -m uvicorn backend.app:app --host 0.0.0.0 --port 8000 --reload &
BACKEND_PID=$!

echo "Starting frontend..."
cd frontend
npm run dev &
FRONTEND_PID=$!
cd ..

echo "Backend PID: $BACKEND_PID"
echo "Frontend PID: $FRONTEND_PID"
echo "Press Ctrl+C to stop both servers"

trap "kill $BACKEND_PID $FRONTEND_PID 2>/dev/null" EXIT
wait
