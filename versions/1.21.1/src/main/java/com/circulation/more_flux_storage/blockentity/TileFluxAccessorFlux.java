package com.circulation.more_flux_storage.blockentity;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import com.circulation.more_flux_storage.api.IFluxProxyHost;
import com.circulation.more_flux_storage.registry.MoreFluxStorageContent;
import com.circulation.more_flux_storage.util.AbstractFluxTransferHandler;
import com.circulation.more_flux_storage.util.ProxyFluxDevice;
import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sonar.fluxnetworks.api.FluxConstants;
import sonar.fluxnetworks.common.device.TileFluxDevice;

import java.util.Objects;
import java.util.UUID;

public class TileFluxAccessorFlux extends AENetworkedBlockEntity implements IFluxProxyHost, ProxyFluxDevice.Host {

    private final FluxAccessorTransferHandler transferHandler = new FluxAccessorTransferHandler();
    private ProxyFluxDevice fluxProxyDevice;
    private CompoundTag pendingFluxTag;
    private byte pendingFluxTagType;

    public TileFluxAccessorFlux(BlockPos pos, BlockState state) {
        super(Objects.requireNonNull(MoreFluxStorageContent.getFluxAccessorFluxBlockEntityType(), "Flux accessor block entity type is not registered"), pos, state);
        getMainNode().setFlags(GridFlags.REQUIRE_CHANNEL);
        getMainNode().setIdlePowerUsage(1.0D);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TileFluxAccessorFlux tile) {
        tile.tick();
    }

    public void tick() {
        if (level != null && !level.isClientSide) {
            transferHandler.syncBufferFromStorage();
            getOrCreateFluxProxyDevice().hostServerTick();
        }
    }

    @Override
    public void onReady() {
        getOrCreateFluxProxyDevice().syncLevel();
        // Process any pending flux tag data that was received before level was initialized
        if (pendingFluxTag != null) {
            getOrCreateFluxProxyDevice().readCustomTag(pendingFluxTag, pendingFluxTagType);
            pendingFluxTag = null;
        }
        super.onReady();
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
    public void saveAdditional(@NotNull CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        syncTransferStateForTagWrite();
        getOrCreateFluxProxyDevice().writeCustomTag(tag, FluxConstants.NBT_SAVE_ALL);
    }

    @Override
    public void loadTag(@NotNull CompoundTag tag, HolderLookup.Provider provider) {
        super.loadTag(tag, provider);
        getOrCreateFluxProxyDevice().syncLevel();
        getOrCreateFluxProxyDevice().readCustomTag(tag, FluxConstants.NBT_SAVE_ALL);
    }

    @Override
    public @NotNull CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        syncTransferStateForTagWrite();
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
        return Component.translatable(MoreFluxStorageContent.FLUX_ACCESSOR_FLUX_DESCRIPTION_ID);
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

    @Override
    public @NotNull BlockEntity getTE() {
        return this;
    }

    @Override
    public @NotNull AbstractFluxTransferHandler getProxyTransferHandler() {
        if (level != null && !level.isClientSide) {
            transferHandler.syncBufferFromStorage();
        }
        return transferHandler;
    }

    @Override
    public @NotNull Component getProxyDisplayName() {
        return getDisplayName();
    }

    @Override
    public @NotNull ItemStack getProxyDisplayStack() {
        return MoreFluxStorageContent.getFluxAccessorFluxStack();
    }

    private ProxyFluxDevice getOrCreateFluxProxyDevice() {
        if (fluxProxyDevice == null) {
            fluxProxyDevice = new ProxyFluxDevice(this, getType(), worldPosition, getBlockState());
        }
        return fluxProxyDevice;
    }

    private void syncFluxData() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 11);
        }
    }

    private void syncTransferStateForTagWrite() {
        if (level != null && !level.isClientSide) {
            transferHandler.syncBufferFromStorage();
        }
    }

    private @Nullable IStorageService getStorageService() {
        var grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService();
    }

    private @NotNull IActionSource getActionSource() {
        return IActionSource.ofMachine(this);
    }

    private final class FluxAccessorTransferHandler extends AbstractFluxTransferHandler {

        private void syncBufferFromStorage() {
            // Only sync on server side to avoid client crashes
            if (level == null || level.isClientSide) {
                return;
            }
            IStorageService storage = getStorageService();
            setBuffer(storage == null ? 0L : storage.getCachedInventory().get(FluxKey.of(EnergyType.FE)));
        }

        @Override
        public void onCycleStart() {
            syncBufferFromStorage();
        }

        @Override
        public long getRequest() {
            IStorageService storage = getStorageService();
            if (storage == null) {
                return 0L;
            }

            long receivable = storage.getInventory().insert(FluxKey.of(EnergyType.FE), Long.MAX_VALUE - 1, Actionable.SIMULATE, getActionSource());
            return Math.min(receivable, getRemainingAddLimit());
        }

        @Override
        public void addToBuffer(long amount) {
            IStorageService storage = getStorageService();
            long allowed = Math.min(Math.max(0L, amount), getRemainingAddLimit());
            if (storage == null || allowed <= 0L) {
                return;
            }

            long inserted = storage.getInventory().insert(FluxKey.of(EnergyType.FE), allowed, Actionable.MODULATE, getActionSource());
            super.addToBuffer(inserted);
            syncBufferFromStorage();
            if (inserted > 0L) {
                syncFluxData();
            }
        }

        @Override
        public long removeFromBuffer(long amount) {
            IStorageService storage = getStorageService();
            long allowed = Math.min(Math.max(0L, amount), getRemainingRemoveLimit());
            if (storage == null || allowed <= 0L) {
                return 0L;
            }

            long extracted = storage.getInventory().extract(FluxKey.of(EnergyType.FE), allowed, Actionable.MODULATE, getActionSource());
            long removed = super.removeFromBuffer(extracted);
            syncBufferFromStorage();
            if (removed > 0L) {
                syncFluxData();
            }
            return removed;
        }
    }
}