package com.tacz.legacy.api.client.animation.statemachine;

import com.tacz.legacy.api.client.animation.AnimationController;

import java.util.function.Consumer;

public class LuaAnimationStateMachine<T extends AnimationStateContext> extends AnimationStateMachine<T> {
    Consumer<T> initializeFunc;
    Consumer<T> exitFunc;

    LuaAnimationStateMachine(AnimationController animationController) {
        super(animationController);
    }

    @Override
    public void initialize() {
        if (this.initializeFunc != null) {
            this.initializeFunc.accept(this.context);
        }
        super.initialize();
    }

    @Override
    public void exit() {
        if (this.exitFunc != null) {
            this.exitFunc.accept(this.context);
        }
        super.exit();
    }
}
