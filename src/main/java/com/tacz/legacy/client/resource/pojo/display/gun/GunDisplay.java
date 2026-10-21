package com.tacz.legacy.client.resource.pojo.display.gun;

import com.google.common.collect.Maps;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.tacz.legacy.api.item.gun.FireMode;
import com.tacz.legacy.client.resource.pojo.display.LaserConfig;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Gun display POJO — deserialized from gun pack display JSON.
 * Port of upstream TACZ GunDisplay with fields needed for rendering.
 * <p>
 * Texture path conversion (FileToIdConverter equivalent)
 * is handled by {@link #init()} post-deserialization.
 */
public class GunDisplay {
    @SerializedName("model_type")
    private String modelType = "default";
    @SerializedName("model")
    private ResourceLocation modelLocation;
    @SerializedName("texture")
    private ResourceLocation modelTexture;
    @SerializedName("iron_zoom")
    private float ironZoom = 1.2f;
    @SerializedName("zoom_model_fov")
    private float zoomModelFov = 70f;
    @Nullable
    @SerializedName("lod")
    private GunLod gunLod;
    @Nullable
    @SerializedName("hud")
    private ResourceLocation hudTextureLocation;
    @Nullable
    @SerializedName("hud_empty")
    private ResourceLocation hudEmptyTextureLocation;
    @Nullable
    @SerializedName("slot")
    private ResourceLocation slotTextureLocation;
    @Nullable
    @SerializedName("third_person_animation")
    private String thirdPersonAnimation;
    @Nullable
    @SerializedName("animation")
    private ResourceLocation animationLocation;
    @Nullable
    @SerializedName("state_machine")
    private ResourceLocation stateMachineLocation;
    @Nullable
    @SerializedName("state_machine_param")
    private Map<String, Object> stateMachineParam;
    @Nullable
    @SerializedName("use_default_animation")
    private DefaultAnimationType defaultAnimationType;
    @Nullable
    @SerializedName("default_animation")
    private ResourceLocation defaultAnimation;
    // B10（X-51）：1.12.2 没有 PlayerAnimator（该模组最低 1.16.4），这两个字段仅保留
    // display JSON 的解析兼容（README 已声明“可解析但不生效”），没有任何渲染/玩法消费者；
    // 解析测试也覆盖了它们，因此不按“零调用”删除，避免破坏枪包字段契约。
    @Nullable
    @SerializedName("player_animator_3rd")
    private ResourceLocation playerAnimator3rd;
    @SerializedName("3rd_fixed_hand")
    private boolean playerAnimator3rdFixedHand = false;
    @Nullable
    @SerializedName("sounds")
    private Map<String, ResourceLocation> sounds;
    @Nullable
    @SerializedName("transform")
    private GunTransform transform;
    @Nullable
    @SerializedName("shell")
    private ShellEjection shellEjection;
    @Nullable
    @SerializedName("ammo")
    private GunAmmo gunAmmo;
    @Nullable
    @SerializedName("muzzle_flash")
    private MuzzleFlash muzzleFlash;
    @Nullable
    @SerializedName("laser")
    private LaserConfig laserConfig;
    @SerializedName("show_crosshair")
    private boolean showCrosshair = false;
    @SerializedName("ammo_count_style")
    private AmmoCountStyle ammoCountStyle = AmmoCountStyle.NORMAL;
    /**
     * 伤害数字的统计方式（total / per_projectile）。上游用于伤害数字 HUD；
     * 1.12.2 移植层没有伤害数字渲染路径，字段仅解析保留，语义默认为 PER_PROJECTILE。
     */
    @SerializedName("damage_style")
    private DamageStyle damageStyle = DamageStyle.PER_PROJECTILE;
    @SerializedName("text_show")
    private Map<String, TextShow> textShows = Maps.newHashMap();
    /**
     * 进入世界时需要预加载的音效键（{@code sounds} 中的键名）。
     * 1.12.2 的声音后端在客户端资产重载时预解码整个 manifest，
     * 这里列出的键会在 {@code TACZClientAssetManager} 中补登记，确保不被漏掉。
     */
    @Nullable
    @SerializedName("preload_sounds")
    private List<String> preloadSounds;
    /**
     * 副手枪械显示参数。上游是 {@code {pos, rotate, scale}} 对象；
     * 本移植额外接受布尔简写 {@code false} 用于关闭副手渲染（见 LayerGunShow#isVisible）。
     */
    @SerializedName("offhand_show")
    private LayerGunShow offhandShow = new LayerGunShow();
    /**
     * 快捷栏枪械显示参数（键为快捷栏索引）。上游未配置时不显示快捷栏 3D 枪械；
     * 1.12.2 没有这条 HUD 3D 图层渲染路径，字段仅解析保留。
     */
    @Nullable
    @SerializedName("hotbar_show")
    private Map<String, LayerGunShow> hotbarShow;
    /**
     * 手柄震动参数（按开火模式索引）。1.12.2 没有手柄震动 API，字段仅解析保留。
     */
    @JsonAdapter(ControllableData.MapDeserializer.class)
    @SerializedName("controllable")
    private EnumMap<FireMode, ControllableData> controllableData = Maps.newEnumMap(FireMode.class);
    /**
     * 是否使用半透明混合模式渲染枪模型（上游 1.1.8 起支持）。
     */
    @SerializedName("enable_transparency")
    private boolean enableTransparency = false;

    public String getModelType() {
        return modelType;
    }

    public ResourceLocation getModelLocation() {
        return modelLocation;
    }

    public ResourceLocation getModelTexture() {
        return modelTexture;
    }

    @Nullable
    public GunLod getGunLod() {
        return gunLod;
    }

    @Nullable
    public ResourceLocation getHudTextureLocation() {
        return hudTextureLocation;
    }

    @Nullable
    public ResourceLocation getHudEmptyTextureLocation() {
        return hudEmptyTextureLocation;
    }

    @Nullable
    public ResourceLocation getSlotTextureLocation() {
        return slotTextureLocation;
    }

    @Nullable
    public ResourceLocation getAnimationLocation() {
        return animationLocation;
    }

    @Nullable
    public ResourceLocation getStateMachineLocation() {
        return stateMachineLocation;
    }

    @Nullable
    public Map<String, Object> getStateMachineParam() {
        return stateMachineParam;
    }

    @Nullable
    public DefaultAnimationType getDefaultAnimationType() {
        return defaultAnimationType;
    }

    @Nullable
    public ResourceLocation getDefaultAnimation() {
        return defaultAnimation;
    }

    @Nullable
    public ResourceLocation getPlayerAnimator3rd() {
        return playerAnimator3rd;
    }

    public boolean is3rdFixedHand() {
        return playerAnimator3rdFixedHand;
    }

    @Nullable
    public String getThirdPersonAnimation() {
        return thirdPersonAnimation;
    }

    @Nullable
    public Map<String, ResourceLocation> getSounds() {
        return sounds;
    }

    @Nullable
    public List<String> getPreloadSounds() {
        return preloadSounds;
    }

    @Nullable
    public GunTransform getTransform() {
        return transform;
    }

    @Nullable
    public ShellEjection getShellEjection() {
        return shellEjection;
    }

    @Nullable
    public GunAmmo getGunAmmo() {
        return gunAmmo;
    }

    @Nullable
    public MuzzleFlash getMuzzleFlash() {
        return muzzleFlash;
    }

    /**
     * 审计 B8-1：Gson 反射遇到 {@code "offhand_show": null} 会用 null 覆盖字段初始值，
     * 而 {@code TACZGunItemRenderer} 的 TEISR 消费点直接读 {@code display.offhandShow.isVisible}
     * （Kotlin 侧是平台类型，没有编译期空检查，渲染入口也没有 try/catch）。
     * 一个第三方 display 写 null 就会崩客户端渲染线程 —— 这里必须兜底，不能只靠解析侧。
     */
    public LayerGunShow getOffhandShow() {
        return offhandShow != null ? offhandShow : new LayerGunShow();
    }

    @Nullable
    public Map<String, LayerGunShow> getHotbarShow() {
        return hotbarShow;
    }

    public float getIronZoom() {
        return ironZoom;
    }

    public float getZoomModelFov() {
        return zoomModelFov;
    }

    public boolean isShowCrosshair() {
        return showCrosshair;
    }

    public AmmoCountStyle getAmmoCountStyle() {
        return ammoCountStyle;
    }

    public DamageStyle getDamageStyle() {
        // 同 getOffhandShow：显式写 "damage_style": null 时 Gson 会覆盖初始值，兜底回默认档
        return damageStyle != null ? damageStyle : DamageStyle.PER_PROJECTILE;
    }

    @Nullable
    public LaserConfig getLaserConfig() {
        return laserConfig;
    }

    public Map<String, TextShow> getTextShows() {
        // 同 getOffhandShow：显式 null 会被 Gson 覆盖成 null，消费方（HUD 文本）不该猜
        return textShows != null ? textShows : Maps.newHashMap();
    }

    public EnumMap<FireMode, ControllableData> getControllableData() {
        // 同 getOffhandShow：显式 "controllable": null 时兜底成空表
        return controllableData != null ? controllableData : Maps.newEnumMap(FireMode.class);
    }

    public boolean enablesTransparency() {
        return enableTransparency;
    }

    /**
     * Post-deserialization texture path conversion.
     * Converts short texture names (e.g. {@code tacz:gun/uv/ak47})
     * to full resource paths (e.g. {@code tacz:textures/gun/uv/ak47.png}).
     * Equivalent to upstream's FileToIdConverter("textures", ".png").idToFile().
     */
    public void init() {
        if (modelTexture != null) {
            modelTexture = expandTexturePath(modelTexture);
        }
        if (hudTextureLocation != null) {
            hudTextureLocation = expandTexturePath(hudTextureLocation);
        }
        if (hudEmptyTextureLocation != null) {
            hudEmptyTextureLocation = expandTexturePath(hudEmptyTextureLocation);
        }
        if (slotTextureLocation != null) {
            slotTextureLocation = expandTexturePath(slotTextureLocation);
        }
        if (gunLod != null && gunLod.getModelTexture() != null) {
            gunLod.setModelTexture(expandTexturePath(gunLod.getModelTexture()));
        }
        if (muzzleFlash != null && muzzleFlash.texture != null) {
            muzzleFlash.texture = expandTexturePath(muzzleFlash.texture);
        }
    }

    /**
     * Expands a short texture ID (e.g. {@code tacz:gun/uv/ak47}) to the full
     * resource path ({@code tacz:textures/gun/uv/ak47.png}).
     * Replicates upstream's FileToIdConverter("textures", ".png").idToFile().
     */
    private static ResourceLocation expandTexturePath(ResourceLocation shortId) {
        return new ResourceLocation(shortId.getNamespace(), "textures/" + shortId.getPath() + ".png");
    }
}
