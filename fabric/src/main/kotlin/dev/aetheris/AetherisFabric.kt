package dev.aetheris

import dev.aetheris.content.AetherisContent
import dev.aetheris.content.FabricRegistrar
import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Fabric entrypoint, declared in `fabric.mod.json`. */
class AetherisFabric : ModInitializer {
    override fun onInitialize() {
        AetherisContent.define(FabricRegistrar())

        val engine = AetherisCommon.chemistry
        LOGGER.info(
            "{} ready: {} species, {} reactions",
            Aetheris.MOD_NAME,
            engine.registry.all.size,
            engine.reactions.size,
        )
    }

    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(Aetheris.MOD_ID)
    }
}
