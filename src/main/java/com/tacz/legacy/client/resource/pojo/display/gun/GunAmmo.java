package com.tacz.legacy.client.resource.pojo.display.gun;

import com.google.gson.annotations.SerializedName;
import com.tacz.legacy.client.resource.pojo.display.ammo.AmmoParticle;

import javax.annotation.Nullable;

public class GunAmmo {
    @Nullable
    @SerializedName("tracer_color")
    private String tracerColor = null;

    /**
     * 弹药粒子配置。上游用于子弹飞行/命中粒子；1.12.2 的 EnumParticleTypes
     * 与上游粒子注册名不一致，且当前移植层没有对应消费路径，字段仅解析保留。
     */
    @Nullable
    @SerializedName("particle")
    private AmmoParticle particle = null;

    @Nullable
    public String getTracerColor() {
        return tracerColor;
    }

    @Nullable
    public AmmoParticle getParticle() {
        return particle;
    }
}