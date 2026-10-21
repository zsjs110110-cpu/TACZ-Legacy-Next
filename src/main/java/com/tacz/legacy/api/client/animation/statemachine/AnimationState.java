package com.tacz.legacy.api.client.animation.statemachine;

public interface AnimationState<T extends AnimationStateContext> {
    void update(T context);
    void entryAction(T context);
    void exitAction(T context);
    AnimationState<T> transition(T context, String condition);
}
