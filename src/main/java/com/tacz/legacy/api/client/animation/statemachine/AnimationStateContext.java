package com.tacz.legacy.api.client.animation.statemachine;

import com.tacz.legacy.api.client.animation.AnimationController;
import com.tacz.legacy.api.client.animation.DiscreteTrackArray;
import com.tacz.legacy.api.client.animation.ObjectAnimation;
import com.tacz.legacy.api.client.animation.ObjectAnimationRunner;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class AnimationStateContext {
    private boolean shouldHideCrossHair = false;
    private @Nullable AnimationStateMachine<?> stateMachine;
    private final DiscreteTrackArray trackArray = new DiscreteTrackArray();

    public @Nullable AnimationStateMachine<?> getStateMachine() {
        return stateMachine;
    }

    public DiscreteTrackArray getTrackArray() {
        return trackArray;
    }

    public int addTrackLine() {
        checkTrackArray();
        return getTrackArray().addTrackLine();
    }

    public void ensureTrackLineSize(int size) {
        checkTrackArray();
        getTrackArray().ensureCapacity(size);
    }

    public int getTrackLineSize() {
        checkTrackArray();
        return getTrackArray().getTrackLineSize();
    }

    public int assignNewTrack(int index) {
        checkTrackArray();
        return getTrackArray().assignNewTrack(index);
    }

    public int findIdleTrack(int index, boolean interruptHolding) {
        AnimationStateMachine<?> sm = checkStateMachine();
        checkTrackArray();
        DiscreteTrackArray ta = getTrackArray();
        List<Integer> trackList = ta.getByIndex(index);
        AnimationController controller = sm.getAnimationController();
        for (int track : trackList) {
            ObjectAnimationRunner animation = controller.getAnimation(track);
            if (animation == null || animation.isStopped() || (interruptHolding && animation.isHolding())) {
                return track;
            }
        }
        return ta.assignNewTrack(index);
    }

    public void ensureTracksAmount(int index, int amount) {
        checkTrackArray();
        getTrackArray().ensureTrackAmount(index, amount);
    }

    public int getTrack(int trackLineIndex, int trackIndex) {
        checkTrackArray();
        DiscreteTrackArray ta = getTrackArray();
        if (trackLineIndex >= ta.getTrackLineSize()) {
            return -1;
        }
        List<Integer> tracks = ta.getByIndex(trackLineIndex);
        if (trackIndex >= tracks.size()) {
            return -1;
        }
        return tracks.get(trackIndex);
    }

    public int getAsSingletonTrack(int index) {
        checkTrackArray();
        DiscreteTrackArray ta = getTrackArray();
        List<Integer> trackList = ta.getByIndex(index);
        if (trackList.isEmpty()) {
            return ta.assignNewTrack(index);
        } else {
            return trackList.get(0);
        }
    }

    public void runAnimation(String name, int track, boolean blending, int playType, float transitionTime) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimation.PlayType pt = ObjectAnimation.PlayType.values()[playType];
        sm.getAnimationController().runAnimation(track, name, pt, transitionTime);
        sm.getAnimationController().setBlending(track, blending);
    }

    public void stopAnimation(int track) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.stop();
        }
    }

    public void holdAnimation(int track) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.hold();
        }
    }

    public void pauseAnimation(int track) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.pause();
        }
    }

    public void resumeAnimation(int track) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.run();
        }
    }

    public void setAnimationProgress(int track, float progress, boolean normalization) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            if (runner.isRunning() || runner.isPausing()) {
                if (normalization) {
                    progress = runner.getAnimation().getMaxEndTimeS() * progress;
                }
                runner.setProgressNs((long) (progress * 1e9));
                return;
            }
            ObjectAnimationRunner runner1 = runner.getTransitionTo();
            if (runner1 != null) {
                if (normalization) {
                    progress = runner1.getAnimation().getMaxEndTimeS() * progress;
                }
                runner1.setProgressNs((long) (progress * 1e9));
            }
        }
    }

    public void adjustAnimationProgress(int track, float progress, boolean normalization) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            if (runner.isRunning()) {
                if (normalization) {
                    progress = runner.getAnimation().getMaxEndTimeS() * progress;
                }
                runner.setProgressNs(runner.getProgressNs() + (long) (progress * 1e9));
                return;
            }
            ObjectAnimationRunner runner1 = runner.getTransitionTo();
            if (runner1 != null) {
                if (normalization) {
                    progress = runner1.getAnimation().getMaxEndTimeS() * progress;
                }
                runner1.setProgressNs(runner1.getProgressNs() + (long) (progress * 1e9));
            }
        }
    }

    public boolean isHolding(int track) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            return (runner.getTransitionTo() != null ? runner.getTransitionTo().isHolding() : runner.isHolding());
        }
        return false;
    }

    public boolean isStopped(int track) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            return (runner.getTransitionTo() != null ? runner.getTransitionTo().isStopped() : runner.isStopped());
        }
        return true;
    }

    public boolean isPause(int track) {
        AnimationStateMachine<?> sm = checkStateMachine();
        ObjectAnimationRunner runner = sm.getAnimationController().getAnimation(track);
        if (runner != null) {
            return (runner.getTransitionTo() != null ? !runner.getTransitionTo().isPausing() : !runner.isPausing());
        }
        return false;
    }

    public boolean hasAnimationPrototype(String name) {
        AnimationStateMachine<?> sm = checkStateMachine();
        return sm.getAnimationController().containPrototype(name);
    }

    public void trigger(String input) {
        AnimationStateMachine<?> sm = checkStateMachine();
        sm.trigger(input);
    }

    public boolean shouldHideCrossHair() {
        return shouldHideCrossHair;
    }

    public void setShouldHideCrossHair(boolean shouldHideCrossHair) {
        this.shouldHideCrossHair = shouldHideCrossHair;
    }

    void setStateMachine(@Nullable AnimationStateMachine<?> stateMachine) {
        if (this.stateMachine != null) {
            this.stateMachine.getAnimationController().setUpdatingTrackArray(null);
        }
        if (stateMachine != null) {
            stateMachine.getAnimationController().setUpdatingTrackArray(trackArray);
        }
        this.stateMachine = stateMachine;
    }

    private void checkTrackArray() {
        if (stateMachine != null && stateMachine.getAnimationController().getUpdatingTrackArray() != trackArray) {
            throw new TrackArrayMismatchException();
        }
    }

    @Nonnull
    private AnimationStateMachine<?> checkStateMachine() {
        if (this.stateMachine == null) {
            throw new IllegalStateException("This context has not been bound to a state machine.");
        }
        return this.stateMachine;
    }
}
