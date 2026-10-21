package com.tacz.legacy.client.resource.pojo.display.gun;

import com.google.gson.annotations.SerializedName;

/**
 * 伤害数字的统计方式（display JSON 的 {@code damage_style} 字段）。
 * 与上游 TACZ DamageStyle 保持一致。
 * <ul>
 *   <li>{@link #TOTAL}：显示一颗弹丸命中的总伤害（霰弹枪聚合）；</li>
 *   <li>{@link #PER_PROJECTILE}：按单发弹丸显示伤害（上游默认值）。</li>
 * </ul>
 */
public enum DamageStyle {
    @SerializedName("total")
    TOTAL,
    @SerializedName("per_projectile")
    PER_PROJECTILE
}
