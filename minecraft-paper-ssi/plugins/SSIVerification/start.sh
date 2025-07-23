#!/bin/bash

# SSI Verification System Startup Script
echo "🎮 Starting Minecraft SSI Verification System..."

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed. Please install Node.js first."
    exit 1
fi

# Check if ACA-Py is running
echo "🔍 Checking ACA-Py status..."
if ! curl -s http://localhost:8021/status > /dev/null; then
    echo "⚠️  ACA-Py is not running on port 8021"
    echo "   Please start your ACA-Py agent first"
    echo "   Current SSI tutorial should be running"
else
    echo "✅ ACA-Py is running"
fi

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "📦 Installing Node.js dependencies..."
    npm install
fi

# Set environment variables for your setup
export ACAPY_ADMIN_URL="http://localhost:8021"
export CRED_DEF_ID="AbH2V5oKsrPXbzbKKrpU3f:3:CL:2872881:University-Certificate"
export QR_SERVER_PORT="8080"
export MINECRAFT_RCON_PASSWORD="ssi_dev_2024"

# Start the integration server
echo "🚀 Starting SSI Integration Server..."
echo "   - QR codes will be available at: http://localhost:8080/qr/{sessionId}"
echo "   - Integration API at: http://localhost:8080"
echo "   - Minecraft players can use: /verify"
echo ""

node minecraft-ssi-integration.js