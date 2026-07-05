package com.circulation.more_flux_storage.blockentity;

import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyCore;
import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyPylon;
import com.circulation.more_flux_storage.api.IFluxProxyHost;
import com.circulation.more_flux_storage.registry.MoreFluxStorageContent;
import com.circulation.more_flux_storage.util.AbstractFluxTransferHandler;
import com.circulation.more_flux_storage.util.ProxyFluxDevice;
import com.circulation.more_flux_storage.util.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import sonar.fluxnetworks.api.FluxConstants;
import sonar.fluxnetworks.common.device.TileFluxDevice;

import java.util.Objects;
import java.util.UUID;

@SuppressWarnings("ReturnOfInnerClass")
public class TileEnergyPylonFlux extends TileEnergyPylon implements IFluxProxyHost, ProxyFluxDevice.Host {

    private final EnergyPylonTransferHandler transferHandler = new EnergyPylonTransferHandler();
    private ProxyFluxDevice fluxProxyDevice;
    private CompoundTag pendingFluxTag;
    private byte pendingFluxTagType;
    private int fluxTick;

    public TileEnergyPylonFlux(BlockPos pos, BlockState state) {
        super(Utils.trigger(pos), state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TileEnergyPylonFlux tile) {
        tile.tick();
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && !level.isClientSide) {
            if (fluxTick++ % 20 == 0 && getCore() == null) {
                selectNextCore();
            }
            transferHandler.syncBufferFromCore();
            getOrCreateFluxProxyDevice().hostServerTick();
        }
    }

    @Override
    public void onLoad() {
        getOrCreateFluxProxyDevice().setLevel();
        // Process any pending flux tag data that was received before level was initialized
        if (pendingFluxTag != null) {
            getOrCreateFluxProxyDevice().readCustomTag(pendingFluxTag, pendingFluxTagType);
            pendingFluxTag = null;
        }
        super.onLoad();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        getOrCreateFluxProxyDevice().hostChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        getOrCreateFluxProxyDevice().hostRemoved();
    }

    @Override
    public void writeExtraNBT(CompoundTag tag) {
        super.writeExtraNBT(tag);
        syncTransferStateForTagWrite();
        writeTransferState(tag);
        getOrCreateFluxProxyDevice().writeCustomTag(tag, FluxConstants.NBT_SAVE_ALL);
    }

    @Override
    public void readExtraNBT(CompoundTag tag) {
        super.readExtraNBT(tag);
        getOrCreateFluxProxyDevice().setLevel();
        getOrCreateFluxProxyDevice().readCustomTag(tag, FluxConstants.NBT_SAVE_ALL);
    }

    @Override
    public @NotNull CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        syncTransferStateForTagWrite();
        writeTransferState(tag);
        getOrCreateFluxProxyDevice().writeCustomTag(tag, FluxConstants.NBT_TILE_UPDATE);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(@NotNull CompoundTag tag) {
        super.handleUpdateTag(tag);
        getOrCreateFluxProxyDevice().setLevel();
        getOrCreateFluxProxyDevice().readCustomTag(tag, FluxConstants.NBT_TILE_UPDATE);
    }

    @NotNull
    @Override
    public Component getDisplayName() {
        return Component.translatable(MoreFluxStorageContent.ENERGY_PYLON_FLUX_DESCRIPTION_ID);
    }

    @Override
    public TileFluxDevice getFluxProxyDevice() {
        return getOrCreateFluxProxyDevice();
    }

    @Override
    public int getFluxNetworkId() {
        return getOrCreateFluxProxyDevice().getNetworkID();
    }

    @Override
    public void setFluxOwner(UUID uuid) {
        getOrCreateFluxProxyDevice().setOwnerUUID(uuid);
        syncFluxData();
    }

    @Override
    public boolean canOpenFluxGui(Player player) {
        return getOrCreateFluxProxyDevice().canOpenGui(player);
    }

    @Override
    public void writeFluxTag(CompoundTag tag, byte type) {
        syncTransferStateForTagWrite();
        writeTransferState(tag);
        getOrCreateFluxProxyDevice().writeCustomTag(tag, type);
    }

    @Override
    public void readFluxTag(CompoundTag tag, byte type) {
        ProxyFluxDevice device = getOrCreateFluxProxyDevice();
        device.setLevel();
        if (device.getLevel() == null) {
            pendingFluxTag = tag.copy();
            pendingFluxTagType = type;
        } else {
            device.readCustomTag(tag, type);
        }
    }

    @NotNull
    @Override
    public BlockEntity getTE() {
        return this;
    }

    @NotNull
    @Override
    public AbstractFluxTransferHandler getProxyTransferHandler() {
        if (level != null && !level.isClientSide) {
            transferHandler.syncBufferFromCore();
        }
        return transferHandler;
    }

    @NotNull
    @Override
    public Component getProxyDisplayName() {
        return getDisplayName();
    }

    @NotNull
    @Override
    public ItemStack getProxyDisplayStack() {
        return MoreFluxStorageContent.getEnergyPylonFluxStack();
    }

    private void syncFluxData() {
        dirtyBlock();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 11);
        }
        updateBlock();
    }

    private void syncTransferStateForTagWrite() {
        if (level != null && !level.isClientSide) {
            transferHandler.syncBufferFromCore();
        }
    }

    private void writeTransferState(CompoundTag tag) {
        long buffer = transferHandler.getBuffer();
        if (level != null && !level.isClientSide) {
            TileEnergyCore core = getCore();
            buffer = core == null ? 0L : core.energy.getOPStored();
        }
        tag.putLong("buffer", buffer);
    }

    private ProxyFluxDevice getOrCreateFluxProxyDevice() {
        if (fluxProxyDevice == null) {
            fluxProxyDevice = new ProxyFluxDevice(this, getType(), worldPosition, getBlockState());
        }
        return fluxProxyDevice;
    }

    private void onFluxGuiDataChanged() {
        syncFluxData();
    }

    private final class EnergyPylonTransferHandler extends AbstractFluxTransferHandler {

        private void syncBufferFromCore() {
            if (level != null && !level.isClientSide) {
                Objects.requireNonNull(level.getServer()).addTickable(() -> {
                    TileEnergyCore core = getCore();
                    setBuffer(core == null ? 0L : core.energy.getOPStored());
                });
            }
        }

        @Override
        public long getRequest() {
            TileEnergyCore core = getCore();
            if (core == null) {
                return 0L;
            }

            long freeSpace = Math.max(0L, core.energy.getMaxOPStored() - core.energy.getUncappedStored());
            return Math.min(freeSpace, getRemainingAddLimit());
        }

        @Override
        public void addToBuffer(long amount) {
            TileEnergyCore core = getCore();
            long allowed = Math.min(Math.max(0L, amount), getRemainingAddLimit());
            if (core == null || allowed <= 0L) {
                return;
            }

            long received = core.energy.receiveOP(allowed, false);
            super.addToBuffer(received);
            syncBufferFromCore();
            if (received > 0L) {
                onFluxGuiDataChanged();
            }
        }

        @Override
        public long removeFromBuffer(long amount) {
            TileEnergyCore core = getCore();
            long allowed = Math.min(Math.max(0L, amount), getRemainingRemoveLimit());
            if (core == null || allowed <= 0L) {
                return 0L;
            }

            long extracted = core.energy.extractOP(allowed, false);
            long removed = super.removeFromBuffer(extracted);
            syncBufferFromCore();
            if (removed > 0L) {
                onFluxGuiDataChanged();
            }
            return removed;
        }

        @Override
        public void onCycleStart() {
            syncBufferFromCore();
        }
    }
}