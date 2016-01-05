package org.cyclops.integrateddynamics.api.part;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.cyclops.cyclopscore.datastructure.DimPos;
import org.cyclops.cyclopscore.init.IInitListener;
import org.cyclops.integrateddynamics.api.network.*;

import java.util.List;

/**
 * A type of part that can be inserted into a {@link IPartContainer}.
 * Only one unique instance for each part should exist, the values are stored inside an
 * {@link IPartState}.
 * @param <P> The part type.
 * @param <S> The part state type.
 * @author rubensworks
 */
public interface IPartType<P extends IPartType<P, S>, S extends IPartState<P>> extends INetworkEventListener<IPartNetwork, IPartNetworkElement<P, S>> {

    /**
     * Get the part type class.
     * This is used for doing dynamic construction of guis.
     * @return The actual class for this part type.
     */
    public Class<? super P> getPartTypeClass();

    /**
     * @return The unique name for this part type.
     */
    public String getName();

    /**
     * @return The unlocalized base name of this part.
     */
    public String getUnlocalizedNameBase();

    /**
     * @return The unlocalized name of this part. (With the .name suffix)
     */
    public String getUnlocalizedName();

    /**
     * @return The item associated with this part type.
     */
    public Item getItem();

    /**
     * @param state The state
     * @return If this element is solid.
     */
    public boolean isSolid(S state);

    /**
     * @return The position part occupies, used to calculate the required render lengths.
     *         This part is assumed to be aligned at the edge of the block for the depth, while centered on width and height.
     */
    public RenderPosition getRenderPosition();

    /**
     * Called on the Integrated Dynamics mod initialization steps.
     * @param initStep The init step.
     */
    public void onInit(IInitListener.Step initStep);

    /**
     * Write the PROPERTY of this part to NBT.
     * An identificator for this part is not required, this is written somewhere else.
     * @param tag The tag to write to. This tag is guaranteed to be empty.
     * @param partState The state of this part.
     */
    public void toNBT(NBTTagCompound tag, S partState);

    /**
     * Read the PROPERTY of this part from nbt.
     * This tag is guaranteed to only contain data for this part.
     * @param tag The tag to read from.
     * @return The state of this part.
     */
    public S fromNBT(NBTTagCompound tag);

    /**
     * @return The default state of this part.
     */
    public S getDefaultState();

    /**
     * Set the update interval for this part.
     * @param state The state
     * @param updateInterval The tick interval to update this element.
     */
    public void setUpdateInterval(S state, int updateInterval);

    /**
     * @param state The state
     * @return The tick interval to update this element.
     */
    public int getUpdateInterval(S state);

    /**
     * @param state The state
     * @return If this element should be updated. This method is only called once during network initialization.
     */
    public boolean isUpdate(S state);

    /**
     * @param network The network to update in.
     * @param state The state
     * @param target The target block.
     * Update at the tick interval specified.
     */
    public void update(IPartNetwork network, PartTarget target, S state);

    /**
     * @param network The network to update in.
     * @param state The state
     * @param target The target block.
     * Called right before the network is terminated or will be reset.
     */
    public void beforeNetworkKill(IPartNetwork network, PartTarget target, S state);

    /**
     * @param network The network to update in.
     * @param state The state
     * @param target The target block.
     * Called right after this network is initialized.
     */
    public void afterNetworkAlive(IPartNetwork network, PartTarget target, S state);

    /**
     * Get the itemstack from the given state.
     * @param state The state
     * @return The itemstack possibly containing the state information.
     */
    public ItemStack getItemStack(S state);

    /**
     * Get the part state from the given itemstack.
     * @param itemStack The itemstack possibly containing state information.
     * @return The state contained in the itemstack or the default part state.
     */
    public S getState(ItemStack itemStack);

    /**
     * Add the itemstacks to drop when this element is removed.
     * @param target The target.
     * @param state The state
     * @param itemStacks The itemstack list to add to.
     */
    public void addDrops(PartTarget target, S state, List<ItemStack> itemStacks);

    /**
     * Called when this element is added to the network.
     * @param network The network to update in.
     * @param state The state
     * @param target The target block.
     */
    public void onNetworkAddition(IPartNetwork network, PartTarget target, S state);

    /**
     * Called when this element is removed from the network.
     * @param network The network to update in.
     * @param state The state
     * @param target The target block.
     */
    public void onNetworkRemoval(IPartNetwork network, PartTarget target, S state);

    /**
     * Create a network element for this part type.
     * @param partContainerFacade The facade for reaching the container this part is/will be part of.
     * @param pos The position this network element is/will be placed at.
     * @param side The side this network element is/will be placed at.
     * @return A new network element instance.
     */
    public INetworkElement<IPartNetwork> createNetworkElement(IPartContainerFacade partContainerFacade, DimPos pos, EnumFacing side);

    /**
     * Called when a part is right-clicked.
     * @param world The world.
     * @param pos The position of the block this part is part of.
     * @param state The block state of the parent block.
     * @param partState The state of this part.
     * @param player The player activating the part.
     * @param side The side this part is attached on.
     * @param hitX The X hit position.
     * @param hitY The Y hit position.
     * @param hitZ The Z hit position.
     * @return True if the further processing should be stopped.
     */
    public boolean onPartActivated(World world, BlockPos pos, IBlockState state, S partState, EntityPlayer player,
                                   EnumFacing side, float hitX, float hitY, float hitZ);

    /**
     * Get the base block state that will be rendered for this part.
     * An appropriate {@link org.cyclops.integrateddynamics.core.block.IgnoredBlock#FACING} property will be set.
     * @param partContainer The tile entity.
     * @param x X
     * @param y Y
     * @param z Z
     * @param partialTick The partial tick
     * @param destroyStage The stage of the block destruction.
     * @param side The position of the part.
     * @return The block state to render with.
     */
    public IBlockState getBlockState(IPartContainer partContainer, double x, double y, double z, float partialTick,
                                     int destroyStage, EnumFacing side);

    /**
     * Called when this element is about to be removed.
     * @param network The network.
     * @param state The state
     * @param target The target block.
     */
    public void onPreRemoved(IPartNetwork network, PartTarget target, S state);

    /**
     * Called when a neighbouring block is updated, more specifically when
     * {@link net.minecraft.block.Block#onNeighborChange(IBlockAccess, BlockPos, BlockPos)} is called.
     * @param network The network to update in.
     * @param state The state
     * @param target The target block.
     * @param world The world in which the neighbour was updated.
     * @param neighborBlock The block type of the neighbour that was updated.
     */
    public void onBlockNeighborChange(IPartNetwork network, PartTarget target, S state, IBlockAccess world, Block neighborBlock);

    /**
     * @param state The state
     * @return The consumption rate of this part for the given state.
     */
    public int getConsumptionRate(S state);

    /**
     * Called after the element was updated or not.
     * If the update was not called, this can be because the network did not contain
     * enough energy to let this element work.
     * @param network The network to update in.
     * @param state The state
     * @param target The target block.
     * @param updated If the {@link INetworkElement#update(INetwork)} was called.
     */
    public void postUpdate(IPartNetwork network, PartTarget target, S state, boolean updated);

    /**
     * @param state The state
     * @return If this part is enabled.
     */
    public boolean isEnabled(S state);

    /**
     * Set if this part should work.
     * @param state The state
     * @param enabled If it should work.
     */
    public void setEnabled(S state, boolean enabled);

    public static class RenderPosition {

        public static final RenderPosition NONE = new RenderPosition(-1, -1, -1);

        private final float depthFactor;
        private final float widthFactor;
        private final float heightFactor;

        public float getDepthFactor() {
            return depthFactor;
        }

        public float getWidthFactor() {
            return widthFactor;
        }

        public float getHeightFactor() {
            return heightFactor;
        }

        public RenderPosition(float depthFactor, float widthFactor, float heightFactor) {
            this.depthFactor = depthFactor;
            this.widthFactor = widthFactor;
            this.heightFactor = heightFactor;
        }

    }

}
