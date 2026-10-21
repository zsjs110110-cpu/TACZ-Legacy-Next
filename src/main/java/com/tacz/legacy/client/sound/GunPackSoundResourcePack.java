package com.tacz.legacy.client.sound;

import com.tacz.legacy.TACZLegacy;
import com.tacz.legacy.client.resource.TACZClientAssetManager;
import com.tacz.legacy.mixin.minecraft.client.MinecraftInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource-pack bridge exposing gun pack sound assets to Minecraft's sound system.
 */
@SideOnly(Side.CLIENT)
public final class GunPackSoundResourcePack implements IResourcePack {
    private static final GunPackSoundResourcePack INSTANCE = new GunPackSoundResourcePack();

    private volatile Set<String> resourceDomains = Collections.emptySet();

    private GunPackSoundResourcePack() {
    }

    @SuppressWarnings("deprecation")
    public static synchronized void installOrUpdate(Set<ResourceLocation> soundResources) {
        synchronize(soundResources, true);
    }

    @SuppressWarnings("deprecation")
    public static synchronized void synchronize(Set<ResourceLocation> soundResources, boolean enabled) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return;
        }
        if (!enabled) {
            INSTANCE.resourceDomains = Collections.emptySet();
            boolean removed = removeInstalled(minecraft);
            if (removed) {
                TACZLegacy.logger.info("Refreshing resources after disabling gun pack sound bridge");
                minecraft.refreshResources();
            }
            return;
        }

        LinkedHashSet<String> newDomains = new LinkedHashSet<>();
        for (ResourceLocation soundId : soundResources) {
            newDomains.add(soundId.getNamespace());
        }
        boolean domainsChanged = !INSTANCE.resourceDomains.equals(newDomains);
        INSTANCE.resourceDomains = Collections.unmodifiableSet(newDomains);

        boolean installedNow = ensureInstalled(minecraft);
        if ((installedNow || domainsChanged) && !newDomains.isEmpty()) {
            TACZLegacy.logger.info("Refreshing resources after gun pack sound bridge update (domains={})", newDomains);
            minecraft.refreshResources();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean ensureInstalled(Minecraft minecraft) {
        List<IResourcePack> defaultResourcePacks = getDefaultResourcePacks(minecraft);
        if (defaultResourcePacks.contains(INSTANCE)) {
            return false;
        }
        defaultResourcePacks.add(INSTANCE);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean removeInstalled(Minecraft minecraft) {
        return getDefaultResourcePacks(minecraft).remove(INSTANCE);
    }

    @SuppressWarnings("unchecked")
    private static List<IResourcePack> getDefaultResourcePacks(Minecraft minecraft) {
        if (!(minecraft instanceof MinecraftInvoker)) {
            throw new IllegalStateException("MinecraftInvoker mixin not applied; unable to access default resource packs safely");
        }
        return ((MinecraftInvoker) minecraft).tacz$getDefaultResourcePacks();
    }

    /**
     * 解析实际应读取的资源坐标：优先重定向到 tacz_sounds 目录，
     * 若该坐标不存在则回退原始 sounds 路径，以兼容仅提供 sounds 目录的有效枪包。
     */
    private ResourceLocation rewriteSoundPath(ResourceLocation location) {
        String path = location.getPath();
        if (path.startsWith("sounds/") && path.endsWith(".ogg")) {
            ResourceLocation redirected = new ResourceLocation(location.getNamespace(), "tacz_sounds/" + path.substring("sounds/".length()));
            if (TACZClientAssetManager.INSTANCE.hasPackAsset(redirected)) {
                return redirected;
            }
            return location;
        }
        return location;
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        location = rewriteSoundPath(location);
        InputStream stream = TACZClientAssetManager.INSTANCE.openPackAsset(location);
        if (stream == null) {
            throw new FileNotFoundException(location.toString());
        }
        // paulscode 的 CodecJOrbis 在持有 SoundSystem 全局锁的命令线程里同步 read()；
        // 直接返回文件流时，单次 read() 偶发阻塞就会冻结主线程（setListener 抢同一把锁）。
        // 这里把整文件读进内存返回 ByteArrayInputStream，read() 物理上不可能阻塞。
        // paulscode 的 loadSound 有 bufferMap 缓存解码结果，同一音效只首播时调一次本方法，故 readFully 成本极低。
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                baos.write(buffer, 0, read);
            }
            return new ByteArrayInputStream(baos.toByteArray());
        } finally {
            stream.close();
        }
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        String path = location.getPath();
        if (!path.endsWith(".ogg")) {
            return false;
        }
        location = rewriteSoundPath(location);
        return TACZClientAssetManager.INSTANCE.hasPackAsset(location);
    }

    @Override
    public Set<String> getResourceDomains() {
        return resourceDomains;
    }

    @Override
    @Nullable
    public <T extends IMetadataSection> T getPackMetadata(MetadataSerializer metadataSerializer, String metadataSectionName) {
        return null;
    }

    @Override
    public BufferedImage getPackImage() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public String getPackName() {
        return "TACZ Gun Pack Sounds";
    }
}
