package de.thelion.openPartiesAndClaimsBridge.sync

import de.thelion.openPartiesAndClaimsBridge.data.ClaimData
import de.thelion.openPartiesAndClaimsBridge.data.PartyData
import de.thelion.openPartiesAndClaimsBridge.database.DatabaseManager
import de.thelion.openPartiesAndClaimsBridge.packets.PacketHandler
import de.thelion.openPartiesAndClaimsBridge.packets.ClaimSyncResponsePacket
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Manages synchronization of claim data between the server plugin and client mods
 */
class ClaimSynchronizer(
    private val plugin: Plugin,
    private val databaseManager: DatabaseManager,
    private val packetHandler: PacketHandler,
    private val logger: Logger
) {
    
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val playerSyncTimestamps = ConcurrentHashMap<java.util.UUID, Long>()
    private val syncJobs = ConcurrentHashMap<java.util.UUID, Job>()
    
    companion object {
        const val SYNC_INTERVAL_SECONDS = 30L
        const val BATCH_SIZE = 50
        const val MAX_SYNC_RETRIES = 3
    }
    
    /**
     * Initialize the synchronizer
     */
    fun initialize() {
        logger.info("Claim synchronizer initialized")
        
        // Start periodic sync for online players
        startPeriodicSync()
    }
    
    /**
     * Start periodic synchronization for all online players
     */
    private fun startPeriodicSync() {
        coroutineScope.launch {
            while (isActive) {
                try {
                    val onlinePlayers = Bukkit.getOnlinePlayers()
                    logger.fine("Running periodic sync for ${onlinePlayers.size} online players")
                    
                    onlinePlayers.forEach { player ->
                        launch {
                            syncPlayerData(player, false)
                        }
                    }
                    
                    delay(SYNC_INTERVAL_SECONDS * 1000)
                } catch (e: Exception) {
                    logger.severe("Error in periodic sync: ${e.message}")
                    delay(5000) // Wait 5 seconds before retrying
                }
            }
        }
    }
    
    /**
     * Synchronize data for a specific player
     */
    suspend fun syncPlayerData(player: Player, forceFullSync: Boolean = false) {
        try {
            // Cancel any existing sync job for this player
            syncJobs[player.uniqueId]?.cancel()
            
            val syncJob = coroutineScope.launch {
                performPlayerSync(player, forceFullSync)
            }
            
            syncJobs[player.uniqueId] = syncJob
            syncJob.join()
        } catch (e: Exception) {
            logger.severe("Error syncing data for player ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Perform the actual synchronization for a player
     */
    private suspend fun performPlayerSync(player: Player, forceFullSync: Boolean) {
        try {
            val lastSyncTime = if (forceFullSync) 0L else playerSyncTimestamps[player.uniqueId] ?: 0L
            val currentTime = System.currentTimeMillis()
            
            // Get updated claims
            val allClaims = databaseManager.getClaimsByOwner(player.uniqueId)
            val updatedClaims = if (lastSyncTime > 0) {
                allClaims.filter { it.updatedAt > lastSyncTime }
            } else {
                allClaims
            }
            
            // Get updated parties
            val allParties = databaseManager.getPartiesForPlayer(player.uniqueId)
            val updatedParties = if (lastSyncTime > 0) {
                allParties.filter { it.createdAt > lastSyncTime }
            } else {
                allParties
            }
            
            // Send data in batches if necessary
            if (updatedClaims.isNotEmpty() || updatedParties.isNotEmpty() || forceFullSync) {
                sendSyncDataInBatches(player, updatedClaims, updatedParties)
                playerSyncTimestamps[player.uniqueId] = currentTime
                
                logger.fine("Synced ${updatedClaims.size} claims and ${updatedParties.size} parties for ${player.name}")
            }
        } catch (e: Exception) {
            logger.severe("Error performing sync for ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Send synchronization data in batches to avoid overwhelming the client
     */
    private suspend fun sendSyncDataInBatches(
        player: Player,
        claims: List<ClaimData>,
        parties: List<PartyData>
    ) {
        try {
            // Send claims in batches
            val claimBatches = claims.chunked(BATCH_SIZE)
            val partyBatches = parties.chunked(BATCH_SIZE)
            
            val maxBatches = maxOf(claimBatches.size, partyBatches.size)
            
            for (i in 0 until maxBatches) {
                val claimBatch = claimBatches.getOrNull(i) ?: emptyList()
                val partyBatch = if (i == 0) partyBatches.getOrNull(i) ?: emptyList() else emptyList()
                
                val syncResponse = ClaimSyncResponsePacket(
                    claims = claimBatch,
                    parties = partyBatch,
                    success = true
                )
                
                // Send via packet handler
                withContext(Dispatchers.Main) {
                    packetHandler.sendPacket(player, "CLAIM_SYNC_RESPONSE", syncResponse)
                }
                
                // Small delay between batches to prevent overwhelming
                if (i < maxBatches - 1) {
                    delay(100)
                }
            }
        } catch (e: Exception) {
            logger.severe("Error sending sync batches to ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Handle player join - perform initial sync
     */
    suspend fun onPlayerJoin(player: Player) {
        try {
            logger.info("Player ${player.name} joined, performing initial sync")
            
            // Delay initial sync to allow client mod to initialize
            delay(2000)
            
            syncPlayerData(player, forceFullSync = true)
        } catch (e: Exception) {
            logger.severe("Error during player join sync for ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Handle player quit - cleanup sync data
     */
    fun onPlayerQuit(player: Player) {
        try {
            // Cancel any ongoing sync job
            syncJobs[player.uniqueId]?.cancel()
            syncJobs.remove(player.uniqueId)
            
            // Keep timestamp for when they rejoin
            logger.fine("Cleaned up sync data for ${player.name}")
        } catch (e: Exception) {
            logger.severe("Error during player quit cleanup for ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Force sync for a specific claim (when it's updated)
     */
    suspend fun syncClaimUpdate(claimData: ClaimData) {
        try {
            val owner = Bukkit.getPlayer(claimData.ownerId)
            if (owner != null && owner.isOnline) {
                val syncResponse = ClaimSyncResponsePacket(
                    claims = listOf(claimData),
                    parties = emptyList(),
                    success = true
                )
                
                withContext(Dispatchers.Main) {
                    packetHandler.sendPacket(owner, "CLAIM_SYNC_RESPONSE", syncResponse)
                }
                
                logger.fine("Force synced claim ${claimData.id} to ${owner.name}")
            }
        } catch (e: Exception) {
            logger.severe("Error force syncing claim ${claimData.id}: ${e.message}")
        }
    }
    
    /**
     * Force sync for a specific party (when it's updated)
     */
    suspend fun syncPartyUpdate(partyData: PartyData) {
        try {
            // Sync to leader
            val leader = Bukkit.getPlayer(partyData.leaderId)
            if (leader != null && leader.isOnline) {
                sendPartySync(leader, partyData)
            }
            
            // Sync to all members
            partyData.members.forEach { memberId ->
                val member = Bukkit.getPlayer(memberId)
                if (member != null && member.isOnline) {
                    sendPartySync(member, partyData)
                }
            }
        } catch (e: Exception) {
            logger.severe("Error force syncing party ${partyData.id}: ${e.message}")
        }
    }
    
    /**
     * Send party sync to a specific player
     */
    private suspend fun sendPartySync(player: Player, partyData: PartyData) {
        try {
            val syncResponse = ClaimSyncResponsePacket(
                claims = emptyList(),
                parties = listOf(partyData),
                success = true
            )
            
            withContext(Dispatchers.Main) {
                packetHandler.sendPacket(player, "CLAIM_SYNC_RESPONSE", syncResponse)
            }
            
            logger.fine("Force synced party ${partyData.id} to ${player.name}")
        } catch (e: Exception) {
            logger.severe("Error sending party sync to ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Get sync statistics for monitoring
     */
    fun getSyncStatistics(): SyncStatistics {
        return SyncStatistics(
            activeSyncJobs = syncJobs.size,
            trackedPlayers = playerSyncTimestamps.size,
            lastSyncTimes = playerSyncTimestamps.toMap()
        )
    }
    
    /**
     * Shutdown the synchronizer
     */
    fun shutdown() {
        try {
            // Cancel all sync jobs
            syncJobs.values.forEach { it.cancel() }
            syncJobs.clear()
            
            // Cancel the coroutine scope
            coroutineScope.cancel()
            
            logger.info("Claim synchronizer shutdown complete")
        } catch (e: Exception) {
            logger.severe("Error during synchronizer shutdown: ${e.message}")
        }
    }
    
    /**
     * Statistics data class
     */
    data class SyncStatistics(
        val activeSyncJobs: Int,
        val trackedPlayers: Int,
        val lastSyncTimes: Map<java.util.UUID, Long>
    )
}
