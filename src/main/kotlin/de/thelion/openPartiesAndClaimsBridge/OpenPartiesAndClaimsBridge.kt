package de.thelion.openPartiesAndClaimsBridge

import de.thelion.openPartiesAndClaimsBridge.database.DatabaseManager
import de.thelion.openPartiesAndClaimsBridge.packets.PacketHandler
import de.thelion.openPartiesAndClaimsBridge.sync.ClaimSynchronizer
import de.thelion.openPartiesAndClaimsBridge.validation.ClaimValidator
import de.thelion.openPartiesAndClaimsBridge.commands.BridgeCommand
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Main plugin class for Open Parties and Claims Bridge
 * Acts as an API bridge between the Fabric mod and Paper server
 */
class OpenPartiesAndClaimsBridge : JavaPlugin(), Listener {

    // Core components
    private lateinit var databaseManager: DatabaseManager
    private lateinit var claimValidator: ClaimValidator
    private lateinit var packetHandler: PacketHandler
    private lateinit var claimSynchronizer: ClaimSynchronizer
    private lateinit var bridgeCommand: BridgeCommand

    // Plugin state
    private var isInitialized = false

    override fun onEnable() {
        try {
            logger.info("Starting Open Parties and Claims Bridge v${description.version}")
            
            // Create plugin data folder
            if (!dataFolder.exists()) {
                dataFolder.mkdirs()
            }
            
            // Load configuration
            saveDefaultConfig()
            loadConfiguration()
            
            // Initialize core components
            initializeComponents()
            
            // Register events and commands
            registerEventsAndCommands()
            
            // Start async initialization
            runBlocking {
                initializeAsync()
            }
            
            isInitialized = true
            logger.info("Open Parties and Claims Bridge successfully enabled!")
            
        } catch (e: Exception) {
            logger.severe("Failed to enable plugin: ${e.message}")
            e.printStackTrace()
            isEnabled = false
        }
    }

    override fun onDisable() {
        try {
            logger.info("Shutting down Open Parties and Claims Bridge...")
            
            if (::claimSynchronizer.isInitialized) {
                claimSynchronizer.shutdown()
            }
            
            if (::packetHandler.isInitialized) {
                packetHandler.shutdown()
            }
            
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            
            logger.info("Open Parties and Claims Bridge disabled successfully")
            
        } catch (e: Exception) {
            logger.severe("Error during plugin shutdown: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Load and validate configuration
     */
    private fun loadConfiguration() {
        // Set default configuration values
        config.addDefault("database.type", "sqlite")
        config.addDefault("database.file", "claims.db")
        config.addDefault("sync.interval-seconds", 30)
        config.addDefault("sync.batch-size", 50)
        config.addDefault("claims.min-size", 16)
        config.addDefault("claims.max-size", 1024)
        config.addDefault("claims.max-per-player", 10)
        config.addDefault("claims.min-distance", 8)
        config.addDefault("world-border-size", 10000)
        config.addDefault("spawn-protection-radius", 100)
        config.addDefault("disabled-worlds", listOf<String>())
        
        config.options().copyDefaults(true)
        saveConfig()
        
        logger.info("Configuration loaded successfully")
    }

    /**
     * Initialize core components
     */
    private fun initializeComponents() {
        logger.info("Initializing core components...")
        
        // Initialize database manager
        databaseManager = DatabaseManager(dataFolder, logger)
        
        // Initialize claim validator
        claimValidator = ClaimValidator(this, databaseManager, logger)
        
        // Initialize packet handler
        packetHandler = PacketHandler(this, databaseManager, claimValidator, logger)
        
        // Initialize claim synchronizer
        claimSynchronizer = ClaimSynchronizer(this, databaseManager, packetHandler, logger)
        
        // Initialize command handler
        bridgeCommand = BridgeCommand(this, databaseManager, claimSynchronizer, logger)
        
        logger.info("Core components initialized")
    }

    /**
     * Register events and commands
     */
    private fun registerEventsAndCommands() {
        // Register event listeners
        server.pluginManager.registerEvents(this, this)
        
        // Register commands
        getCommand("opcbridge")?.setExecutor(bridgeCommand)
        getCommand("opcbridge")?.tabCompleter = bridgeCommand
        
        logger.info("Events and commands registered")
    }

    /**
     * Initialize async components
     */
    private suspend fun initializeAsync() {
        logger.info("Starting async initialization...")
        
        // Initialize database
        databaseManager.initialize()
        
        // Initialize packet handler
        packetHandler.initialize()
        
        // Initialize synchronizer
        claimSynchronizer.initialize()
        
        logger.info("Async initialization completed")
    }

    /**
     * Handle player join events
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!isInitialized) return
        
        val player = event.player
        logger.fine("Player ${player.name} joined, scheduling sync")
        
        // Schedule sync after a short delay to allow client mod to initialize
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, Runnable {
            runBlocking {
                claimSynchronizer.onPlayerJoin(player)
            }
        }, 40L) // 2 seconds delay
    }

    /**
     * Handle player quit events
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        if (!isInitialized) return
        
        val player = event.player
        logger.fine("Player ${player.name} quit, cleaning up sync data")
        
        claimSynchronizer.onPlayerQuit(player)
    }

    /**
     * Handle plugin commands
     */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        return if (::bridgeCommand.isInitialized) {
            bridgeCommand.onCommand(sender, command, label, args)
        } else {
            sender.sendMessage("§cPlugin is not fully initialized yet!")
            true
        }
    }

    /**
     * Handle tab completion
     */
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        return if (::bridgeCommand.isInitialized) {
            bridgeCommand.onTabComplete(sender, command, alias, args)
        } else {
            emptyList()
        }
    }

    /**
     * Get the database manager instance
     */
    fun getDatabaseManager(): DatabaseManager {
        return databaseManager
    }

    /**
     * Get the packet handler instance
     */
    fun getPacketHandler(): PacketHandler {
        return packetHandler
    }

    /**
     * Get the claim validator instance
     */
    fun getClaimValidator(): ClaimValidator {
        return claimValidator
    }

    /**
     * Get the claim synchronizer instance
     */
    fun getClaimSynchronizer(): ClaimSynchronizer {
        return claimSynchronizer
    }

    /**
     * Check if the plugin is fully initialized
     */
    fun isFullyInitialized(): Boolean {
        return isInitialized
    }

    /**
     * Reload the plugin configuration and components
     */
    suspend fun reloadPlugin() {
        try {
            logger.info("Reloading plugin configuration...")
            
            // Reload configuration
            reloadConfig()
            loadConfiguration()
            
            // Reinitialize components that need config updates
            claimValidator = ClaimValidator(this, databaseManager, logger)
            
            logger.info("Plugin reloaded successfully")
            
        } catch (e: Exception) {
            logger.severe("Error reloading plugin: ${e.message}")
            throw e
        }
    }

    /**
     * Get plugin statistics for monitoring
     */
    fun getPluginStatistics(): PluginStatistics {
        return PluginStatistics(
            isInitialized = isInitialized,
            onlinePlayers = Bukkit.getOnlinePlayers().size,
            syncStats = if (::claimSynchronizer.isInitialized) {
                claimSynchronizer.getSyncStatistics()
            } else null,
            databaseConnected = ::databaseManager.isInitialized
        )
    }

    /**
     * Statistics data class
     */
    data class PluginStatistics(
        val isInitialized: Boolean,
        val onlinePlayers: Int,
        val syncStats: ClaimSynchronizer.SyncStatistics?,
        val databaseConnected: Boolean
    )
}
