package de.thelion.openPartiesAndClaimsBridge.packets

import de.thelion.openPartiesAndClaimsBridge.database.DatabaseManager
import de.thelion.openPartiesAndClaimsBridge.validation.ClaimValidator
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.logging.Logger

/**
 * Handles incoming and outgoing packets for the Open Parties and Claims bridge
 */
class PacketHandler(
    private val plugin: Plugin,
    private val databaseManager: DatabaseManager,
    private val claimValidator: ClaimValidator,
    private val logger: Logger
) : PluginMessageListener {
    
    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    
    companion object {
        const val CHANNEL_NAME = "openpartiesandclaims:bridge"
        const val PROTOCOL_VERSION = "1.0"
    }
    
    /**
     * Initialize the packet handler
     */
    fun initialize() {
        // Register plugin message channel
        plugin.server.messenger.registerIncomingPluginChannel(plugin, CHANNEL_NAME, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL_NAME)
        
        logger.info("Packet handler initialized with channel: $CHANNEL_NAME")
    }
    
    /**
     * Handle incoming plugin messages from clients
     */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL_NAME) return
        
        try {
            val input = DataInputStream(ByteArrayInputStream(message))
            val packetType = input.readUTF()
            val packetData = input.readUTF()
            
            logger.info("Received packet type: $packetType from player: ${player.name}")
            
            coroutineScope.launch {
                handlePacket(player, packetType, packetData)
            }
        } catch (e: Exception) {
            logger.severe("Failed to process packet from ${player.name}: ${e.message}")
            sendErrorPacket(player, "PACKET_PARSE_ERROR", "Failed to parse packet: ${e.message}")
        }
    }
    
    /**
     * Handle different packet types
     */
    private suspend fun handlePacket(player: Player, packetType: String, packetData: String) {
        try {
            when (packetType) {
                "CLAIM_CREATE" -> {
                    val packet = gson.fromJson(packetData, ClaimCreatePacket::class.java)
                    handleClaimCreate(player, packet)
                }
                "CLAIM_UPDATE" -> {
                    val packet = gson.fromJson(packetData, ClaimUpdatePacket::class.java)
                    handleClaimUpdate(player, packet)
                }
                "CLAIM_DELETE" -> {
                    val packet = gson.fromJson(packetData, ClaimDeletePacket::class.java)
                    handleClaimDelete(player, packet)
                }
                "CLAIM_SYNC_REQUEST" -> {
                    val packet = gson.fromJson(packetData, ClaimSyncRequestPacket::class.java)
                    handleClaimSyncRequest(player, packet)
                }
                "PARTY_CREATE" -> {
                    val packet = gson.fromJson(packetData, PartyCreatePacket::class.java)
                    handlePartyCreate(player, packet)
                }
                "PERMISSION_UPDATE" -> {
                    val packet = gson.fromJson(packetData, PermissionUpdatePacket::class.java)
                    handlePermissionUpdate(player, packet)
                }
                "HEARTBEAT" -> {
                    val packet = gson.fromJson(packetData, HeartbeatPacket::class.java)
                    handleHeartbeat(player, packet)
                }
                else -> {
                    logger.warning("Unknown packet type: $packetType from player: ${player.name}")
                    sendErrorPacket(player, "UNKNOWN_PACKET_TYPE", "Unknown packet type: $packetType")
                }
            }
        } catch (e: JsonSyntaxException) {
            logger.severe("Invalid JSON in packet from ${player.name}: ${e.message}")
            sendErrorPacket(player, "INVALID_JSON", "Invalid packet format")
        } catch (e: Exception) {
            logger.severe("Error handling packet from ${player.name}: ${e.message}")
            sendErrorPacket(player, "PROCESSING_ERROR", "Failed to process packet")
        }
    }
    
    /**
     * Handle claim creation requests
     */
    private suspend fun handleClaimCreate(player: Player, packet: ClaimCreatePacket) {
        try {
            // Validate player permissions
            if (packet.playerId != player.uniqueId) {
                sendErrorPacket(player, "PERMISSION_DENIED", "Cannot create claim for another player")
                return
            }
            
            // Validate claim data
            val validationResult = claimValidator.validateClaimCreation(
                player, packet.world, packet.minX, packet.minZ, packet.maxX, packet.maxZ
            )
            
            if (!validationResult.isValid) {
                sendErrorPacket(player, "VALIDATION_FAILED", validationResult.errorMessage ?: "Claim validation failed")
                return
            }
            
            // Create claim data
            val claimData = validationResult.claimData!!
            
            // Save to database
            val success = databaseManager.saveClaim(claimData)
            
            if (success) {
                logger.info("Created claim ${claimData.id} for player ${player.name}")
                sendClaimSyncResponse(player, listOf(claimData), emptyList())
            } else {
                sendErrorPacket(player, "DATABASE_ERROR", "Failed to save claim to database")
            }
        } catch (e: Exception) {
            logger.severe("Error creating claim for ${player.name}: ${e.message}")
            sendErrorPacket(player, "CREATION_ERROR", "Failed to create claim")
        }
    }
    
    /**
     * Handle claim update requests
     */
    private suspend fun handleClaimUpdate(player: Player, packet: ClaimUpdatePacket) {
        try {
            // Get existing claim
            val existingClaim = databaseManager.getClaim(packet.claimId)
            if (existingClaim == null) {
                sendErrorPacket(player, "CLAIM_NOT_FOUND", "Claim not found")
                return
            }
            
            // Check permissions
            if (existingClaim.ownerId != player.uniqueId && !player.hasPermission("openpartiesandclaims.admin")) {
                sendErrorPacket(player, "PERMISSION_DENIED", "Cannot modify another player's claim")
                return
            }
            
            // Update claim data
            val updatedClaim = existingClaim.copy(
                claimName = packet.claimName ?: existingClaim.claimName,
                description = packet.description ?: existingClaim.description,
                isPublic = packet.isPublic ?: existingClaim.isPublic,
                updatedAt = System.currentTimeMillis()
            )
            
            // Save to database
            val success = databaseManager.saveClaim(updatedClaim)
            
            if (success) {
                logger.info("Updated claim ${updatedClaim.id} for player ${player.name}")
                sendClaimSyncResponse(player, listOf(updatedClaim), emptyList())
            } else {
                sendErrorPacket(player, "DATABASE_ERROR", "Failed to update claim")
            }
        } catch (e: Exception) {
            logger.severe("Error updating claim for ${player.name}: ${e.message}")
            sendErrorPacket(player, "UPDATE_ERROR", "Failed to update claim")
        }
    }
    
    /**
     * Handle claim deletion requests
     */
    private suspend fun handleClaimDelete(player: Player, packet: ClaimDeletePacket) {
        try {
            // Get existing claim
            val existingClaim = databaseManager.getClaim(packet.claimId)
            if (existingClaim == null) {
                sendErrorPacket(player, "CLAIM_NOT_FOUND", "Claim not found")
                return
            }
            
            // Check permissions
            if (existingClaim.ownerId != player.uniqueId && !player.hasPermission("openpartiesandclaims.admin")) {
                sendErrorPacket(player, "PERMISSION_DENIED", "Cannot delete another player's claim")
                return
            }
            
            // Delete from database
            val success = databaseManager.deleteClaim(packet.claimId)
            
            if (success) {
                logger.info("Deleted claim ${packet.claimId} for player ${player.name}")
                // Send empty sync response to indicate deletion
                sendClaimSyncResponse(player, emptyList(), emptyList())
            } else {
                sendErrorPacket(player, "DATABASE_ERROR", "Failed to delete claim")
            }
        } catch (e: Exception) {
            logger.severe("Error deleting claim for ${player.name}: ${e.message}")
            sendErrorPacket(player, "DELETE_ERROR", "Failed to delete claim")
        }
    }
    
    /**
     * Handle claim synchronization requests
     */
    private suspend fun handleClaimSyncRequest(player: Player, packet: ClaimSyncRequestPacket) {
        try {
            // Get player's claims
            val claims = databaseManager.getClaimsByOwner(player.uniqueId)
            
            // Get player's parties
            val parties = databaseManager.getPartiesForPlayer(player.uniqueId)
            
            // Filter by timestamp if provided
            val filteredClaims = if (packet.lastSyncTimestamp > 0) {
                claims.filter { it.updatedAt > packet.lastSyncTimestamp }
            } else {
                claims
            }
            
            logger.info("Syncing ${filteredClaims.size} claims and ${parties.size} parties for player ${player.name}")
            sendClaimSyncResponse(player, filteredClaims, parties)
        } catch (e: Exception) {
            logger.severe("Error syncing claims for ${player.name}: ${e.message}")
            sendErrorPacket(player, "SYNC_ERROR", "Failed to sync claims")
        }
    }
    
    /**
     * Handle party creation requests
     */
    private suspend fun handlePartyCreate(player: Player, packet: PartyCreatePacket) {
        try {
            // Validate player permissions
            if (packet.leaderId != player.uniqueId) {
                sendErrorPacket(player, "PERMISSION_DENIED", "Cannot create party for another player")
                return
            }
            
            // Create party data
            val partyData = de.thelion.openPartiesAndClaimsBridge.data.PartyData(
                name = packet.partyName,
                leaderId = packet.leaderId,
                leaderName = packet.leaderName,
                description = packet.description,
                isOpen = packet.isOpen,
                maxMembers = packet.maxMembers
            )
            
            // Save to database
            val success = databaseManager.saveParty(partyData)
            
            if (success) {
                logger.info("Created party ${partyData.id} for player ${player.name}")
                sendClaimSyncResponse(player, emptyList(), listOf(partyData))
            } else {
                sendErrorPacket(player, "DATABASE_ERROR", "Failed to save party to database")
            }
        } catch (e: Exception) {
            logger.severe("Error creating party for ${player.name}: ${e.message}")
            sendErrorPacket(player, "PARTY_CREATION_ERROR", "Failed to create party")
        }
    }
    
    /**
     * Handle permission update requests
     */
    private suspend fun handlePermissionUpdate(player: Player, packet: PermissionUpdatePacket) {
        try {
            // Get existing claim
            val existingClaim = databaseManager.getClaim(packet.claimId)
            if (existingClaim == null) {
                sendErrorPacket(player, "CLAIM_NOT_FOUND", "Claim not found")
                return
            }
            
            // Check permissions
            if (existingClaim.ownerId != player.uniqueId && !player.hasPermission("openpartiesandclaims.admin")) {
                sendErrorPacket(player, "PERMISSION_DENIED", "Cannot modify permissions for another player's claim")
                return
            }
            
            // Update permissions
            val newPermission = de.thelion.openPartiesAndClaimsBridge.data.ClaimPermission(
                playerId = packet.targetPlayerId,
                playerName = packet.targetPlayerName,
                canBuild = packet.canBuild,
                canBreak = packet.canBreak,
                canInteract = packet.canInteract,
                canAccess = packet.canAccess,
                isAdmin = packet.isAdmin
            )
            
            val updatedPermissions = existingClaim.permissions.toMutableMap()
            updatedPermissions[packet.targetPlayerId.toString()] = newPermission
            
            val updatedClaim = existingClaim.copy(
                permissions = updatedPermissions,
                updatedAt = System.currentTimeMillis()
            )
            
            // Save to database
            val success = databaseManager.saveClaim(updatedClaim)
            
            if (success) {
                logger.info("Updated permissions for claim ${updatedClaim.id}")
                sendClaimSyncResponse(player, listOf(updatedClaim), emptyList())
            } else {
                sendErrorPacket(player, "DATABASE_ERROR", "Failed to update permissions")
            }
        } catch (e: Exception) {
            logger.severe("Error updating permissions for ${player.name}: ${e.message}")
            sendErrorPacket(player, "PERMISSION_UPDATE_ERROR", "Failed to update permissions")
        }
    }
    
    /**
     * Handle heartbeat packets
     */
    private suspend fun handleHeartbeat(player: Player, packet: HeartbeatPacket) {
        // Simply acknowledge the heartbeat
        sendHeartbeatResponse(player)
    }
    
    /**
     * Send claim sync response to client
     */
    private fun sendClaimSyncResponse(player: Player, claims: List<de.thelion.openPartiesAndClaimsBridge.data.ClaimData>, parties: List<de.thelion.openPartiesAndClaimsBridge.data.PartyData>) {
        val response = ClaimSyncResponsePacket(
            claims = claims,
            parties = parties,
            success = true
        )
        sendPacket(player, "CLAIM_SYNC_RESPONSE", response)
    }
    
    /**
     * Send error packet to client
     */
    private fun sendErrorPacket(player: Player, errorCode: String, errorMessage: String) {
        val errorPacket = ErrorPacket(
            errorCode = errorCode,
            errorMessage = errorMessage
        )
        sendPacket(player, "ERROR", errorPacket)
    }
    
    /**
     * Send heartbeat response to client
     */
    private fun sendHeartbeatResponse(player: Player) {
        val heartbeat = HeartbeatPacket(playerId = player.uniqueId)
        sendPacket(player, "HEARTBEAT_RESPONSE", heartbeat)
    }
    
    /**
     * Send a packet to the client (public method for external use)
     */
    fun sendPacket(player: Player, packetType: String, packet: PacketData) {
        try {
            val output = ByteArrayOutputStream()
            val dataOutput = DataOutputStream(output)
            
            dataOutput.writeUTF(packetType)
            dataOutput.writeUTF(gson.toJson(packet))
            
            player.sendPluginMessage(plugin, CHANNEL_NAME, output.toByteArray())
        } catch (e: Exception) {
            logger.severe("Failed to send packet to ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CHANNEL_NAME)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_NAME)
        logger.info("Packet handler shutdown complete")
    }
}
