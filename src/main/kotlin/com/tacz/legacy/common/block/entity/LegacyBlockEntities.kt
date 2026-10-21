package com.tacz.legacy.common.block.entity

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.registry.GameRegistry

internal object LegacyBlockEntities {
    private var registered: Boolean = false

    internal fun registerAll(): Unit {
        if (registered) {
            return
        }
        GameRegistry.registerTileEntity(GunSmithTableTileEntity::class.java, ResourceLocation(TACZLegacy.MOD_ID, "gun_smith_table"))
        GameRegistry.registerTileEntity(TargetTileEntity::class.java, ResourceLocation(TACZLegacy.MOD_ID, "target"))
        GameRegistry.registerTileEntity(StatueTileEntity::class.java, ResourceLocation(TACZLegacy.MOD_ID, "statue"))
        registered = true
    }
}

internal class GunSmithTableTileEntity : TileEntity() {
    internal var blockId: ResourceLocation = DefaultAssets.DEFAULT_BLOCK_ID

    /**
     * 多方块结构的版本。0（或字段缺失）表示旧存档的单方块工作台：
     * 不检查附属部位、不因结构残缺而拆除。新放置完成后由方块设为 1。
     */
    internal var structureVersion: Int = 0

    /**
     * 实际生效的工作台 ID。
     * 未在枪包中定义的 ID（例如没有任何枪包使用的 tacz:workbench_b，或已卸载枪包的工作台）
     * 会回退到可用的默认工作台，避免方块不可见、GUI 为空。
     */
    internal fun resolvedBlockId(): ResourceLocation = resolveWorkbenchBlockId(blockId)

    internal companion object {
        internal fun resolveWorkbenchBlockId(id: ResourceLocation): ResourceLocation {
            val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
            if (snapshot.blocks.containsKey(id)) {
                return id
            }
            return TACZGunPackPresentation.sortedBlocksForItem(snapshot, id).firstOrNull()?.id
                ?: DefaultAssets.DEFAULT_BLOCK_ID
        }
    }

    override fun readFromNBT(compound: NBTTagCompound): Unit {
        super.readFromNBT(compound)
        if (compound.hasKey("BlockId")) {
            blockId = ResourceLocation(compound.getString("BlockId"))
        }
        if (compound.hasKey("StructureVersion")) {
            structureVersion = compound.getInteger("StructureVersion")
        }
    }

    override fun writeToNBT(compound: NBTTagCompound): NBTTagCompound {
        val tag = super.writeToNBT(compound)
        tag.setString("BlockId", blockId.toString())
        tag.setInteger("StructureVersion", structureVersion)
        return tag
    }

    /** 模型最大占 2x2 格且可向任一水平方向延伸；默认的 1 格渲染范围会让模型在主方块出屏时被提前剔除 */
    override fun getRenderBoundingBox(): net.minecraft.util.math.AxisAlignedBB =
        net.minecraft.util.math.AxisAlignedBB(pos.add(-1, 0, -1), pos.add(2, 2, 2))

    override fun getUpdateTag(): NBTTagCompound = writeToNBT(NBTTagCompound())

    override fun getUpdatePacket(): net.minecraft.network.play.server.SPacketUpdateTileEntity =
        net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, updateTag)

    override fun onDataPacket(net: net.minecraft.network.NetworkManager, pkt: net.minecraft.network.play.server.SPacketUpdateTileEntity) {
        readFromNBT(pkt.nbtCompound)
    }
}

internal class TargetTileEntity : TileEntity() {
    internal var triggered: Boolean = false

    internal fun trigger(): Unit {
        triggered = true
        markDirty()
    }

    internal fun reset(): Unit {
        triggered = false
        markDirty()
    }

    override fun readFromNBT(compound: NBTTagCompound): Unit {
        super.readFromNBT(compound)
        triggered = compound.getBoolean("Triggered")
    }

    override fun writeToNBT(compound: NBTTagCompound): NBTTagCompound {
        val tag = super.writeToNBT(compound)
        tag.setBoolean("Triggered", triggered)
        return tag
    }
}

internal class StatueTileEntity : TileEntity() {
    internal var storedItem: ItemStack = ItemStack.EMPTY

    internal fun store(stack: ItemStack): Unit {
        storedItem = stack.copy()
        markDirty()
    }

    internal fun clear(): Unit {
        storedItem = ItemStack.EMPTY
        markDirty()
    }

    override fun readFromNBT(compound: NBTTagCompound): Unit {
        super.readFromNBT(compound)
        storedItem = if (compound.hasKey("StoredItem")) {
            ItemStack(compound.getCompoundTag("StoredItem"))
        } else {
            ItemStack.EMPTY
        }
    }

    override fun writeToNBT(compound: NBTTagCompound): NBTTagCompound {
        val tag = super.writeToNBT(compound)
        if (!storedItem.isEmpty) {
            tag.setTag("StoredItem", storedItem.writeToNBT(NBTTagCompound()))
        }
        return tag
    }
}
