package de.thelion.openPartiesAndClaimsBridge.packets

import de.thelion.openPartiesAndClaimsBridge.data.ClaimData
import de.thelion.openPartiesAndClaimsBridge.data.PartyData
import java.util.*

/**
 * Base class for all packets exchanged between client and server
 */
sealed class PacketData {
    abstract val packetId: String
    abstract val timestamp: Long
}

/**
 * Packet types for different operations
 */
enum class PacketType {
    CLAIM_CREATE,
    CLAIM_UPDATE,
    CLAIM_DELETE,
    CLAIM_SYNC_REQUEST,
    CLAIM_SYNC_RESPONSE,
    PARTY_CREATE,
    PARTY_UPDATE,
    PARTY_DELETE,
    PARTY_SYNC_REQUEST,
    PARTY_SYNC_RESPONSE,
    PERMISSION_UPDATE,
    HEARTBEAT,
    ERROR
}

/**
 * Packet for creating a new claim
 */
data class ClaimCreatePacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val playerId: UUID,
    val playerName: String,
    val world: String,
    val minX: Int,
    val minZ: Int,
    val maxX: Int,
    val maxZ: Int,
    val claimName: String? = null,
    val description: String? = null
) : PacketData()

/**
 * Packet for updating an existing claim
 */
data class ClaimUpdatePacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val claimId: String,
    val playerId: UUID,
    val claimName: String? = null,
    val description: String? = null,
    val isPublic: Boolean? = null
) : PacketData()

/**
 * Packet for deleting a claim
 */
data class ClaimDeletePacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val claimId: String,
    val playerId: UUID
) : PacketData()

/**
 * Packet for requesting claim synchronization
 */
data class ClaimSyncRequestPacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val playerId: UUID,
    val lastSyncTimestamp: Long = 0
) : PacketData()

/**
 * Packet for responding with claim data
 */
data class ClaimSyncResponsePacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val claims: List<ClaimData>,
    val parties: List<PartyData>,
    val success: Boolean = true,
    val errorMessage: String? = null
) : PacketData()

/**
 * Packet for creating a new party
 */
data class PartyCreatePacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val leaderId: UUID,
    val leaderName: String,
    val partyName: String,
    val description: String? = null,
    val isOpen: Boolean = false,
    val maxMembers: Int = 10
) : PacketData()

/**
 * Packet for updating party permissions
 */
data class PermissionUpdatePacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val claimId: String,
    val targetPlayerId: UUID,
    val targetPlayerName: String,
    val requesterId: UUID,
    val canBuild: Boolean = false,
    val canBreak: Boolean = false,
    val canInteract: Boolean = false,
    val canAccess: Boolean = false,
    val isAdmin: Boolean = false
) : PacketData()

/**
 * Generic error packet
 */
data class ErrorPacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val errorCode: String,
    val errorMessage: String,
    val originalPacketId: String? = null
) : PacketData()

/**
 * Heartbeat packet to maintain connection
 */
data class HeartbeatPacket(
    override val packetId: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val playerId: UUID
) : PacketData()
