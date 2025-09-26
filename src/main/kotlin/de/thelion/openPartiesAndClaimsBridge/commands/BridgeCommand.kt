package de.thelion.openPartiesAndClaimsBridge.commands

import de.thelion.openPartiesAndClaimsBridge.OpenPartiesAndClaimsBridge
import de.thelion.openPartiesAndClaimsBridge.database.DatabaseManager
import de.thelion.openPartiesAndClaimsBridge.sync.ClaimSynchronizer
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.logging.Logger

/**
 * Command handler for the Open Parties and Claims Bridge plugin
 */
class BridgeCommand(
    private val plugin: OpenPartiesAndClaimsBridge,
    private val databaseManager: DatabaseManager,
    private val claimSynchronizer: ClaimSynchronizer,
    private val logger: Logger
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("openpartiesandclaims.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command!")
            return true
        }

        if (args.isEmpty()) {
            sendHelpMessage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> handleReload(sender)
            "status" -> handleStatus(sender)
            "sync" -> handleSync(sender, args)
            "stats" -> handleStats(sender)
            "help" -> sendHelpMessage(sender)
            else -> {
                sender.sendMessage("§cUnknown subcommand: ${args[0]}")
                sendHelpMessage(sender)
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("openpartiesandclaims.admin")) {
            return emptyList()
        }

        return when (args.size) {
            1 -> listOf("reload", "status", "sync", "stats", "help").filter { 
                it.startsWith(args[0].lowercase()) 
            }
            2 -> when (args[0].lowercase()) {
                "sync" -> {
                    if (sender.hasPermission("openpartiesandclaims.admin")) {
                        Bukkit.getOnlinePlayers().map { it.name }.filter { 
                            it.lowercase().startsWith(args[1].lowercase()) 
                        }
                    } else emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Handle reload command
     */
    private fun handleReload(sender: CommandSender) {
        try {
            sender.sendMessage("§eReloading Open Parties and Claims Bridge...")
            
            runBlocking {
                plugin.reloadPlugin()
            }
            
            sender.sendMessage("§aPlugin reloaded successfully!")
            logger.info("Plugin reloaded by ${sender.name}")
            
        } catch (e: Exception) {
            sender.sendMessage("§cFailed to reload plugin: ${e.message}")
            logger.severe("Failed to reload plugin: ${e.message}")
        }
    }

    /**
     * Handle status command
     */
    private fun handleStatus(sender: CommandSender) {
        try {
            val stats = plugin.getPluginStatistics()
            
            sender.sendMessage("§6=== Open Parties and Claims Bridge Status ===")
            sender.sendMessage("§ePlugin Version: §f${plugin.description.version}")
            sender.sendMessage("§eInitialized: §f${if (stats.isInitialized) "§aYes" else "§cNo"}")
            sender.sendMessage("§eDatabase Connected: §f${if (stats.databaseConnected) "§aYes" else "§cNo"}")
            sender.sendMessage("§eOnline Players: §f${stats.onlinePlayers}")
            
            stats.syncStats?.let { syncStats ->
                sender.sendMessage("§eActive Sync Jobs: §f${syncStats.activeSyncJobs}")
                sender.sendMessage("§eTracked Players: §f${syncStats.trackedPlayers}")
            }
            
        } catch (e: Exception) {
            sender.sendMessage("§cFailed to get status: ${e.message}")
            logger.severe("Failed to get plugin status: ${e.message}")
        }
    }

    /**
     * Handle sync command
     */
    private fun handleSync(sender: CommandSender, args: Array<out String>) {
        try {
            if (args.size < 2) {
                // Sync all online players
                sender.sendMessage("§eSyncing all online players...")
                
                val onlinePlayers = Bukkit.getOnlinePlayers()
                var syncCount = 0
                
                runBlocking {
                    onlinePlayers.forEach { player ->
                        try {
                            claimSynchronizer.syncPlayerData(player, forceFullSync = true)
                            syncCount++
                        } catch (e: Exception) {
                            logger.warning("Failed to sync player ${player.name}: ${e.message}")
                        }
                    }
                }
                
                sender.sendMessage("§aSuccessfully synced $syncCount/${onlinePlayers.size} players")
                
            } else {
                // Sync specific player
                val targetName = args[1]
                val targetPlayer = Bukkit.getPlayer(targetName)
                
                if (targetPlayer == null) {
                    sender.sendMessage("§cPlayer '$targetName' not found or not online!")
                    return
                }
                
                sender.sendMessage("§eSyncing player ${targetPlayer.name}...")
                
                runBlocking {
                    claimSynchronizer.syncPlayerData(targetPlayer, forceFullSync = true)
                }
                
                sender.sendMessage("§aSuccessfully synced player ${targetPlayer.name}")
            }
            
        } catch (e: Exception) {
            sender.sendMessage("§cFailed to sync: ${e.message}")
            logger.severe("Failed to sync players: ${e.message}")
        }
    }

    /**
     * Handle stats command
     */
    private fun handleStats(sender: CommandSender) {
        try {
            runBlocking {
                val totalClaims = if (sender is Player) {
                    databaseManager.getClaimsByOwner(sender.uniqueId).size
                } else {
                    // For console, we'd need a method to get total claims count
                    0
                }
                
                val totalParties = if (sender is Player) {
                    databaseManager.getPartiesForPlayer(sender.uniqueId).size
                } else {
                    0
                }
                
                sender.sendMessage("§6=== Your Statistics ===")
                if (sender is Player) {
                    sender.sendMessage("§eYour Claims: §f$totalClaims")
                    sender.sendMessage("§eYour Parties: §f$totalParties")
                } else {
                    sender.sendMessage("§eStatistics are only available for players")
                }
            }
            
        } catch (e: Exception) {
            sender.sendMessage("§cFailed to get statistics: ${e.message}")
            logger.severe("Failed to get player statistics: ${e.message}")
        }
    }

    /**
     * Send help message
     */
    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage("§6=== Open Parties and Claims Bridge Commands ===")
        sender.sendMessage("§e/opcbridge reload §7- Reload the plugin configuration")
        sender.sendMessage("§e/opcbridge status §7- Show plugin status")
        sender.sendMessage("§e/opcbridge sync [player] §7- Force sync claims and parties")
        sender.sendMessage("§e/opcbridge stats §7- Show your statistics")
        sender.sendMessage("§e/opcbridge help §7- Show this help message")
    }
}
