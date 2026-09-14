package com.example.data.entitlement

import android.util.Log
import com.example.data.db.UserPreferenceDao
import com.example.data.db.UserPreferenceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Repository responsible for orchestrating Aura Pro entitlement state.
 * 
 * Responsibilities:
 * 1. Maintain and expose the current [ProState].
 * 2. Persist local entitlement state to encrypted storage (SQLCipher).
 * 3. Enforce feature access decisions.
 * 4. Emit access-denied events for paywall triggering.
 * 
 * Note: This repository is Billing-agnostic. Google Play Billing integration
 * will be handled via a separate manager that updates this repository.
 */
class EntitlementRepository(
    private val preferenceDao: UserPreferenceDao,
    private val scope: CoroutineScope
) {
    private val _proState = MutableStateFlow<ProState>(ProState.Unknown)
    val proState: StateFlow<ProState> = _proState.asStateFlow()

    private val _accessDeniedEvents = MutableSharedFlow<ProFeature>()
    val accessDeniedEvents: SharedFlow<ProFeature> = _accessDeniedEvents.asSharedFlow()

    private val PREF_KEY_STATUS = "aura_pro_status_v1"
    private val TAG = "EntitlementRepository"

    init {
        loadLocalEntitlement()
    }

    /**
     * Loads the last successfully verified entitlement from encrypted storage.
     * Ensures Pro functionality is available offline for verified users.
     */
    private fun loadLocalEntitlement() {
        scope.launch {
            try {
                val pref = preferenceDao.getPreference(PREF_KEY_STATUS)
                _proState.value = when (pref?.value) {
                    "PRO" -> ProState.Pro()
                    "FREE" -> ProState.Free
                    else -> ProState.Free // Default to Free if never set
                }
                Log.d(TAG, "Local entitlement loaded: ${_proState.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load local entitlement", e)
                _proState.value = ProState.Free // Safety fallback
            }
        }
    }

    /**
     * Updates and persists the entitlement state.
     * To be called by BillingManager or manual restore actions.
     */
    suspend fun updateEntitlement(isPro: Boolean, purchaseToken: String? = null) {
        val newState = if (isPro) ProState.Pro(purchaseToken) else ProState.Free
        _proState.value = newState
        
        try {
            preferenceDao.insertPreference(
                UserPreferenceEntity(PREF_KEY_STATUS, if (isPro) "PRO" else "FREE")
            )
            Log.i(TAG, "Entitlement updated and persisted: $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist entitlement update", e)
        }
    }

    /**
     * Centralized access decision for Pro features.
     * If access is denied, a [ProFeature] event is emitted to trigger the paywall.
     */
    fun isFeatureAvailable(feature: ProFeature): Boolean {
        val current = _proState.value
        if (current.isPro) {
            return true
        } else {
            // Asynchronously notify that a Pro feature was requested and denied
            scope.launch {
                _accessDeniedEvents.emit(feature)
            }
            return false
        }
    }
}
