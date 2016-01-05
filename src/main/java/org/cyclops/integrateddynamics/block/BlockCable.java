package org.cyclops.integrateddynamics.block;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import lombok.Setter;
import lombok.experimental.Delegate;
import mcmultipart.multipart.PartState;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import net.minecraftforge.common.property.Properties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.cyclops.cyclopscore.block.property.BlockProperty;
import org.cyclops.cyclopscore.block.property.UnlistedProperty;
import org.cyclops.cyclopscore.client.icon.Icon;
import org.cyclops.cyclopscore.config.configurable.ConfigurableBlockContainer;
import org.cyclops.cyclopscore.config.extendedconfig.ExtendedConfig;
import org.cyclops.cyclopscore.datastructure.DimPos;
import org.cyclops.cyclopscore.helper.*;
import org.cyclops.integrateddynamics.api.block.IDynamicLightBlock;
import org.cyclops.integrateddynamics.api.block.IDynamicRedstoneBlock;
import org.cyclops.integrateddynamics.api.block.cable.ICable;
import org.cyclops.integrateddynamics.api.block.cable.ICableFacadeable;
import org.cyclops.integrateddynamics.api.block.cable.ICableFakeable;
import org.cyclops.integrateddynamics.api.block.cable.ICableNetwork;
import org.cyclops.integrateddynamics.api.network.INetworkElement;
import org.cyclops.integrateddynamics.api.network.INetworkElementProvider;
import org.cyclops.integrateddynamics.api.network.IPartNetwork;
import org.cyclops.integrateddynamics.api.part.IPartContainer;
import org.cyclops.integrateddynamics.api.part.IPartContainerFacade;
import org.cyclops.integrateddynamics.api.part.IPartType;
import org.cyclops.integrateddynamics.api.path.ICablePathElement;
import org.cyclops.integrateddynamics.client.model.CableModel;
import org.cyclops.integrateddynamics.core.block.ICollidable;
import org.cyclops.integrateddynamics.core.block.ICollidableParent;
import org.cyclops.integrateddynamics.core.block.cable.CableNetworkFacadeableComponent;
import org.cyclops.integrateddynamics.core.block.cable.NetworkElementProviderComponent;
import org.cyclops.integrateddynamics.core.helper.WrenchHelpers;
import org.cyclops.integrateddynamics.core.path.CablePathElement;
import org.cyclops.integrateddynamics.core.tileentity.TileMultipartTicking;
import org.cyclops.integrateddynamics.item.ItemBlockCable;
import org.cyclops.integrateddynamics.item.ItemFacade;
import org.cyclops.integrateddynamics.modcompat.mcmultipart.MultipartBlockComponent;
import org.cyclops.integrateddynamics.modcompat.mcmultipart.MultipartCollidableComponent;

import javax.annotation.Nullable;
import java.util.*;


/**
 * A block that is build up from different parts.
 * This block refers to a ticking tile entity.
 * Ray tracing code is partially based on BuildCraft's pipe code.
 * @author rubensworks
 */
public class BlockCable extends ConfigurableBlockContainer implements ICableNetwork<IPartNetwork, ICablePathElement>,
        ICableFakeable<ICablePathElement>, ICableFacadeable<ICablePathElement>, INetworkElementProvider,
        IPartContainerFacade, ICollidable<EnumFacing>, ICollidableParent, IDynamicRedstoneBlock, IDynamicLightBlock {

    // Properties
    @BlockProperty
    public static final IUnlistedProperty<Boolean> REALCABLE = Properties.toUnlisted(PropertyBool.create("realcable"));
    @BlockProperty
    public static final IUnlistedProperty<Boolean>[] CONNECTED = new IUnlistedProperty[6];
    @BlockProperty
    public static final IUnlistedProperty<Boolean>[] PART = new IUnlistedProperty[6];
    @BlockProperty
    public static final IUnlistedProperty<IPartType.RenderPosition>[] PART_RENDERPOSITIONS = new IUnlistedProperty[6];
    @BlockProperty
    public static final IUnlistedProperty<Optional> FACADE = new UnlistedProperty<>("facade", Optional.class);
    static {
        for(EnumFacing side : EnumFacing.values()) {
            CONNECTED[side.ordinal()] = Properties.toUnlisted(PropertyBool.create("connect-" + side.getName()));
            PART[side.ordinal()] = Properties.toUnlisted(PropertyBool.create("part-" + side.getName()));
            PART_RENDERPOSITIONS[side.ordinal()] = new UnlistedProperty<>("partRenderPosition-" + side.getName(), IPartType.RenderPosition.class);
        }
    }
    @BlockProperty
    public static final IUnlistedProperty<List<PartState>> MCMULTIPARTS = MultipartBlockComponent.PROPERTY;

    // Collision boxes
    private static final float[][] CABLE_COLLISION_BOXES = {
            {CableModel.MIN, 0, CableModel.MIN, CableModel.MAX, CableModel.MIN, CableModel.MAX}, // DOWN
            {CableModel.MIN, CableModel.MAX, CableModel.MIN, CableModel.MAX, 1, CableModel.MAX}, // UP
            {CableModel.MIN, CableModel.MIN, 0, CableModel.MAX, CableModel.MAX, CableModel.MIN}, // NORTH
            {CableModel.MIN, CableModel.MAX, CableModel.MAX, CableModel.MAX, CableModel.MIN, 1}, // SOUTH
            {0, CableModel.MIN, CableModel.MIN, CableModel.MIN, CableModel.MAX, CableModel.MAX}, // WEST
            {CableModel.MAX, CableModel.MIN, CableModel.MIN, 1, CableModel.MAX, CableModel.MAX}, // EAST
    };
    private static final float[][] PART_COLLISION_BOXES = {
            {0.19F, 0.81F}, {CableModel.MIN - 0.37F, CableModel.MIN}, {0.19F, 0.81F}
    };

    // Collision components
    private static final List<IComponent<EnumFacing, BlockCable>> COLLIDABLE_COMPONENTS = Lists.newLinkedList();
    private static final IComponent<EnumFacing, BlockCable> CENTER_COMPONENT = new IComponent<EnumFacing, BlockCable>() {
        @Override
        public Collection<EnumFacing> getPossiblePositions() {
            return Arrays.asList(new EnumFacing[]{null});
        }

        @Override
        public boolean isActive(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return block.isRealCable(world, pos);
        }

        @Override
        public AxisAlignedBB getBounds(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return block.getCableBoundingBox(null);
        }
    };
    private static final IComponent<EnumFacing, BlockCable> CABLECONNECTIONS_COMPONENT = new IComponent<EnumFacing, BlockCable>() {
        @Override
        public Collection<EnumFacing> getPossiblePositions() {
            return Arrays.asList(EnumFacing.VALUES);
        }

        @Override
        public boolean isActive(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return block.isConnected(world, pos, position);
        }

        @Override
        public AxisAlignedBB getBounds(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return block.getCableBoundingBox(position);
        }
    };
    private static final IComponent<EnumFacing, BlockCable> PARTS_COMPONENT = new IComponent<EnumFacing, BlockCable>() {
        @Override
        public Collection<EnumFacing> getPossiblePositions() {
            return Arrays.asList(EnumFacing.VALUES);
        }

        @Override
        public boolean isActive(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return block.hasPart(world, pos, position);
        }

        @Override
        public AxisAlignedBB getBounds(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return block.getPartBoundingBox(position).expand(0.01, 0.01, 0.01);
        }
    };
    private static final IComponent<EnumFacing, BlockCable> FACADE_COMPONENT = new IComponent<EnumFacing, BlockCable>() {

        private final AxisAlignedBB BOUNDS = new AxisAlignedBB(0, 0, 0, 1, 1, 1);

        @Override
        public Collection<EnumFacing> getPossiblePositions() {
            return Arrays.asList(new EnumFacing[]{null});
        }

        @Override
        public boolean isActive(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return block.hasFacade(world, pos);
        }

        @Override
        public AxisAlignedBB getBounds(BlockCable block, World world, BlockPos pos, EnumFacing position) {
            return BOUNDS;
        }
    };
    static {
        COLLIDABLE_COMPONENTS.add(FACADE_COMPONENT);
        COLLIDABLE_COMPONENTS.add(CENTER_COMPONENT);
        COLLIDABLE_COMPONENTS.add(CABLECONNECTIONS_COMPONENT);
        COLLIDABLE_COMPONENTS.add(PARTS_COMPONENT);
    }
    @Delegate
    private ICollidable collidableComponent = new MultipartCollidableComponent<EnumFacing, BlockCable>(this, COLLIDABLE_COMPONENTS);
    //@Delegate// <- Lombok can't handle delegations with generics, so we'll have to do it manually...
    private CableNetworkFacadeableComponent<BlockCable> cableNetworkComponent = new CableNetworkFacadeableComponent<>(this);
    private NetworkElementProviderComponent<IPartNetwork> networkElementProviderComponent = new NetworkElementProviderComponent<>(this);
    private MultipartBlockComponent multipartBlockComponent = new MultipartBlockComponent();

    private static BlockCable _instance = null;

    @SideOnly(Side.CLIENT)
    @Icon(location = "blocks/cable")
    public TextureAtlasSprite texture;
    @Setter
    private boolean disableCollisionBox = false;

    /**
     * Get the unique instance.
     * @return The instance.
     */
    public static BlockCable getInstance() {
        return _instance;
    }

    /**
     * Make a new block instance.
     * @param eConfig Config for this block.
     */
    public BlockCable(ExtendedConfig eConfig) {
        super(eConfig, Material.glass, TileMultipartTicking.class);

        setHardness(3.0F);
        setStepSound(soundTypeMetal);
        if(MinecraftHelpers.isClientSide()) {
            eConfig.getMod().getIconProvider().registerIconHolderObject(this);
        }
    }

    protected MultipartBlockComponent getBlockComponent() {
        return this.multipartBlockComponent;
    }

    @Override
    public IBlockState getExtendedState(IBlockState state, IBlockAccess world, BlockPos pos) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        IExtendedBlockState extendedBlockState;
        if(tile != null) {
            extendedBlockState = tile.getConnectionState();
        } else {
            extendedBlockState = (IExtendedBlockState) getDefaultState();
        }
        return multipartBlockComponent.applyExtendedState(extendedBlockState, world, pos);
    }

    protected boolean hasPart(IBlockAccess world, BlockPos pos, EnumFacing side) {
        return BlockHelpers.getSafeBlockStateProperty(
               (IExtendedBlockState) getExtendedState(world.getBlockState(pos), world, pos),
               PART[side.ordinal()],
               false);
    }

    @Override
    public boolean isRealCable(World world, BlockPos pos) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            return tile.isRealCable();
        }
        return true;
    }

    @Override
    public void setRealCable(World world, BlockPos pos, boolean realCable) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            tile.setRealCable(realCable);
            if(realCable) {
                cableNetworkComponent.addToNetwork(world, pos);
            } else {
                if(!cableNetworkComponent.removeFromNetwork(world, pos)) {
                    tile.setRealCable(!realCable);
                }
            }
        }
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumFacing side, float hitX, float hitY, float hitZ) {
        /*
            Wrench: sneak + right-click anywhere on cable to remove cable
                    right-click on a cable side to disconnect on that side
                    sneak + right-click on part to remove that part
            No wrench: right-click to open GUI
         */
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            RayTraceResult<EnumFacing> rayTraceResult = doRayTrace(world, pos, player);
            if(rayTraceResult != null) {
                EnumFacing positionHit = rayTraceResult.getPositionHit();
                if(rayTraceResult.getCollisionType() == FACADE_COMPONENT) {
                    if(!world.isRemote && WrenchHelpers.isWrench(player, pos) && player.isSneaking()) {
                        IBlockState blockState = getFacade(world, pos);
                        ItemStack itemStack = new ItemStack(ItemFacade.getInstance());
                        ItemFacade.getInstance().writeFacadeBlock(itemStack, blockState);
                        setFacade(world, pos, null);
                        ItemStackHelpers.spawnItemStackToPlayer(world, pos, itemStack, player);
                        world.notifyNeighborsOfStateChange(pos, this);
                        return true;
                    }
                    return false;
                } else if(rayTraceResult.getCollisionType() == PARTS_COMPONENT) {
                    if(!world.isRemote && WrenchHelpers.isWrench(player, pos)) {
                        // Remove part from cable
                        if(player.isSneaking()) {
                            getPartContainer(world, pos).removePart(positionHit, player);
                            world.notifyNeighborsOfStateChange(pos, this);
                            ItemBlockCable.playBreakSound(world, pos, state);
                            // Remove full cable block if this was the last part and if it was already an unreal cable.
                            if(!isRealCable(world, pos) && !getPartContainer(world, pos).hasParts()) {
                                world.destroyBlock(pos, !player.capabilities.isCreativeMode);
                            }
                        }
                        return true;
                    } else {
                        // Delegate activated call to part
                        getPartContainer(world, pos).getPart(positionHit).onPartActivated(world, pos, state,
                                getPartContainer(world, pos).getPartState(positionHit), player, positionHit, hitX, hitY, hitZ);
                        return true;
                    }
                } else if (!world.isRemote
                        && (rayTraceResult.getCollisionType() == CABLECONNECTIONS_COMPONENT
                            || rayTraceResult.getCollisionType() == CENTER_COMPONENT)) {
                    if(WrenchHelpers.isWrench(player, pos)) {
                        if (player.isSneaking()) {
                            if (!getPartContainer(world, pos).hasParts()) {
                                // Remove full cable
                                world.destroyBlock(pos, true);
                            } else {
                                // Mark cable as unavailable.
                                setRealCable(world, pos, false);
                                ItemBlockCable.playBreakSound(world, pos, state);
                                ItemStackHelpers.spawnItemStackToPlayer(world, pos, new ItemStack(BlockCable.getInstance()), player);
                            }
                        } else if (rayTraceResult.getCollisionType() == CABLECONNECTIONS_COMPONENT) {
                            // Disconnect cable side

                            // Store the disconnection in the tile entity
                            disconnect(world, pos, positionHit);

                            // Signal changes
                            updateConnections(world, pos);
                            cableNetworkComponent.triggerNeighbourConnections(world, pos);

                            // Reinit the networks for this block and the disconnected neighbour.
                            initNetwork(world, pos);
                            BlockPos neighbourPos = pos.offset(positionHit);
                            Block neighbourBlock = world.getBlockState(neighbourPos).getBlock();
                            if (neighbourBlock instanceof ICableNetwork) {
                                ((ICableNetwork<IPartNetwork, ICablePathElement>) neighbourBlock).initNetwork(world, neighbourPos);
                            }
                            return true;
                        } else if (rayTraceResult.getCollisionType() == CENTER_COMPONENT) {
                            // Reconnect cable side
                            BlockPos neighbourPos = pos.offset(side);
                            Block neighbourBlock = world.getBlockState(neighbourPos).getBlock();
                            if(neighbourBlock instanceof ICable && !isConnected(world, pos, side) &&
                                    (canConnect(world, pos, this, side) || ((ICable) neighbourBlock).canConnect(world, neighbourPos, this, side.getOpposite()))
                                    ) {
                                // Notify the reconnection in the tile entity of this and the neighbour block,
                                // since we don't know in which one the disconnection was made.
                                reconnect(world, pos, side);
                                ((ICable) neighbourBlock).reconnect(world, neighbourPos, side.getOpposite());

                                // Signal changes
                                updateConnections(world, pos);
                                cableNetworkComponent.triggerNeighbourConnections(world, pos);

                                // Reinit the networks for this block and the connected neighbour.
                                initNetwork(world, pos);
                                if (neighbourBlock instanceof ICableNetwork) {
                                    ((ICableNetwork<IPartNetwork, ICablePathElement>) neighbourBlock).initNetwork(world, neighbourPos);
                                }
                            }
                            return true;
                        }
                        return true;
                    }
                }
            } else {
                return multipartBlockComponent.onBlockActivated(world, pos, state, player , side, hitX, hitY, hitZ);
            }
        }
        return super.onBlockActivated(world, pos, state, player , side, hitX, hitY, hitZ);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack itemStack) {
        super.onBlockPlacedBy(world, pos, state, placer, itemStack);
        cableNetworkComponent.addToNetwork(world, pos);
    }

    @Override
    public boolean saveNBTToDroppedItem() {
        return false;
    }

    @Override
    public boolean isDropBlockItem(IBlockAccess world, BlockPos pos, IBlockState blockState, int fortune) {
        return BlockHelpers.getSafeBlockStateProperty((IExtendedBlockState) getExtendedState(blockState, world, pos),
               REALCABLE, true);
    }

    @Override
    public ItemStack getPickBlock(MovingObjectPosition target, World world, BlockPos pos, EntityPlayer player) {
        // TODO: check if targeting part
        ItemStack itemStack = multipartBlockComponent.getPickBlock(target, world, pos, player);
        if(itemStack == null) {
            itemStack = new ItemStack(getItem(world, pos), 1, getDamageValue(world, pos));;
        }
        return itemStack;
    }

    @Override
    protected void onPreBlockDestroyed(World world, BlockPos pos) {
        multipartBlockComponent.onPreBlockDestroyed(world, pos);
        networkElementProviderComponent.onPreBlockDestroyed(getNetwork(world, pos), world, pos);
        if(isRealCable(world, pos)) {
            cableNetworkComponent.onPreBlockDestroyed(world, pos);
        }
        super.onPreBlockDestroyed(world, pos);
    }

    @Override
    public List<ItemStack> getDrops(IBlockAccess world, BlockPos pos, IBlockState blockState, int fortune) {
        List<ItemStack> allDrops = Lists.newLinkedList();
        allDrops.addAll(multipartBlockComponent.getDrops(world, pos));
        allDrops.addAll(super.getDrops(world, pos, blockState, fortune));
        return allDrops;
    }

    @Override
    protected void onPostBlockDestroyed(World world, BlockPos pos) {
        super.onPostBlockDestroyed(world, pos);
        cableNetworkComponent.onPostBlockDestroyed(world, pos);
    }

    @Override
    public Collection<INetworkElement> createNetworkElements(World world, BlockPos blockPos) {
        Set<INetworkElement> sidedElements = Sets.newHashSet();
        for(Map.Entry<EnumFacing, IPartType<?, ?>> entry : getPartContainer(world, blockPos).getPartTypes().entrySet()) {
            sidedElements.add(entry.getValue().createNetworkElement(this, DimPos.of(world, blockPos), entry.getKey()));
        }
        return sidedElements;
    }

    @Override
    public IPartContainer getPartContainer(IBlockAccess world, BlockPos pos) {
        return TileHelpers.getSafeTile(world, pos, IPartContainer.class);
    }

    /* --------------- MCMultiPart-specific calls --------------- */

    @Override
    public boolean removedByPlayer(World world, BlockPos pos, EntityPlayer player, boolean willHarvest) {
        return multipartBlockComponent.removedByPlayer(world, pos, player, willHarvest) && super.removedByPlayer(world, pos, player, willHarvest);
    }

    @Override
    public float getPlayerRelativeBlockHardness(EntityPlayer playerIn, World worldIn, BlockPos pos) {
        return Math.max(
                super.getPlayerRelativeBlockHardness(playerIn, worldIn, pos),
                multipartBlockComponent.getPlayerRelativeBlockHardness(playerIn, worldIn, pos)
        );
    }

    @Override
    public void onBlockClicked(World worldIn, BlockPos pos, EntityPlayer playerIn) {
        multipartBlockComponent.onBlockClicked(worldIn, pos, playerIn);
        super.onBlockClicked(worldIn, pos, playerIn);
    }

    @Override
    public void onNeighborChange(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        super.onNeighborChange(world, pos, neighbor);
        multipartBlockComponent.onNeighborChange(world, pos, neighbor);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void randomDisplayTick(World worldIn, BlockPos pos, IBlockState state, Random rand) {
        super.randomDisplayTick(worldIn, pos, state, rand);
        multipartBlockComponent.randomDisplayTick(worldIn, pos, state, rand);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public boolean addDestroyEffects(World world, BlockPos pos, EffectRenderer effectRenderer) {
        return multipartBlockComponent.addDestroyEffects(world, pos, effectRenderer) || super.addDestroyEffects(world, pos, effectRenderer);
    }

    @Override
    public boolean addLandingEffects(WorldServer worldObj, BlockPos blockPosition, IBlockState iblockstate, EntityLivingBase entity, int numberOfParticles) {
        return multipartBlockComponent.addLandingEffects(worldObj, blockPosition, iblockstate, entity, numberOfParticles) ||
                super.addLandingEffects(worldObj, blockPosition, iblockstate, entity, numberOfParticles);
    }

    /* --------------- Start ICollidable and rendering --------------- */

    @Override
    public AxisAlignedBB getCollisionBoundingBox(World worldIn, BlockPos pos, IBlockState state) {
        if(disableCollisionBox) return null;
        return super.getCollisionBoundingBox(worldIn, pos, state);
    }

    @Override
    public int getLightOpacity(IBlockAccess world, BlockPos pos) {
        return hasFacade(world, pos) ? 255 : 0;
    }

    @Override
    public int getRenderType() {
        return 3;
    }

    @Override
    public boolean hasDynamicModel() {
        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IBakedModel createDynamicModel() {
        return new CableModel();
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean doesSideBlockRendering(IBlockAccess world, BlockPos pos, EnumFacing face) {
        return super.doesSideBlockRendering(world, pos, face) || hasFacade(world, pos);
    }

    @Override
    public boolean isFullCube() {
        return false;
    }

    @Override
    public boolean isNormalCube() {
        return false;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean addHitEffects(World world, MovingObjectPosition target, EffectRenderer effectRenderer) {
        BlockPos blockPos = target.getBlockPos();
        if(hasFacade(world, blockPos)) {
            IBlockState blockState = getFacade(world, blockPos);
            RenderHelpers.addBlockHitEffects(effectRenderer, world, blockState, blockPos, target.sideHit);
            return true;
        } else {
            return multipartBlockComponent.addHitEffects(world, target, effectRenderer) ||
                    super.addHitEffects(world, target, effectRenderer);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean isSideSolid(IBlockAccess world, BlockPos pos, EnumFacing side) {
        if(hasFacade(world, pos)) {
            return true;
        }
        boolean isSideSolid;
        if(hasPart(world, pos, side)) {
            IPartContainer partContainer = getPartContainer(world, pos);
            IPartType partType = partContainer.getPart(side);
            isSideSolid = partType.isSolid(partContainer.getPartState(side));
        } else {
            isSideSolid = false;
        }
        return isSideSolid ||
                multipartBlockComponent.isSideSolid(world, pos, side) ||
                super.isSideSolid(world, pos, side);
    }

    @Override
    public boolean canPlaceTorchOnTop(IBlockAccess world, BlockPos pos) {
        if(!super.canPlaceTorchOnTop(world, pos)) {
            return multipartBlockComponent.canPlaceTorchOnTop(world, pos);
        }
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public EnumWorldBlockLayer getBlockLayer() {
        return EnumWorldBlockLayer.TRANSLUCENT;
    }

    private AxisAlignedBB getCableBoundingBox(EnumFacing side) {
        float min = CableModel.MIN;
        float max = CableModel.MAX;
        if (side == null) {
            return AxisAlignedBB.fromBounds(min, min, min, max, max, max).expand(0.002F, 0.002F, 0.002F);
        } else {
            float[] b = CABLE_COLLISION_BOXES[side.ordinal()];
            return AxisAlignedBB.fromBounds(b[0], b[1], b[2], b[3], b[4], b[5]).expand(0.001F, 0.001F, 0.001F);
        }
    }

    private AxisAlignedBB getPartBoundingBox(EnumFacing side) {
        // Copy bounds
        float[][] bounds = new float[PART_COLLISION_BOXES.length][PART_COLLISION_BOXES[0].length];
        for (int i = 0; i < bounds.length; i++)
            bounds[i] = Arrays.copyOf(PART_COLLISION_BOXES[i], PART_COLLISION_BOXES[i].length);

        // Transform bounds
        MatrixHelpers.transform(bounds, side);
        return AxisAlignedBB.fromBounds(bounds[0][0], bounds[1][0], bounds[2][0], bounds[0][1], bounds[1][1], bounds[2][1]);
    }

    @Override
    public void addCollisionBoxesToListParent(World worldIn, BlockPos pos, IBlockState state, AxisAlignedBB mask,
                                              List list, Entity collidingEntity) {
        super.addCollisionBoxesToList(worldIn, pos, state, mask, list, collidingEntity);
    }

    @Override
    public AxisAlignedBB getSelectedBoundingBoxParent(World worldIn, BlockPos pos) {
        return super.getSelectedBoundingBox(worldIn, pos);
    }

    @Override
    public MovingObjectPosition collisionRayTraceParent(World world, BlockPos pos, Vec3 origin, Vec3 direction) {
        return super.collisionRayTrace(world, pos, origin, direction);
    }

    @Override
    public boolean canRenderInLayer(EnumWorldBlockLayer layer) {
        return true; // Because of MCMP
    }

    /* --------------- Start IDynamicRedstoneBlock --------------- */

    @Override
    public void disableRedstoneAt(IBlockAccess world, BlockPos pos, EnumFacing side) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            tile.disableRedstoneLevel(side);
        }
    }

    @Override
    public void setRedstoneLevel(IBlockAccess world, BlockPos pos, EnumFacing side, int level) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            tile.setRedstoneLevel(side, level);
        }
    }

    @Override
    public int getRedstoneLevel(IBlockAccess world, BlockPos pos, EnumFacing side) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            return tile.getRedstoneLevel(side);
        }
        return -1;
    }

    @Override
    public void setAllowRedstoneInput(IBlockAccess world, BlockPos pos, EnumFacing side, boolean allow) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            tile.setAllowRedstoneInput(side, allow);
        }
    }

    @Override
    public boolean isAllowRedstoneInput(IBlockAccess world, BlockPos pos, EnumFacing side) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            return tile.isAllowRedstoneInput(side);
        }
        return false;
    }

    @Override
    public boolean canProvidePower() {
        return true;
    }

    @Override
    public boolean canConnectRedstone(IBlockAccess world, BlockPos pos, EnumFacing side) {
        return getRedstoneLevel(world, pos, side.getOpposite()) >= 0
                || isAllowRedstoneInput(world, pos, side.getOpposite())
                || multipartBlockComponent.canConnectRedstone(world, pos, side);
    }

    @Override
    public int getStrongPower(IBlockAccess world, BlockPos pos, IBlockState state, EnumFacing side) {
        return multipartBlockComponent.getStrongPower(world, pos, state, side);
    }

    @Override
    public int getWeakPower(IBlockAccess world, BlockPos pos, IBlockState state, EnumFacing side) {
        return Math.max(
                getRedstoneLevel(world, pos, side.getOpposite()),
                multipartBlockComponent.getWeakPower(world, pos, state, side)
        );
    }

    /* --------------- Start IDynamicLightBlock --------------- */

    @Override
    public void setLightLevel(IBlockAccess world, BlockPos pos, EnumFacing side, int level) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        if(tile != null) {
            tile.setLightLevel(side, level);
        }
    }

    @Override
    public int getLightLevel(IBlockAccess world, BlockPos pos, EnumFacing side) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos, TileMultipartTicking.class);
        int light = 0;
        if(tile != null) {
            light = tile.getLightLevel(side);
        }
        return Math.max(light, multipartBlockComponent.getLightValue(world, pos));
    }

    @Override
    public int getLightValue(IBlockAccess world, BlockPos pos) {
        int light = 0;
        for(EnumFacing side : EnumFacing.values()) {
            light = Math.max(light, getLightLevel(world, pos, side));
        }
        return light;
    }

    /* --------------- Delegate to ICableNetwork<CablePathElement> --------------- */

    @Override
    public void initNetwork(World world, BlockPos pos) {
        if(isRealCable(world, pos)) {
            cableNetworkComponent.initNetwork(world, pos);
        }
    }

    @Override
    public boolean canConnect(World world, BlockPos selfPosition, ICable connector, EnumFacing side) {
        return cableNetworkComponent.canConnect(world, selfPosition, connector, side);
    }

    @Override
    public void updateConnections(World world, BlockPos pos) {
        cableNetworkComponent.updateConnections(world, pos);
    }

    @Override
    public boolean isConnected(World world, BlockPos pos, EnumFacing side) {
        // Note delegated to component, but instead use the cached information in the extended blockstate
        return BlockHelpers.getSafeBlockStateProperty(
                (IExtendedBlockState) getExtendedState(world.getBlockState(pos), world, pos),
                CONNECTED[side.ordinal()],
                false);
    }

    @Override
    public void disconnect(World world, BlockPos pos, EnumFacing side) {
        cableNetworkComponent.disconnect(world, pos, side);
    }

    @Override
    public void reconnect(World world, BlockPos pos, EnumFacing side) {
        cableNetworkComponent.reconnect(world, pos, side);
    }

    @Override
    public boolean hasFacade(IBlockAccess world, BlockPos pos) {
        return cableNetworkComponent.hasFacade(world, pos);
    }

    @Override
    public IBlockState getFacade(World world, BlockPos pos) {
        return cableNetworkComponent.getFacade(world, pos);
    }

    @Override
    public void setFacade(World world, BlockPos pos, @Nullable IBlockState blockState) {
        cableNetworkComponent.setFacade(world, pos, blockState);
    }

    @Override
    public void resetCurrentNetwork(World world, BlockPos pos) {
        cableNetworkComponent.resetCurrentNetwork(world, pos);
    }

    @Override
    public void setNetwork(IPartNetwork network, World world, BlockPos pos) {
        cableNetworkComponent.setNetwork(network, world, pos);
    }

    @Override
    public IPartNetwork getNetwork(World world, BlockPos pos) {
        return cableNetworkComponent.getNetwork(world, pos);
    }

    @Override
    public CablePathElement createPathElement(World world, BlockPos blockPos) {
        return cableNetworkComponent.createPathElement(world, blockPos);
    }

    @Override
    public void onNeighborBlockChange(World world, BlockPos pos, IBlockState state, Block neighborBlock) {
        multipartBlockComponent.onNeighborBlockChange(world, pos, state, neighborBlock);
        super.onNeighborBlockChange(world, pos, state, neighborBlock);
        networkElementProviderComponent.onBlockNeighborChange(getNetwork(world, pos), world, pos, neighborBlock);
    }
}
