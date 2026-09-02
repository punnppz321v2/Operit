package com.ai.assistance.operit.core.integration

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.nonoassistance.memory.BudgetConfig
import com.ai.nonoassistance.memory.BudgetStats
import com.ai.nonoassistance.memory.ContextBudgetManager
import com.ai.nonoassistance.memory.GlobalMemoryStore
import com.ai.nonoassistance.orchestration.OrchestrationEngine
import com.ai.nonoassistance.orchestration.PermissionLevel
import com.ai.nonoassistance.orchestration.RoleAssignmentConfig
import com.ai.nonoassistance.orchestration.RoleConfig
import com.ai.nonoassistance.orchestration.Responsibility
import com.ai.nonoassistance.provider.OperitProviderAdapter
import com.ai.nonoassistance.provider.ProviderRegistry
import com.ai.nonoassistance.provider.ModelPricingService
import java.io.File

/**
 * Central integration manager for OperitX new modules.
 *
 * Initializes and wires:
 * - ProviderRegistry + OperitProviderAdapter (bridges to existing AIService)
 * - OrchestrationEngine (multi-AI leader/worker)
 * - GlobalMemoryStore + ContextBudgetManager
 * - ToolCallSanitizer (hooked into ToolExecutionManager)
 * - RootExecutionGuard (permission system)
 *
 * Must be initialized after OperitApplication.onCreate().
 */
object NonOIntegrationManager {
    private const val TAG = "NonOIntegrationManager"

    @Volatile
    private var initialized = false

    // Core modules
    lateinit var providerRegistry: ProviderRegistry
        private set
    lateinit var pricingService: ModelPricingService
        private set
    lateinit var orchestrationEngine: OrchestrationEngine
        private set
    lateinit var memoryStore: GlobalMemoryStore
        private set
    lateinit var contextBudgetManager: ContextBudgetManager
        private set

    /**
     * Initialize all OperitX modules. Call once from OperitApplication.onCreate().
     */
    fun initialize(context: Context) {
        if (initialized) {
            AppLogger.w(TAG, "NonOIntegrationManager already initialized")
            return
        }

        AppLogger.i(TAG, "Initializing OperitX integration modules...")

        try {
            // 1. Provider system
            providerRegistry = ProviderRegistry()
            pricingService = ModelPricingService()

            // 2. Orchestration engine
            val defaultRoleConfig = RoleAssignmentConfig(
                leader = RoleConfig(
                    model = "deepseek-chat",
                    responsibilities = listOf(
                        Responsibility.DECOMPOSE_TASK,
                        Responsibility.REVIEW_OUTPUT,
                        Responsibility.DISPATCH_FIX
                    ),
                    permission = PermissionLevel.FULL
                ),
                workers = listOf(
                    RoleConfig(
                        model = "gemini-2.0-flash",
                        responsibilities = listOf(
                            Responsibility.EXECUTE_SUBTASK,
                            Responsibility.REPORT_RESULT
                        ),
                        permission = PermissionLevel.RESTRICTED
                    )
                ),
                maxWorkersPerSession = 3
            )
            orchestrationEngine = OrchestrationEngine(defaultRoleConfig)

            // 3. Memory & context
            val memoryDir = File(context.filesDir, "nono_memory")
            memoryDir.mkdirs()
            memoryStore = GlobalMemoryStore(memoryDir)

            contextBudgetManager = ContextBudgetManager(
                config = BudgetConfig(
                    maxTokens = 256_000,
                    autoSummarizeThreshold = 0.8
                )
            )

            initialized = true
            AppLogger.i(TAG, "NonOIntegrationManager initialized successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize NonOIntegrationManager", e)
            throw e
        }
    }

    /**
     * Get the provider adapter for use with existing chat services.
     */
    fun getProviderAdapter(): OperitProviderAdapter {
        check(initialized) { "NonOIntegrationManager not initialized" }
        return OperitProviderAdapter(
            id = "operit",
            displayName = "Operit",
            underlyingServiceId = "OPERIT"
        )
    }

    /**
     * Get budget stats for display in UI.
     */
    fun getBudgetStats(): BudgetStats {
        check(initialized) { "NonOIntegrationManager not initialized" }
        return contextBudgetManager.getStats()
    }

    /**
     * Check if integration is ready.
     */
    fun isInitialized(): Boolean = initialized
}
