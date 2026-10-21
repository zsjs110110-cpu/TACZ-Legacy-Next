package com.tacz.legacy.api.client.animation.statemachine;

import com.tacz.legacy.api.client.animation.AnimationController;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AnimationStateMachine<T extends AnimationStateContext> {
    private List<AnimationState<T>> currentStates;
    protected T context;
    private Supplier<Iterable<? extends AnimationState<T>>> statesSupplier;
    private final @Nonnull AnimationController animationController;
    protected long exitingTime = -1;

    public AnimationStateMachine(@Nonnull AnimationController animationController) {
        this.animationController = Objects.requireNonNull(animationController);
    }

    public void update() {
        if (context != null && currentStates != null) {
            for (AnimationState<T> state : currentStates) {
                state.update(context);
            }
        }
        animationController.update();
    }

    public void visualUpdate() {
        if (context != null && currentStates != null) {
            for (AnimationState<T> state : currentStates) {
                state.update(context);
            }
        }
        animationController.updateSoundOnly();
    }

    /**
     * @return 本次输入是否至少让一个状态发生了切换；未初始化或全部 transition 返回 null 时为 false
     */
    public boolean trigger(String condition) {
        if (context == null || currentStates == null) {
            return false;
        }
        boolean changed = false;
        ListIterator<AnimationState<T>> iterator = currentStates.listIterator();
        while (iterator.hasNext()) {
            AnimationState<T> state = iterator.next();
            AnimationState<T> nextState = state.transition(context, condition);
            if (nextState != null) {
                state.exitAction(context);
                iterator.set(nextState);
                nextState.entryAction(context);
                changed = true;
            }
        }
        return changed;
    }

    public void initialize() {
        if (context == null) {
            throw new IllegalStateException("Context must not be null before initialization");
        }
        if (currentStates != null) {
            throw new IllegalStateException("State machine is already initialized");
        }
        this.currentStates = new LinkedList<>();
        if (statesSupplier != null) {
            Iterable<? extends AnimationState<T>> states = statesSupplier.get();
            if (states != null) {
                for (AnimationState<T> state : states) {
                    currentStates.add(state);
                    state.entryAction(context);
                }
            }
        }
    }

    public void exit() {
        checkNullPointer();
        for (AnimationState<T> state : currentStates) {
            state.exitAction(context);
        }
        this.currentStates = null;
    }

    public void setExitingTime(long keepTime) {
        this.exitingTime = System.currentTimeMillis() + keepTime;
    }

    public long getExitingTime() {
        return exitingTime;
    }

    public @Nonnull AnimationController getAnimationController() {
        return animationController;
    }

    public boolean isInitialized() {
        return currentStates != null;
    }

    public @Nullable T getContext() {
        return context;
    }

    public void processContextIfExist(Consumer<T> consumer) {
        if (context != null) {
            consumer.accept(context);
        }
    }

    public void setContext(@Nonnull T context) {
        AnimationStateMachine<?> sm = context.getStateMachine();
        if (sm != null && sm != this) {
            throw new IllegalStateException("Context is already used");
        }
        if (currentStates != null) {
            throw new IllegalStateException("State machine is already initialized, call exit() first");
        }
        if (this.context != null) {
            this.context.setStateMachine(null);
        }
        context.setStateMachine(this);
        this.context = context;
    }

    public void setStatesSupplier(Supplier<Iterable<? extends AnimationState<T>>> statesSupplier) {
        this.statesSupplier = statesSupplier;
    }

    private void checkNullPointer() {
        if (context == null) {
            throw new IllegalStateException("Context has not been initialized");
        }
        if (currentStates == null) {
            throw new IllegalStateException("State machine has not been initialized");
        }
    }
}
