package com.circulation.more_flux_storage.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import sonar.fluxnetworks.api.device.IFluxStorage;
import sonar.fluxnetworks.common.device.FluxStorageHandler;
import sonar.fluxnetworks.common.device.TileFluxStorage;

import javax.annotation.Nonnull;

public final class ProxyFluxDevice extends TileFluxStorage implements IFluxStorage {

    private final Host host;

    public ProxyFluxDevice(Host host, BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state, host.getProxyTransferHandler());
        this.host = host;
    }

    public void markChunkUnsaved() {
        if (this.host.getTE().getLevel() != null) {
            this.host.getTE().getLevel().getChunkAt(this.worldPosition).setUnsaved(true);
        }
    }

    public void setLevel() {
        level = host.getTE().getLevel();
    }

    public void hostServerTick() {
        if (level != null && !level.isClientSide) {
            onServerTick();
        }
    }

    public void hostChunkUnloaded() {
        if (level != null && !level.isClientSide) {
            onChunkUnloaded();
        }
    }

    public void hostRemoved() {
        if (level != null && !level.isClientSide) {
            setRemoved();
        }
    }

    public boolean canOpenGui(Player player) {
        return level == null || level.isClientSide || canPlayerAccess(player);
    }

    @Override
    public @Nonnull FluxStorageHandler getTransferHandler() {
        return host.getProxyTransferHandler();
    }

    @Override
    public @Nonnull Component getDisplayName() {
        return host.getProxyDisplayName();
    }

    @Override
    public @Nonnull ItemStack getDisplayStack() {
        return host.getProxyDisplayStack();
    }

    public interface Host {

        @Nonnull
        BlockEntity getTE();

        @Nonnull
        FluxStorageHandler getProxyTransferHandler();

        @Nonnull
        Component getProxyDisplayName();

        @Nonnull
        ItemStack getProxyDisplayStack();
    }
}