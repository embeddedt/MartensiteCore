package org.embeddedt.vintagefix.dynamicresources.model;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelBlock;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.IRegistry;
import net.minecraft.util.registry.RegistrySimple;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.embeddedt.vintagefix.event.DynamicModelBakeEvent;
import org.embeddedt.vintagefix.util.ExceptionHelper;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class DynamicBakedModelProvider extends RegistrySimple<ModelResourceLocation, IBakedModel> {
    private static final Logger LOGGER = LogManager.getLogger();
    public static DynamicBakedModelProvider instance;
    public static IBakedModel missingModel;
    public static final ModelResourceLocation MISSING_MODEL_LOCATION = new ModelResourceLocation("builtin/missing", "missing");

    private final IRegistry<ResourceLocation, IModel> modelProvider;
    private final Map<ModelResourceLocation, IBakedModel> permanentlyLoadedBakedModels = new Object2ObjectOpenHashMap<>();
    private final Cache<ModelResourceLocation, Optional<IBakedModel>> loadedBakedModels =
            CacheBuilder.newBuilder()
                        .expireAfterAccess(3, TimeUnit.MINUTES)
                        .maximumSize(1000)
                        .concurrencyLevel(8)
                        .softValues()
                        .build();

    public DynamicBakedModelProvider(DynamicModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    @Override
    protected Map<ModelResourceLocation, IBakedModel> createUnderlyingMap() {
        return ImmutableMap.of();
    }

    @Nullable
    private static IBakedModel getMissingIfRegistered(ModelResourceLocation location) {
        /* check if its known inventory variant */
        if(ModelLocationInformation.allItemVariants.contains(location))
            return missingModel;
        else
            return null;
    }

    @Override
    @Nullable
    public IBakedModel getObject(ModelResourceLocation location) {
        try {
            return loadedBakedModels.get(location, () -> Optional.ofNullable(loadBakedModel(location))).orElse(getMissingIfRegistered(location));
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    @Nullable
    public IBakedModel getModelIfPresent(ModelResourceLocation location) {
        Optional<IBakedModel> opt = loadedBakedModels.getIfPresent(location);
        if(opt != null)
            return opt.orElse(null);
        else
            return null;
    }

    private static final Class<?> VANILLA_MODEL_WRAPPER;

    static {
        try {
            VANILLA_MODEL_WRAPPER = Class.forName("net.minecraftforge.client.model.ModelLoader$VanillaModelWrapper");
        } catch(ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final FileNotFoundException PARENT_MISSING_EXCEPTION = new FileNotFoundException("Failed to load model parent");

    private IBakedModel loadBakedModel(ModelResourceLocation location) {
//        LOGGER.info("Loading baked model " + location);

        IBakedModel bakedModel = permanentlyLoadedBakedModels.get(location);
        if (bakedModel != null) {
            return bakedModel;
        }

        try {
            ResourceLocation inventoryVariantLocation = ModelLocationInformation.getInventoryVariantLocation(location);
            if (inventoryVariantLocation != null) {
                IModel model;
                try {
                    model = modelProvider.getObject(inventoryVariantLocation);
                    Optional<ModelBlock> vModel = model.asVanillaModel();
                    if(vModel.isPresent() && vModel.get().getParentLocation() != null) {
                        ModelBlock parent = vModel.get().parent;
                        if(parent == null || parent == ModelLoaderRegistry.getMissingModel().asVanillaModel().orElse(null)) {
                            throw PARENT_MISSING_EXCEPTION;
                        }
                    }
                } catch (Throwable t) {
                    boolean tryBlockState = false;
                    // first check if this was triggered by FileNotFoundException
                    Throwable potentialExc = t;
                    while(potentialExc != null) {
                        if(potentialExc instanceof FileNotFoundException) {
                            tryBlockState = true;
                            break;
                        }
                        potentialExc = potentialExc.getCause();
                    }
                    // if not, check if the item model even existed
                    if(!tryBlockState) {
                        try(IResource ignored = Minecraft.getMinecraft().getResourceManager().getResource(inventoryVariantLocation)) {
                        } catch(IOException ignored) {
                            // didn't exist, assume blockstate
                            tryBlockState = true;
                        }
                    }
                    if(tryBlockState) {
                        // load from blockstate json
                        ModelLocationInformation.addInventoryVariantLocation(location, location);
                        model = modelProvider.getObject(location);
                    } else {
                        throw t;
                    }
                }

                if (VANILLA_MODEL_WRAPPER.isAssignableFrom(model.getClass())) {
                    for (ResourceLocation dep : model.asVanillaModel().get().getOverrideLocations()) {
                        if (!location.equals(dep)) {
                            ModelLocationInformation.addInventoryVariantLocation(ModelLocationInformation.getInventoryVariant(dep.toString()), dep);
                        }
                    }
                }

                return bakeAndCheckTextures(location, model, DefaultVertexFormats.ITEM);
            }

            IModel model = modelProvider.getObject(location);
            return bakeAndCheckTextures(location, model, DefaultVertexFormats.ITEM);
        } catch (Throwable t) {
            if(ModelLocationInformation.DEBUG_MODEL_LOAD)
                LOGGER.error("Error occured while loading model {}", location, t);
            else
                LOGGER.error("Error occured while loading model {}", location);
        }

        return null;
    }

    private static IBakedModel bakeAndCheckTextures(ResourceLocation location, IModel model, VertexFormat format) {
        // TODO log when textures missing
        synchronized (DynamicBakedModelProvider.class) {
            IBakedModel bakedModel = model.bake(model.getDefaultState(), format, ModelLoader.defaultTextureGetter());
            if(!MISSING_MODEL_LOCATION.equals(location)) {
                DynamicModelBakeEvent event = new DynamicModelBakeEvent(location, model, bakedModel);
                MinecraftForge.EVENT_BUS.post(event);
                return event.bakedModel;
            } else
                return bakedModel;
        }
    }

    @Override
    public void putObject(ModelResourceLocation key, IBakedModel value) {
        permanentlyLoadedBakedModels.put(key, value);
        loadedBakedModels.invalidate(key);
    }

    @Override
    public Set<ModelResourceLocation> getKeys() {
        return permanentlyLoadedBakedModels.keySet();
    }

    @Override
    public Iterator<IBakedModel> iterator() {
        return permanentlyLoadedBakedModels.values().iterator();
    }
}
