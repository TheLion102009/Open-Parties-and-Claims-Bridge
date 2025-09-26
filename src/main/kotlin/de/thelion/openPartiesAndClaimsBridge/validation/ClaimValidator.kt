package de.thelion.openPartiesAndClaimsBridge.validation

import de.thelion.openPartiesAndClaimsBridge.data.ClaimData
import de.thelion.openPartiesAndClaimsBridge.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.logging.Logger
import kotlin.math.abs

/**
 * Validates claim operations and ensures they meet server requirements
 */
class ClaimValidator(
    private val plugin: Plugin,
    private val databaseManager: DatabaseManager,
    private val logger: Logger
) {
    
    companion object {
        const val MIN_CLAIM_SIZE = 16 // Minimum claim size in blocks
        const val MAX_CLAIM_SIZE = 1024 // Maximum claim size in blocks
        const val MAX_CLAIMS_PER_PLAYER = 10 // Maximum claims per player
        const val MIN_DISTANCE_BETWEEN_CLAIMS = 8 // Minimum distance between claims
    }
    
    /**
     * Result of claim validation
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val claimData: ClaimData? = null
    )
    
    /**
     * Validate claim creation request
     */
    suspend fun validateClaimCreation(
        player: Player,
        world: String,
        minX: Int,
        minZ: Int,
        maxX: Int,
        maxZ: Int
    ): ValidationResult = withContext(Dispatchers.Default) {
        
        try {
            // Ensure coordinates are properly ordered
            val actualMinX = minOf(minX, maxX)
            val actualMaxX = maxOf(minX, maxX)
            val actualMinZ = minOf(minZ, maxZ)
            val actualMaxZ = maxOf(minZ, maxZ)
            
            // Check claim size
            val width = actualMaxX - actualMinX + 1
            val height = actualMaxZ - actualMinZ + 1
            val area = width * height
            
            if (width < MIN_CLAIM_SIZE || height < MIN_CLAIM_SIZE) {
                return@withContext ValidationResult(
                    false, 
                    "Claim too small. Minimum size is ${MIN_CLAIM_SIZE}x${MIN_CLAIM_SIZE} blocks"
                )
            }
            
            if (width > MAX_CLAIM_SIZE || height > MAX_CLAIM_SIZE) {
                return@withContext ValidationResult(
                    false, 
                    "Claim too large. Maximum size is ${MAX_CLAIM_SIZE}x${MAX_CLAIM_SIZE} blocks"
                )
            }
            
            // Check if player has permission to claim in this world
            if (!player.hasPermission("openpartiesandclaims.claim") && 
                !player.hasPermission("openpartiesandclaims.claim.$world")) {
                return@withContext ValidationResult(
                    false, 
                    "You don't have permission to create claims in this world"
                )
            }
            
            // Check player's claim limit
            val existingClaims = databaseManager.getClaimsByOwner(player.uniqueId)
            if (existingClaims.size >= MAX_CLAIMS_PER_PLAYER && !player.hasPermission("openpartiesandclaims.unlimited")) {
                return@withContext ValidationResult(
                    false, 
                    "You have reached the maximum number of claims ($MAX_CLAIMS_PER_PLAYER)"
                )
            }
            
            // Check for overlapping claims
            val overlappingClaims = databaseManager.getClaimsInArea(world, actualMinX, actualMinZ, actualMaxX, actualMaxZ)
            if (overlappingClaims.isNotEmpty()) {
                val conflictingClaim = overlappingClaims.first()
                return@withContext ValidationResult(
                    false, 
                    "This area overlaps with an existing claim owned by ${conflictingClaim.ownerName}"
                )
            }
            
            // Check minimum distance from other claims (if not admin)
            if (!player.hasPermission("openpartiesandclaims.admin")) {
                val nearbyArea = getNearbyArea(actualMinX, actualMinZ, actualMaxX, actualMaxZ, MIN_DISTANCE_BETWEEN_CLAIMS)
                val nearbyClaims = databaseManager.getClaimsInArea(
                    world, 
                    nearbyArea.minX, 
                    nearbyArea.minZ, 
                    nearbyArea.maxX, 
                    nearbyArea.maxZ
                )
                
                val tooCloseClaims = nearbyClaims.filter { it.ownerId != player.uniqueId }
                if (tooCloseClaims.isNotEmpty()) {
                    return@withContext ValidationResult(
                        false, 
                        "Claims must be at least $MIN_DISTANCE_BETWEEN_CLAIMS blocks apart from other players' claims"
                    )
                }
            }
            
            // Check world-specific restrictions
            val worldValidation = validateWorldRestrictions(player, world, actualMinX, actualMinZ, actualMaxX, actualMaxZ)
            if (!worldValidation.isValid) {
                return@withContext worldValidation
            }
            
            // Create the claim data if all validations pass
            val claimData = ClaimData(
                ownerId = player.uniqueId,
                ownerName = player.name,
                world = world,
                minX = actualMinX,
                minZ = actualMinZ,
                maxX = actualMaxX,
                maxZ = actualMaxZ
            )
            
            ValidationResult(true, null, claimData)
            
        } catch (e: Exception) {
            logger.severe("Error during claim validation: ${e.message}")
            ValidationResult(false, "Internal validation error")
        }
    }
    
    /**
     * Validate claim update request
     */
    suspend fun validateClaimUpdate(
        player: Player,
        existingClaim: ClaimData,
        newName: String?,
        newDescription: String?,
        newIsPublic: Boolean?
    ): ValidationResult = withContext(Dispatchers.Default) {
        
        try {
            // Check ownership or admin permission
            if (existingClaim.ownerId != player.uniqueId && !player.hasPermission("openpartiesandclaims.admin")) {
                return@withContext ValidationResult(false, "You can only modify your own claims")
            }
            
            // Validate claim name if provided
            newName?.let { name ->
                if (name.length > 32) {
                    return@withContext ValidationResult(false, "Claim name cannot be longer than 32 characters")
                }
                if (name.contains(Regex("[^a-zA-Z0-9 _-]"))) {
                    return@withContext ValidationResult(false, "Claim name contains invalid characters")
                }
            }
            
            // Validate description if provided
            newDescription?.let { desc ->
                if (desc.length > 256) {
                    return@withContext ValidationResult(false, "Claim description cannot be longer than 256 characters")
                }
            }
            
            // Check permission for making claims public
            newIsPublic?.let { isPublic ->
                if (isPublic && !player.hasPermission("openpartiesandclaims.public")) {
                    return@withContext ValidationResult(false, "You don't have permission to make claims public")
                }
            }
            
            ValidationResult(true)
            
        } catch (e: Exception) {
            logger.severe("Error during claim update validation: ${e.message}")
            ValidationResult(false, "Internal validation error")
        }
    }
    
    /**
     * Validate claim deletion request
     */
    suspend fun validateClaimDeletion(
        player: Player,
        existingClaim: ClaimData
    ): ValidationResult = withContext(Dispatchers.Default) {
        
        try {
            // Check ownership or admin permission
            if (existingClaim.ownerId != player.uniqueId && !player.hasPermission("openpartiesandclaims.admin")) {
                return@withContext ValidationResult(false, "You can only delete your own claims")
            }
            
            ValidationResult(true)
            
        } catch (e: Exception) {
            logger.severe("Error during claim deletion validation: ${e.message}")
            ValidationResult(false, "Internal validation error")
        }
    }
    
    /**
     * Validate permission update request
     */
    suspend fun validatePermissionUpdate(
        player: Player,
        existingClaim: ClaimData,
        targetPlayerId: java.util.UUID,
        isAdmin: Boolean
    ): ValidationResult = withContext(Dispatchers.Default) {
        
        try {
            // Check ownership or admin permission
            if (existingClaim.ownerId != player.uniqueId && !player.hasPermission("openpartiesandclaims.admin")) {
                return@withContext ValidationResult(false, "You can only modify permissions for your own claims")
            }
            
            // Check if trying to grant admin permissions
            if (isAdmin && !player.hasPermission("openpartiesandclaims.grant.admin")) {
                return@withContext ValidationResult(false, "You don't have permission to grant admin access")
            }
            
            // Prevent self-permission modification (except for admins)
            if (targetPlayerId == player.uniqueId && !player.hasPermission("openpartiesandclaims.admin")) {
                return@withContext ValidationResult(false, "You cannot modify your own permissions")
            }
            
            ValidationResult(true)
            
        } catch (e: Exception) {
            logger.severe("Error during permission validation: ${e.message}")
            ValidationResult(false, "Internal validation error")
        }
    }
    
    /**
     * Validate world-specific restrictions
     */
    private fun validateWorldRestrictions(
        player: Player,
        world: String,
        minX: Int,
        minZ: Int,
        maxX: Int,
        maxZ: Int
    ): ValidationResult {
        
        // Check if claiming is disabled in this world
        val config = plugin.config
        val disabledWorlds = config.getStringList("disabled-worlds")
        if (disabledWorlds.contains(world)) {
            return ValidationResult(false, "Claiming is disabled in this world")
        }
        
        // Check world borders
        val worldBorderSize = config.getInt("world-border-size", 10000)
        if (abs(minX) > worldBorderSize || abs(maxX) > worldBorderSize || 
            abs(minZ) > worldBorderSize || abs(maxZ) > worldBorderSize) {
            return ValidationResult(false, "Claim extends beyond world border")
        }
        
        // Check spawn protection
        val spawnProtectionRadius = config.getInt("spawn-protection-radius", 100)
        if (spawnProtectionRadius > 0) {
            val spawnX = config.getInt("worlds.$world.spawn.x", 0)
            val spawnZ = config.getInt("worlds.$world.spawn.z", 0)
            
            if (isAreaNearSpawn(minX, minZ, maxX, maxZ, spawnX, spawnZ, spawnProtectionRadius)) {
                if (!player.hasPermission("openpartiesandclaims.spawn.bypass")) {
                    return ValidationResult(false, "Cannot claim near spawn area")
                }
            }
        }
        
        return ValidationResult(true)
    }
    
    /**
     * Get area expanded by specified distance
     */
    private fun getNearbyArea(minX: Int, minZ: Int, maxX: Int, maxZ: Int, distance: Int): ClaimArea {
        return ClaimArea(
            minX = minX - distance,
            minZ = minZ - distance,
            maxX = maxX + distance,
            maxZ = maxZ + distance
        )
    }
    
    /**
     * Check if area is near spawn
     */
    private fun isAreaNearSpawn(
        minX: Int, minZ: Int, maxX: Int, maxZ: Int,
        spawnX: Int, spawnZ: Int, radius: Int
    ): Boolean {
        // Check if any corner of the claim is within spawn protection radius
        val corners = listOf(
            Pair(minX, minZ),
            Pair(maxX, minZ),
            Pair(minX, maxZ),
            Pair(maxX, maxZ)
        )
        
        return corners.any { (x, z) ->
            val distance = kotlin.math.sqrt(((x - spawnX) * (x - spawnX) + (z - spawnZ) * (z - spawnZ)).toDouble())
            distance <= radius
        }
    }
    
    /**
     * Simple data class for claim area
     */
    private data class ClaimArea(
        val minX: Int,
        val minZ: Int,
        val maxX: Int,
        val maxZ: Int
    )
}
