package de.thelion.openPartiesAndClaimsBridge.data

import java.util.*

/**
 * Represents a claim in the world
 */
data class ClaimData(
    val id: String = UUID.randomUUID().toString(),
    val ownerId: UUID,
    val ownerName: String,
    val world: String,
    val minX: Int,
    val minZ: Int,
    val maxX: Int,
    val maxZ: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val claimName: String? = null,
    val description: String? = null,
    val isPublic: Boolean = false,
    val permissions: Map<String, ClaimPermission> = emptyMap()
)

/**
 * Represents permissions for a claim
 */
data class ClaimPermission(
    val playerId: UUID,
    val playerName: String,
    val canBuild: Boolean = false,
    val canBreak: Boolean = false,
    val canInteract: Boolean = false,
    val canAccess: Boolean = false,
    val isAdmin: Boolean = false
)

/**
 * Represents a party in the system
 */
data class PartyData(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val leaderId: UUID,
    val leaderName: String,
    val members: Set<UUID> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val description: String? = null,
    val isOpen: Boolean = false,
    val maxMembers: Int = 10
)
