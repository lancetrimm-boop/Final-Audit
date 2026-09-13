package com.example.data.entitlement

/**
 * Enumeration of Aura capabilities that require a Pro entitlement.
 * Used for centralized access control and paywall triggering.
 */
enum class ProFeature(val displayName: String) {
    ITERATIVE_VISUAL_SEARCH("Iterative Visual Search"),
    ADVANCED_HYBRID_SEARCH("Advanced Hybrid Search"),
    CLEANUP_AUTOMATION("Cleanup Automation"),
    MOMENTS_EXPORT("Aura Moments Export")
}
