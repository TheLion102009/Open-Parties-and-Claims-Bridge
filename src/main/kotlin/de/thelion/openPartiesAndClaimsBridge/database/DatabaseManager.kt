package de.thelion.openPartiesAndClaimsBridge.database

import de.thelion.openPartiesAndClaimsBridge.data.ClaimData
import de.thelion.openPartiesAndClaimsBridge.data.ClaimPermission
import de.thelion.openPartiesAndClaimsBridge.data.PartyData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.*
import java.util.logging.Logger

/**
 * Manages SQLite database operations for claims and parties
 */
class DatabaseManager(private val dataFolder: File, private val logger: Logger) {
    
    private val gson = Gson()
    private val dbFile = File(dataFolder, "claims.db")
    private var connection: Connection? = null
    
    /**
     * Initialize the database connection and create tables
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs()
            }
            
            Class.forName("org.sqlite.JDBC")
            connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            
            createTables()
            logger.info("Database initialized successfully")
        } catch (e: Exception) {
            logger.severe("Failed to initialize database: ${e.message}")
            throw e
        }
    }
    
    /**
     * Create necessary database tables
     */
    private fun createTables() {
        val createClaimsTable = """
            CREATE TABLE IF NOT EXISTS claims (
                id TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL,
                owner_name TEXT NOT NULL,
                world TEXT NOT NULL,
                min_x INTEGER NOT NULL,
                min_z INTEGER NOT NULL,
                max_x INTEGER NOT NULL,
                max_z INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                claim_name TEXT,
                description TEXT,
                is_public INTEGER DEFAULT 0,
                permissions TEXT DEFAULT '{}'
            )
        """.trimIndent()
        
        val createPartiesTable = """
            CREATE TABLE IF NOT EXISTS parties (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                leader_id TEXT NOT NULL,
                leader_name TEXT NOT NULL,
                members TEXT DEFAULT '[]',
                created_at INTEGER NOT NULL,
                description TEXT,
                is_open INTEGER DEFAULT 0,
                max_members INTEGER DEFAULT 10
            )
        """.trimIndent()
        
        val createIndexes = listOf(
            "CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims(owner_id)",
            "CREATE INDEX IF NOT EXISTS idx_claims_world ON claims(world)",
            "CREATE INDEX IF NOT EXISTS idx_claims_coords ON claims(world, min_x, min_z, max_x, max_z)",
            "CREATE INDEX IF NOT EXISTS idx_parties_leader ON parties(leader_id)"
        )
        
        connection?.let { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(createClaimsTable)
                stmt.execute(createPartiesTable)
                createIndexes.forEach { index ->
                    stmt.execute(index)
                }
            }
        }
    }
    
    /**
     * Save a claim to the database
     */
    suspend fun saveClaim(claim: ClaimData): Boolean = withContext(Dispatchers.IO) {
        try {
            val sql = """
                INSERT OR REPLACE INTO claims 
                (id, owner_id, owner_name, world, min_x, min_z, max_x, max_z, 
                 created_at, updated_at, claim_name, description, is_public, permissions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, claim.id)
                stmt.setString(2, claim.ownerId.toString())
                stmt.setString(3, claim.ownerName)
                stmt.setString(4, claim.world)
                stmt.setInt(5, claim.minX)
                stmt.setInt(6, claim.minZ)
                stmt.setInt(7, claim.maxX)
                stmt.setInt(8, claim.maxZ)
                stmt.setLong(9, claim.createdAt)
                stmt.setLong(10, claim.updatedAt)
                stmt.setString(11, claim.claimName)
                stmt.setString(12, claim.description)
                stmt.setInt(13, if (claim.isPublic) 1 else 0)
                stmt.setString(14, gson.toJson(claim.permissions))
                
                stmt.executeUpdate() > 0
            } ?: false
        } catch (e: Exception) {
            logger.severe("Failed to save claim ${claim.id}: ${e.message}")
            false
        }
    }
    
    /**
     * Get a claim by ID
     */
    suspend fun getClaim(claimId: String): ClaimData? = withContext(Dispatchers.IO) {
        try {
            val sql = "SELECT * FROM claims WHERE id = ?"
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, claimId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        mapResultSetToClaim(rs)
                    } else null
                }
            }
        } catch (e: Exception) {
            logger.severe("Failed to get claim $claimId: ${e.message}")
            null
        }
    }
    
    /**
     * Get all claims for a player
     */
    suspend fun getClaimsByOwner(ownerId: UUID): List<ClaimData> = withContext(Dispatchers.IO) {
        try {
            val sql = "SELECT * FROM claims WHERE owner_id = ? ORDER BY created_at DESC"
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, ownerId.toString())
                stmt.executeQuery().use { rs ->
                    val claims = mutableListOf<ClaimData>()
                    while (rs.next()) {
                        mapResultSetToClaim(rs)?.let { claims.add(it) }
                    }
                    claims
                }
            } ?: emptyList()
        } catch (e: Exception) {
            logger.severe("Failed to get claims for owner $ownerId: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Get claims in a specific world and coordinate range
     */
    suspend fun getClaimsInArea(world: String, minX: Int, minZ: Int, maxX: Int, maxZ: Int): List<ClaimData> = withContext(Dispatchers.IO) {
        try {
            val sql = """
                SELECT * FROM claims 
                WHERE world = ? AND 
                      NOT (max_x < ? OR min_x > ? OR max_z < ? OR min_z > ?)
                ORDER BY created_at DESC
            """.trimIndent()
            
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, world)
                stmt.setInt(2, minX)
                stmt.setInt(3, maxX)
                stmt.setInt(4, minZ)
                stmt.setInt(5, maxZ)
                
                stmt.executeQuery().use { rs ->
                    val claims = mutableListOf<ClaimData>()
                    while (rs.next()) {
                        mapResultSetToClaim(rs)?.let { claims.add(it) }
                    }
                    claims
                }
            } ?: emptyList()
        } catch (e: Exception) {
            logger.severe("Failed to get claims in area: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Delete a claim
     */
    suspend fun deleteClaim(claimId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val sql = "DELETE FROM claims WHERE id = ?"
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, claimId)
                stmt.executeUpdate() > 0
            } ?: false
        } catch (e: Exception) {
            logger.severe("Failed to delete claim $claimId: ${e.message}")
            false
        }
    }
    
    /**
     * Save a party to the database
     */
    suspend fun saveParty(party: PartyData): Boolean = withContext(Dispatchers.IO) {
        try {
            val sql = """
                INSERT OR REPLACE INTO parties 
                (id, name, leader_id, leader_name, members, created_at, description, is_open, max_members)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, party.id)
                stmt.setString(2, party.name)
                stmt.setString(3, party.leaderId.toString())
                stmt.setString(4, party.leaderName)
                stmt.setString(5, gson.toJson(party.members.map { it.toString() }))
                stmt.setLong(6, party.createdAt)
                stmt.setString(7, party.description)
                stmt.setInt(8, if (party.isOpen) 1 else 0)
                stmt.setInt(9, party.maxMembers)
                
                stmt.executeUpdate() > 0
            } ?: false
        } catch (e: Exception) {
            logger.severe("Failed to save party ${party.id}: ${e.message}")
            false
        }
    }
    
    /**
     * Get all parties for a player (as leader or member)
     */
    suspend fun getPartiesForPlayer(playerId: UUID): List<PartyData> = withContext(Dispatchers.IO) {
        try {
            val sql = "SELECT * FROM parties WHERE leader_id = ? OR members LIKE ? ORDER BY created_at DESC"
            connection?.prepareStatement(sql)?.use { stmt ->
                stmt.setString(1, playerId.toString())
                stmt.setString(2, "%${playerId}%")
                stmt.executeQuery().use { rs ->
                    val parties = mutableListOf<PartyData>()
                    while (rs.next()) {
                        mapResultSetToParty(rs)?.let { parties.add(it) }
                    }
                    parties
                }
            } ?: emptyList()
        } catch (e: Exception) {
            logger.severe("Failed to get parties for player $playerId: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Map ResultSet to ClaimData
     */
    private fun mapResultSetToClaim(rs: ResultSet): ClaimData? {
        return try {
            val permissionsJson = rs.getString("permissions") ?: "{}"
            val permissionsType = object : TypeToken<Map<String, ClaimPermission>>() {}.type
            val permissions: Map<String, ClaimPermission> = gson.fromJson(permissionsJson, permissionsType) ?: emptyMap()
            
            ClaimData(
                id = rs.getString("id"),
                ownerId = UUID.fromString(rs.getString("owner_id")),
                ownerName = rs.getString("owner_name"),
                world = rs.getString("world"),
                minX = rs.getInt("min_x"),
                minZ = rs.getInt("min_z"),
                maxX = rs.getInt("max_x"),
                maxZ = rs.getInt("max_z"),
                createdAt = rs.getLong("created_at"),
                updatedAt = rs.getLong("updated_at"),
                claimName = rs.getString("claim_name"),
                description = rs.getString("description"),
                isPublic = rs.getInt("is_public") == 1,
                permissions = permissions
            )
        } catch (e: Exception) {
            logger.severe("Failed to map ResultSet to ClaimData: ${e.message}")
            null
        }
    }
    
    /**
     * Map ResultSet to PartyData
     */
    private fun mapResultSetToParty(rs: ResultSet): PartyData? {
        return try {
            val membersJson = rs.getString("members") ?: "[]"
            val membersType = object : TypeToken<List<String>>() {}.type
            val membersList: List<String> = gson.fromJson(membersJson, membersType) ?: emptyList()
            val members = membersList.mapNotNull { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }.toSet()
            
            PartyData(
                id = rs.getString("id"),
                name = rs.getString("name"),
                leaderId = UUID.fromString(rs.getString("leader_id")),
                leaderName = rs.getString("leader_name"),
                members = members,
                createdAt = rs.getLong("created_at"),
                description = rs.getString("description"),
                isOpen = rs.getInt("is_open") == 1,
                maxMembers = rs.getInt("max_members")
            )
        } catch (e: Exception) {
            logger.severe("Failed to map ResultSet to PartyData: ${e.message}")
            null
        }
    }
    
    /**
     * Close the database connection
     */
    fun close() {
        try {
            connection?.close()
            logger.info("Database connection closed")
        } catch (e: Exception) {
            logger.severe("Failed to close database connection: ${e.message}")
        }
    }
}
