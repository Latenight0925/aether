package dev.aether.modules.pest.helpers;

import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class PestDestroyerRuntime {
    volatile PestDestroyer.State state = PestDestroyer.State.IDLE;
    volatile boolean active = false;
    Entity currentTarget = null;
    final List<Entity> killedEntities = new CopyOnWriteArrayList<>();
    final Deque<Entity> pestTargetQueue = new ArrayDeque<>();
    final Set<Integer> accountedKilledPestEntityIds = ConcurrentHashMap.newKeySet();

    long stateEnteredAt = 0L;
    long lastVacuumUseAt = 0L;
    long lastPreRotateAt = 0L;
    long flyRetryAfterUnflyAt = 0L;
    long killVacuumHoldStartedAt = 0L;
    long killVacuumRetryPressAt = 0L;
    long killVacuumReleaseUntil = 0L;
    int stuckTicks = 0;
    int approachTicks = 0;

    int vacuumSlot = -1;
    float vacuumRange = 7.5f;

    int aotvSlot = -1;
    int aotvUseCount = 0;
    long aotvLastUseAt = 0L;
    long aotvNextUseAt = 0L;
    long aotvPostClickGraceUntil = 0L;
    long aotvPendingUseAt = 0L;
    double aotvLastUsePlayerX = Double.NaN;
    double aotvLastUsePlayerY = Double.NaN;
    double aotvLastUsePlayerZ = Double.NaN;
    boolean arrivedAtCurrentTargetViaAotv = false;
    volatile double aotvStartY = Double.NaN;
    long activatedAt = 0L;
    long lastRoofRescanAt = 0L;
    PestDestroyer.State roofAotvReturnState = null;

    int zeroPestTabTicks = 0;
    int targetWithoutSkullTicks = 0;

    final PestNavigationState navigation = new PestNavigationState();

    void beginRun(int detectedVacuumSlot, long now) {
        active = true;
        state = PestDestroyer.State.IDLE;
        stateEnteredAt = now;
        activatedAt = now;
        currentTarget = null;
        killedEntities.clear();
        pestTargetQueue.clear();
        accountedKilledPestEntityIds.clear();
        vacuumSlot = detectedVacuumSlot;
        vacuumRange = 7.5f;
        resetTransientState();
        navigation.resetForRun();
    }

    void stopRun() {
        active = false;
        state = PestDestroyer.State.IDLE;
        currentTarget = null;
        killedEntities.clear();
        pestTargetQueue.clear();
        accountedKilledPestEntityIds.clear();
        targetWithoutSkullTicks = 0;
        navigation.resetForRun();
        resetTransientState();
    }

    void resetAll() {
        stopRun();
        lastPreRotateAt = 0L;
    }

    void transitionTo(PestDestroyer.State newState, long now) {
        state = newState;
        stateEnteredAt = now;
        stuckTicks = 0;
        approachTicks = 0;
        flyRetryAfterUnflyAt = 0L;
        if (newState == PestDestroyer.State.CHECK_NEXT
                || newState == PestDestroyer.State.FINISH
                || newState == PestDestroyer.State.IDLE) {
            arrivedAtCurrentTargetViaAotv = false;
        }
        if (newState != PestDestroyer.State.GET_LOCATION) {
            navigation.isCapturingFirework = false;
            navigation.fireworkCaptureStartedAt = 0L;
        }
        if (newState != PestDestroyer.State.AOTV_BETWEEN_PESTS) {
            aotvLastUseAt = 0L;
            aotvNextUseAt = 0L;
            aotvPostClickGraceUntil = 0L;
            aotvPendingUseAt = 0L;
            aotvLastUsePlayerX = Double.NaN;
            aotvLastUsePlayerY = Double.NaN;
            aotvLastUsePlayerZ = Double.NaN;
        }
        if (newState != PestDestroyer.State.KILL_PEST) {
            targetWithoutSkullTicks = 0;
            lastPreRotateAt = 0L;
            resetKillVacuumRetry();
        }
    }

    void resetKillVacuumRetry() {
        killVacuumHoldStartedAt = 0L;
        killVacuumRetryPressAt = 0L;
        killVacuumReleaseUntil = 0L;
    }

    private void resetTransientState() {
        stuckTicks = 0;
        approachTicks = 0;
        zeroPestTabTicks = 0;
        targetWithoutSkullTicks = 0;
        lastVacuumUseAt = 0L;
        flyRetryAfterUnflyAt = 0L;
        killVacuumHoldStartedAt = 0L;
        killVacuumRetryPressAt = 0L;
        killVacuumReleaseUntil = 0L;
        aotvSlot = -1;
        aotvUseCount = 0;
        aotvLastUseAt = 0L;
        aotvNextUseAt = 0L;
        aotvPostClickGraceUntil = 0L;
        aotvPendingUseAt = 0L;
        aotvLastUsePlayerX = Double.NaN;
        aotvLastUsePlayerY = Double.NaN;
        aotvLastUsePlayerZ = Double.NaN;
        arrivedAtCurrentTargetViaAotv = false;
        aotvStartY = Double.NaN;
        lastRoofRescanAt = 0L;
        roofAotvReturnState = null;
    }
}
