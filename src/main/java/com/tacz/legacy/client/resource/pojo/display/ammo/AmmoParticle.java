package com.tacz.legacy.client.resource.pojo.display.ammo;

import com.google.gson.annotations.SerializedName;
import org.joml.Vector3f;

public class AmmoParticle {
    private static final Vector3f ZERO = new Vector3f(0, 0, 0);

    @SerializedName("name")
    private String name;

    @SerializedName("delta")
    private Vector3f delta = ZERO;

    @SerializedName("speed")
    private float speed = 0f;

    @SerializedName("life_time")
    private int lifeTime = 20;

    @SerializedName("count")
    private int count = 1;

    public String getName() {
        return name;
    }

    public Vector3f getDelta() {
        return delta;
    }

    public float getSpeed() {
        return speed;
    }

    public int getLifeTime() {
        return lifeTime;
    }

    public int getCount() {
        return count;
    }
}