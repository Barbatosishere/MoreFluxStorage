package com.circulation.more_flux_storage.blockentity;

import com.circulation.more_flux_storage.api.IFluxProxyHost;
import com.circulation.more_flux_storage.registry.MoreFluxStorageContent;
import com.circulation.more_flux_storage.util.AbstractFluxTransferHandler;
import com.circulation.more_flux_storage.util.ProxyFluxDevice;
import com.circulation.more_flux_storage.util.Utils;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.content.matrix.MatrixEnergyContainer;
import mekanism.common.content.matrix.MatrixMultiblockData;
import mekanism.common.tile.multiblock.TileEntityInductionPort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import sonar.fluxnetworks.api.FluxConstants;
import sonar.fluxnetworks.common.device.TileFluxDevice;

import java.util.UUID;

@SuppressWarnings("ReturnOfInnerClass")
public class TileInductionPortFlux extends TileEntityInductionPort implements IFluxProxyHost, ProxyFluxDevice.Host {

    private static final double MEKANISM_TO_FLUX = 0.4D;
    private static final double FLUX_TO_MEKANISM = 1D / MEKANISM_TO_FLUX;

    private final InductionPortTransferHandler transferHandler = new InductionPortTransferHandler();
    private ProxyFluxDevice fluxProxyDevice;
    private CompoundTag pendingFluxTag;
    private byte pendingFluxTagType;

    public TileInductionPortFlux(BlockPos pos, BlockState state) {
        super(Utils.trigger(pos), state);
    }

    private static long toFlux(long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        return Math.max(0L, (long) (amount * MEKANISM_TO_FLUX));
    }

    private static long toMekanism(long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        return Math.max(0L, (long) Math.ceil(amount * FLUX_TO_MEKANISM));
    }

    @Override
    protected boolean onUpdateServer(MatrixMultiblockData multiblock) {
        boolean needsSync = super.onUpdateServer(multiblock);
        transferHandler.syncBufferFromMatrix();
        getOrCreateFluxProxyDevice().hostServerTick();
        return needsSync;
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        syncTransferStateForTagWrite();
        writeTransferState(tag);
        getOrCreateFluxProxyDevice().writeCustomTag(tag, FluxConstants.NBT_SAVE_ALL);
    }

    @Override
    public void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        getOrCreateFluxProxyDevice().syncLevel();
        getOrCreateFluxProxyDevice().readCustomTag(tag, FluxConstants.NBT_SAVE_ALL);
    }

    @Override
    public void onLoad() {
        getOrCreateFluxProxyDevice().syncLevel();
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
    public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        syncTransferStateForTagWrite();
        writeTransferState(tag);
        getOrCreateFluxProxyDevice().writeCustomTag(tag, FluxConstants.NBT_TILE_UPDATE);
        return tag;
    }

    @Override
    public void handleUpdateTag(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        super.handleUpdateTag(tag, provider);
        getOrCreateFluxProxyDevice().syncLevel();
        getOrCreateFluxProxyDevice().readCustomTag(tag, FluxConstants.NBT_TILE_UPDATE);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.translatable(MoreFluxStorageContent.INDUCTION_PORT_FLUX_DESCRIPTION_ID);
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
        // FIX: Sync level on the proxy device before reading, as ProxyFluxDevice has its own
        // level field that must be explicitly synced from the host BlockEntity
        ProxyFluxDevice device = getOrCreateFluxProxyDevice();
        device.syncLevel();
        if (device.getLevel() == null) {
            pendingFluxTag = tag.copy();
            pendingFluxTagType = type;
        } else {
            device.readCustomTag(tag, type);
        }
    }

    public InteractionResult onSneakRightClick(Player player) {
        InteractionResult result = super.onSneakRightClick(player);
        if (result.consumesAction()) {
            transferHandler.syncBufferFromMatrix();
            syncFluxData();
        }
        return result;
    }

    public long getFluxEnergyStored() {
        MatrixEnergyContainer energyContainer = getMatrixEnergyContainer();
        return energyContainer == null ? 0L : toFlux(energyContainer.getEnergy());
    }

    @Override
    public @NotNull BlockEntity getTE() {
        return this;
    }

    @Override
    public @NotNull AbstractFluxTransferHandler getProxyTransferHandler() {
        if (level != null && !level.isClientSide) {
            transferHandler.syncBufferFromMatrix();
        }
        return transferHandler;
    }

    @Override
    public @NotNull Component getProxyDisplayName() {
        return getDisplayName();
    }

    @Override
    public @NotNull ItemStack getProxyDisplayStack() {
        return MoreFluxStorageContent.getInductionPortFluxStack();
    }

    private void syncFluxData() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 11);
        }
    }

    private void syncTransferStateForTagWrite() {
        if (level != null && !level.isClientSide) {
            transferHandler.syncBufferFromMatrix();
        }
    }

    private void writeTransferState(CompoundTag tag) {
        long buffer = level != null && !level.isClientSide ? getFluxEnergyStored() : transferHandler.getBuffer();
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

    private MatrixEnergyContainer getMatrixEnergyContainer() {
        MatrixMultiblockData multiblock = getMultiblock();
        return multiblock == null ? null : multiblock.getEnergyContainer();
    }

    private final class InductionPortTransferHandler extends AbstractFluxTransferHandler {

        private void syncBufferFromMatrix() {
            setBuffer(getFluxEnergyStored());
        }

        private long getEffectiveFluxTransferLimit(long transferredThisCycle, long remainingFluxLimit) {
            MatrixEnergyContainer energyContainer = getMatrixEnergyContainer();
            if (energyContainer == null) {
                return 0L;
            }
            long matrixLimit = Math.max(0L, toFlux(energyContainer.getMaxTransfer()) - transferredThisCycle);
            return Math.min(matrixLimit, remainingFluxLimit);
        }

        @Override
        public void onCycleStart() {
            syncBufferFromMatrix();
        }

        @Override
        public long getRequest() {
            if (getActive()) {
                return 0L;
            }

            MatrixEnergyContainer energyContainer = getMatrixEnergyContainer();
            if (energyContainer == null) {
                return 0L;
            }
            long freeSpace = toFlux(energyContainer.getMaxEnergy() - energyContainer.getEnergy());
            return Math.max(0L, Math.min(freeSpace, getEffectiveFluxTransferLimit(getAdded(), getRemainingAddLimit())));
        }

        @Override
        public void addToBuffer(long amount) {
            long allowed = Math.min(Math.max(0L, amount), getEffectiveFluxTransferLimit(getAdded(), getRemainingAddLimit()));
            if (allowed <= 0L) {
                return;
            }

            MatrixEnergyContainer energyContainer = getMatrixEnergyContainer();
            if (energyContainer == null) {
                return;
            }
            long requested = toMekanism(allowed);
            long remainder = energyContainer.insert(requested, Action.EXECUTE, AutomationType.INTERNAL);
            long accepted = toFlux(requested - remainder);
            super.addToBuffer(accepted);
            syncBufferFromMatrix();
            if (accepted > 0L) {
                onFluxGuiDataChanged();
            }
        }

        @Override
        public long removeFromBuffer(long amount) {
            long allowed = Math.min(Math.max(0L, amount), getEffectiveFluxTransferLimit(getRemoved(), getRemainingRemoveLimit()));
            if (allowed <= 0L) {
                return 0L;
            }

            MatrixEnergyContainer energyContainer = getMatrixEnergyContainer();
            if (energyContainer == null) {
                return 0L;
            }
            long extracted = energyContainer.extract(toMekanism(allowed), Action.EXECUTE, AutomationType.INTERNAL);
            long removed = super.removeFromBuffer(toFlux(extracted));
            syncBufferFromMatrix();
            if (removed > 0L) {
                onFluxGuiDataChanged();
            }
            return removed;
        }
    }
}
