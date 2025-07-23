#!/bin/bash

echo "🎮 Starting Minecraft Paper Server with SSI Verification"
echo "=================================================="

# Check if ACA-Py is running
echo "🔍 Checking ACA-Py status..."
if curl -s http://localhost:8021/status > /dev/null; then
    echo "✅ ACA-Py is running on port 8021"
else
    echo "❌ ACA-Py is not running!"
    echo "   Please start your SSI tutorial first"
    echo "   Make sure ACA-Py is accessible on http://localhost:8021"
    exit 1
fi

# Start SSI integration server in background
echo "🚀 Starting SSI Integration Server..."
cd plugins/SSIVerification
nohup node minecraft-ssi-integration.js > ssi-integration.log 2>&1 &
SSI_PID=$!
echo "   SSI Integration Server PID: $SSI_PID"
echo "   Logs: plugins/SSIVerification/ssi-integration.log"

# Wait a moment for integration server to start
sleep 3

# Check if integration server started successfully
if curl -s http://localhost:8080/status/test > /dev/null 2>&1; then
    echo "✅ SSI Integration Server is running on port 8080"
else
    echo "⚠️  SSI Integration Server may not be fully ready yet"
fi

# Go back to server directory
cd ../..

# Create plugin configuration if it doesn't exist
mkdir -p plugins/SSIVerification
if [ ! -f "plugins/SSIVerification/config.yml" ]; then
    cat > plugins/SSIVerification/config.yml << 'EOF'
# SSI Verification Plugin Configuration
acapy:
  admin-url: "http://localhost:8021"
  credential-definition-id: "AbH2V5oKsrPXbzbKKrpU3f:3:CL:2872881:University-Certificate"

verification:
  proof-name: "Minecraft Server Identity Verification"
  required-attributes:
    - "department"
  required-predicates:
    age-over-18:
      attribute: "age"
      predicate-type: ">="
      value: 18

qrcode:
  web-server:
    port: 8080
    host: "localhost"

settings:
  verified-benefits:
    broadcast-verification: true
    chat-prefix: "&a[VERIFIED]&r "
EOF
    echo "📝 Created plugin configuration"
fi

echo ""
echo "🎮 Starting Minecraft Paper Server..."
echo "   Available commands for players:"
echo "   - /verify           : Start SSI verification process"
echo "   - /ssiverify <player> : Check verification status"
echo ""
echo "   QR codes will be available at: http://localhost:8080/qr/{sessionId}"
echo "   Players will receive QR URLs when they run /verify"
echo ""

# Function to cleanup on exit
cleanup() {
    echo ""
    echo "🛑 Shutting down servers..."
    if [ ! -z "$SSI_PID" ]; then
        kill $SSI_PID 2>/dev/null
        echo "   Stopped SSI Integration Server"
    fi
    exit 0
}

# Set trap to cleanup on exit
trap cleanup SIGINT SIGTERM

# Start Minecraft server
java -Xmx4G -Xms2G \
     -XX:+UseG1GC \
     -XX:+ParallelRefProcEnabled \
     -XX:MaxGCPauseMillis=200 \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+DisableExplicitGC \
     -XX:+AlwaysPreTouch \
     -XX:G1NewSizePercent=30 \
     -XX:G1MaxNewSizePercent=40 \
     -XX:G1HeapRegionSize=8M \
     -XX:G1ReservePercent=20 \
     -XX:G1HeapWastePercent=5 \
     -XX:G1MixedGCCountTarget=4 \
     -XX:InitiatingHeapOccupancyPauseTimePercent=15 \
     -XX:G1MixedGCLiveThresholdPercent=90 \
     -XX:G1RSetUpdatingPauseTimePercent=5 \
     -XX:SurvivorRatio=32 \
     -XX:+PerfDisableSharedMem \
     -XX:MaxTenuringThreshold=1 \
     -jar paper-server.jar nogui

# Cleanup when server stops
cleanup