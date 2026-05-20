#!/bin/bash
# Restart the transform-platform API with new code
# Run from the transform-platform root directory:
#   chmod +x restart-app.sh && ./restart-app.sh

set -e
cd "$(dirname "$0")"

echo "Stopping any running platform-api process..."
pkill -f "com.transformplatform" 2>/dev/null || true
pkill -f ":platform-api:bootRun" 2>/dev/null || true
sleep 2

echo "Starting platform-api..."
./gradlew :platform-api:bootRun -PskipFrontend
