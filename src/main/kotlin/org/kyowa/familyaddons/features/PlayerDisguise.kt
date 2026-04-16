package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.config.FamilyConfigManager

object PlayerDisguise {
    fun isEnabled(): Boolean = FamilyConfigManager.config.playerDisguise.enabled
    fun getMobId(): String = FamilyConfigManager.config.playerDisguise.mobId.trim().lowercase()
    fun getScope(): Int = FamilyConfigManager.config.playerDisguise.scope
    fun isBaby(): Boolean = FamilyConfigManager.config.playerDisguise.baby
    fun isSheared(): Boolean = FamilyConfigManager.config.playerDisguise.sheared
    fun showFriendsDisguises(): Boolean = FamilyConfigManager.config.playerDisguise.showFriendsDisguises
}