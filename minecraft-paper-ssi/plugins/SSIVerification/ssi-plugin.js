/**
 * Simple Minecraft plugin script that hooks into server commands
 * Works with Paper server's JavaScript engine
 */

const fetch = require('node-fetch');
const INTEGRATION_URL = 'http://localhost:8080';

// Store verification states
global.verificationStates = global.verificationStates || new Map();

// Handle /verify command
function handleVerifyCommand(player) {
    const playerName = player.getName();
    
    // Check if already verified
    if (global.verificationStates.get(playerName)?.verified) {
        player.sendMessage('§a✓ You are already verified!');
        return;
    }
    
    // Check if verification in progress
    const currentState = global.verificationStates.get(playerName);
    if (currentState?.status === 'in-progress') {
        player.sendMessage('§yVerification already in progress...');
        if (currentState.qrUrl) {
            player.sendMessage('§bQR Code: ' + currentState.qrUrl);
        }
        return;
    }
    
    player.sendMessage('§6=== SSI Identity Verification ===');
    player.sendMessage('§yStarting verification process...');
    player.sendMessage('§7You will need an SSI wallet with valid credentials.');
    
    // Call integration server
    fetch(`${INTEGRATION_URL}/verify-player`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ playerName })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            global.verificationStates.set(playerName, {
                status: 'in-progress',
                qrUrl: data.qrUrl,
                sessionId: data.sessionId
            });
            
            player.sendMessage('§a✓ QR code generated!');
            player.sendMessage('§bScan this QR with your SSI wallet:');
            player.sendMessage('§9§l' + data.qrUrl);
            player.sendMessage('§7Or visit the URL in your browser and scan with your phone');
            
        } else {
            player.sendMessage('§c✗ ' + (data.message || 'Verification failed to start'));
            if (data.verified) {
                global.verificationStates.set(playerName, { verified: true });
            }
        }
    })
    .catch(error => {
        console.error('Verification request failed:', error);
        player.sendMessage('§cVerification system unavailable. Try again later.');
    });
}

// Handle /ssiverify command  
function handleSSIVerifyCommand(sender, args) {
    if (args.length === 0) {
        sender.sendMessage('§cUsage: /ssiverify <player>');
        return;
    }
    
    const targetPlayerName = args[0];
    
    fetch(`${INTEGRATION_URL}/status/${targetPlayerName}`)
    .then(response => response.json())
    .then(data => {
        sender.sendMessage('§6=== Verification Status ===');
        sender.sendMessage('§fPlayer: §e' + data.playerName);
        sender.sendMessage('§fStatus: ' + (data.verified ? '§a✓ VERIFIED' : '§c✗ NOT VERIFIED'));
        
        if (data.session) {
            sender.sendMessage('§fSession Status: §7' + data.session.status);
            if (data.session.qrUrl) {
                sender.sendMessage('§fQR Code: §b' + data.session.qrUrl);
            }
        }
    })
    .catch(error => {
        sender.sendMessage('§cFailed to check verification status');
        console.error('Status check failed:', error);
    });
}

// Register commands when plugin loads
if (typeof server !== 'undefined') {
    // Paper server JavaScript environment
    server.getPluginManager().registerEvents({
        onPlayerCommandPreprocess: function(event) {
            const message = event.getMessage().toLowerCase();
            const player = event.getPlayer();
            
            if (message === '/verify') {
                event.setCancelled(true);
                handleVerifyCommand(player);
            } else if (message.startsWith('/ssiverify ')) {
                event.setCancelled(true);
                const args = message.split(' ').slice(1);
                handleSSIVerifyCommand(player, args);
            }
        }
    }, plugin);
}

// Export functions for external use
module.exports = {
    handleVerifyCommand,
    handleSSIVerifyCommand
};