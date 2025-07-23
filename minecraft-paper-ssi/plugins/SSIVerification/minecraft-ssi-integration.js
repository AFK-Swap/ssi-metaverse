/**
 * Minecraft-SSI Integration Script
 * Connects Minecraft Paper server with existing SSI tutorial backend
 */

const express = require('express');
const QRCode = require('qrcode');
const { exec } = require('child_process');

// Configuration - matches your SSI tutorial setup
const CONFIG = {
    // Your existing SSI tutorial backend
    ISSUER_API_URL: process.env.ISSUER_API_URL || 'http://localhost:4000',
    VERIFIER_API_URL: process.env.VERIFIER_API_URL || 'http://localhost:4002',
    
    // ACA-Py admin (your working setup)
    ACAPY_ADMIN_URL: process.env.ACAPY_ADMIN_URL || 'http://localhost:8021',
    
    // Minecraft integration
    MINECRAFT_RCON_HOST: process.env.MINECRAFT_RCON_HOST || 'localhost',
    MINECRAFT_RCON_PORT: process.env.MINECRAFT_RCON_PORT || 25575,
    MINECRAFT_RCON_PASSWORD: process.env.MINECRAFT_RCON_PASSWORD || 'ssi_dev_2024',
    
    // Web server for QR codes
    QR_SERVER_PORT: process.env.QR_SERVER_PORT || 8080,
    
    // Credential definition from your setup
    CRED_DEF_ID: process.env.CRED_DEF_ID || 'AbH2V5oKsrPXbzbKKrpU3f:3:CL:2872881:University-Certificate'
};

class MinecraftSSIIntegration {
    constructor() {
        this.app = express();
        this.verificationSessions = new Map(); // playerName -> session
        this.verifiedPlayers = new Set();
        
        this.setupRoutes();
        this.startServer();
    }
    
    setupRoutes() {
        this.app.use(express.json());
        this.app.use(express.static('public'));
        
        // Start verification for a Minecraft player
        this.app.post('/verify-player', async (req, res) => {
            try {
                const { playerName } = req.body;
                if (!playerName) {
                    return res.status(400).json({ error: 'Player name required' });
                }
                
                // Check if already verified
                if (this.verifiedPlayers.has(playerName)) {
                    return res.json({ 
                        success: false, 
                        message: 'Player already verified',
                        verified: true 
                    });
                }
                
                // Start verification process
                const session = await this.startVerificationProcess(playerName);
                res.json(session);
                
            } catch (error) {
                console.error('Verification error:', error);
                res.status(500).json({ error: error.message });
            }
        });
        
        // Get QR code for verification
        this.app.get('/qr/:sessionId', async (req, res) => {
            try {
                const { sessionId } = req.params;
                const session = this.verificationSessions.get(sessionId);
                
                if (!session || !session.qrData) {
                    return res.status(404).send('QR code not found');
                }
                
                // Generate QR code image
                const qrImage = await QRCode.toBuffer(session.qrData, {
                    type: 'png',
                    width: 300,
                    margin: 2
                });
                
                res.setHeader('Content-Type', 'image/png');
                res.setHeader('Cache-Control', 'no-cache');
                res.send(qrImage);
                
            } catch (error) {
                console.error('QR generation error:', error);
                res.status(500).send('QR generation failed');
            }
        });
        
        // Check verification status
        this.app.get('/status/:playerName', (req, res) => {
            const { playerName } = req.params;
            const session = this.verificationSessions.get(playerName);
            
            res.json({
                playerName,
                verified: this.verifiedPlayers.has(playerName),
                session: session ? {
                    status: session.status,
                    qrUrl: session.qrUrl,
                    startTime: session.startTime
                } : null
            });
        });
    }
    
    async startVerificationProcess(playerName) {
        console.log(`Starting verification for player: ${playerName}`);
        
        // Step 1: Create connection invitation
        const connectionResponse = await fetch(`${CONFIG.ACAPY_ADMIN_URL}/out-of-band/create-invitation`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                alias: `Minecraft-Player-${playerName}`,
                handshake_protocols: ["https://didcomm.org/didexchange/1.0"]
            })
        });
        
        if (!connectionResponse.ok) {
            throw new Error(`Failed to create connection: ${connectionResponse.status}`);
        }
        
        const connectionData = await connectionResponse.json();
        const sessionId = `minecraft-${playerName}-${Date.now()}`;
        
        // Create session
        const session = {
            playerName,
            sessionId,
            connectionId: connectionData.connection_id,
            invitationUrl: connectionData.invitation_url,
            qrData: connectionData.invitation_url,
            qrUrl: `http://localhost:${CONFIG.QR_SERVER_PORT}/qr/${sessionId}`,
            status: 'waiting-connection',
            startTime: new Date().toISOString()
        };
        
        this.verificationSessions.set(sessionId, session);
        this.verificationSessions.set(playerName, session); // For easy lookup
        
        // Send message to Minecraft player
        this.sendMinecraftMessage(playerName, 
            `§aVerification started! Scan QR: §b${session.qrUrl}`);
        
        // Start monitoring connection and proof process
        this.monitorVerificationProcess(session);
        
        return {
            success: true,
            sessionId,
            playerName,
            qrUrl: session.qrUrl,
            invitationUrl: session.invitationUrl,
            message: 'Verification process started. Scan the QR code with your SSI wallet.'
        };
    }
    
    async monitorVerificationProcess(session) {
        const checkConnection = async () => {
            try {
                // Check connection status
                const connResponse = await fetch(
                    `${CONFIG.ACAPY_ADMIN_URL}/connections/${session.connectionId}`
                );
                
                if (!connResponse.ok) return;
                
                const connData = await connResponse.json();
                
                if (connData.state === 'active' && session.status === 'waiting-connection') {
                    console.log(`Connection established for ${session.playerName}`);
                    session.status = 'connected';
                    
                    this.sendMinecraftMessage(session.playerName, 
                        '§aConnected! Sending proof request...');
                    
                    // Send proof request
                    await this.sendProofRequest(session);
                }
                
                // If we're waiting for proof, check proof status
                if (session.status === 'proof-sent' && session.proofExchangeId) {
                    await this.checkProofStatus(session);
                }
                
            } catch (error) {
                console.error(`Monitoring error for ${session.playerName}:`, error);
            }
        };
        
        // Monitor every 3 seconds for 10 minutes max
        const interval = setInterval(checkConnection, 3000);
        setTimeout(() => {
            clearInterval(interval);
            if (!this.verifiedPlayers.has(session.playerName)) {
                this.sendMinecraftMessage(session.playerName, 
                    '§cVerification timeout. Try /verify again.');
                this.verificationSessions.delete(session.sessionId);
                this.verificationSessions.delete(session.playerName);
            }
        }, 600000); // 10 minutes
    }
    
    async sendProofRequest(session) {
        try {
            // Send proof request using your existing pattern
            const proofRequest = {
                connection_id: session.connectionId,
                presentation_request: {
                    indy: {
                        name: "Minecraft Server Identity Verification",
                        version: "1.0",
                        requested_attributes: {
                            department: {
                                names: ["department"],
                                restrictions: [{
                                    cred_def_id: CONFIG.CRED_DEF_ID
                                }]
                            }
                        },
                        requested_predicates: {
                            age_over_18: {
                                name: "age",
                                p_type: ">=",
                                p_value: 18,
                                restrictions: [{
                                    cred_def_id: CONFIG.CRED_DEF_ID
                                }]
                            }
                        }
                    }
                }
            };
            
            const response = await fetch(`${CONFIG.ACAPY_ADMIN_URL}/present-proof-2.0/send-request`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(proofRequest)
            });
            
            if (!response.ok) {
                throw new Error(`Proof request failed: ${response.status}`);
            }
            
            const proofData = await response.json();
            session.proofExchangeId = proofData.pres_ex_id;
            session.status = 'proof-sent';
            
            this.sendMinecraftMessage(session.playerName, 
                '§yProof request sent to your wallet. Please approve it!');
            
            console.log(`Proof request sent for ${session.playerName}:`, proofData.pres_ex_id);
            
        } catch (error) {
            console.error(`Failed to send proof request for ${session.playerName}:`, error);
            this.sendMinecraftMessage(session.playerName, 
                '§cFailed to send proof request. Try again.');
        }
    }
    
    async checkProofStatus(session) {
        try {
            const response = await fetch(
                `${CONFIG.ACAPY_ADMIN_URL}/present-proof-2.0/records/${session.proofExchangeId}`
            );
            
            if (!response.ok) return;
            
            const proofData = await response.json();
            
            if (proofData.state === 'done') {
                if (proofData.verified === 'true' || proofData.verified === true) {
                    // Verification successful!
                    this.verifiedPlayers.add(session.playerName);
                    session.status = 'verified';
                    
                    // Extract verified data
                    const verifiedData = this.extractVerifiedData(proofData);
                    
                    // Notify Minecraft
                    this.sendMinecraftMessage(session.playerName, 
                        `§a✓ Identity verified! Department: ${verifiedData.department || 'Unknown'}`);
                    
                    // Broadcast to server
                    this.broadcastMinecraftMessage(
                        `§6${session.playerName} §ahas been verified with SSI credentials!`);
                    
                    // Give verified benefits
                    this.giveVerifiedBenefits(session.playerName);
                    
                    console.log(`Verification completed for ${session.playerName}:`, verifiedData);
                    
                } else {
                    // Verification failed
                    this.sendMinecraftMessage(session.playerName, 
                        '§cIdentity verification failed. Check your credentials.');
                }
                
                // Cleanup session
                setTimeout(() => {
                    this.verificationSessions.delete(session.sessionId);
                    this.verificationSessions.delete(session.playerName);
                }, 30000); // Keep for 30 seconds for status checks
            }
            
        } catch (error) {
            console.error(`Failed to check proof status for ${session.playerName}:`, error);
        }
    }
    
    extractVerifiedData(proofData) {
        const data = {};
        
        try {
            // Extract from revealed attributes
            if (proofData.by_format?.pres?.indy?.requested_proof?.revealed_attr_groups) {
                const attrs = proofData.by_format.pres.indy.requested_proof.revealed_attr_groups;
                
                if (attrs.department?.values?.department?.raw) {
                    data.department = attrs.department.values.department.raw;
                }
            }
            
            // Note: Age predicate is verified but actual value not revealed (zero-knowledge)
            if (proofData.by_format?.pres?.indy?.requested_proof?.predicates?.age_over_18) {
                data.ageVerified = true;
            }
            
        } catch (error) {
            console.error('Error extracting verified data:', error);
        }
        
        return data;
    }
    
    sendMinecraftMessage(playerName, message) {
        // Send message to specific player via RCON
        const command = `tellraw ${playerName} {"text":"${message}"}`;
        this.executeMinecraftCommand(command);
    }
    
    broadcastMinecraftMessage(message) {
        // Broadcast message to all players
        const command = `say ${message}`;
        this.executeMinecraftCommand(command);
    }
    
    giveVerifiedBenefits(playerName) {
        // Give verified player benefits
        this.executeMinecraftCommand(`effect give ${playerName} minecraft:glowing 999999 0 true`);
        this.executeMinecraftCommand(`tellraw ${playerName} {"text":"§a✓ You now have verified player benefits!","bold":true}`);
    }
    
    executeMinecraftCommand(command) {
        // Use minecraft-rcon or direct command execution
        exec(`echo "${command}" | nc localhost 25575`, (error) => {
            if (error && error.code !== 1) { // nc returns 1 on success sometimes
                console.error('RCON command error:', error);
            }
        });
    }
    
    startServer() {
        this.app.listen(CONFIG.QR_SERVER_PORT, () => {
            console.log(`🎮 Minecraft-SSI Integration Server running on port ${CONFIG.QR_SERVER_PORT}`);
            console.log(`🔗 ACA-Py Admin URL: ${CONFIG.ACAPY_ADMIN_URL}`);
            console.log(`📱 QR codes available at: http://localhost:${CONFIG.QR_SERVER_PORT}/qr/{sessionId}`);
            console.log(`⚡ Ready for Minecraft verification requests!`);
        });
    }
}

// Start the integration server
if (require.main === module) {
    new MinecraftSSIIntegration();
}

module.exports = MinecraftSSIIntegration;