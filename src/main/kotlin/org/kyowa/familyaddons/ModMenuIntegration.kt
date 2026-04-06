package org.kyowa.familyaddons

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import io.github.notenoughupdates.moulconfig.gui.GuiContext
import io.github.notenoughupdates.moulconfig.gui.GuiElementComponent
import io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent
import net.minecraft.text.Text
import org.kyowa.familyaddons.config.FamilyConfigManager

class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent ->
            MoulConfigScreenComponent(
                Text.empty(),
                GuiContext(GuiElementComponent(FamilyConfigManager.getEditor())),
                parent
            )
        }
    }
}
