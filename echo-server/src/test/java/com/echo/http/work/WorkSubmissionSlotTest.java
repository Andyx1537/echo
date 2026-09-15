package com.echo.http.work;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkSubmissionSlotTest {
    @Test
    void pendingOccupiesAndRejectedDoesNot() {
        assertThat(WorkSubmissionSlot.occupies(Work.Status.PENDING)).isTrue();
        assertThat(WorkSubmissionSlot.occupies("uploading")).isTrue();
        assertThat(WorkSubmissionSlot.occupies("submitting")).isTrue();
        assertThat(WorkSubmissionSlot.occupies(Work.Status.PUBLIC)).isFalse();
        assertThat(WorkSubmissionSlot.occupies(Work.Status.REJECTED)).isFalse();
        assertThat(WorkSubmissionSlot.occupies(Work.Status.TAKENDOWN)).isFalse();
        assertThat(WorkSubmissionSlot.occupies(Work.Status.APPEALING)).isFalse();
        assertThat(WorkSubmissionSlot.occupies(Work.Status.DELETED)).isFalse();
    }

    @Test
    void capabilityNamesTheBlockingWorkUntilTheSlotIsFree() {
        Work pending = new Work();
        pending.id = 9;
        pending.status = Work.Status.PENDING;
        Map<String, Object> blocked = WorkSubmissionSlot.capability(pending);
        assertThat(blocked).containsEntry("canSubmitWork", false)
                .containsEntry("blockingWorkId", "9")
                .containsEntry("blockingStatus", "pending")
                .containsEntry("nextAction", "wait");

        Work rejected = new Work();
        rejected.id = 9;
        rejected.status = Work.Status.REJECTED;
        assertThat(WorkSubmissionSlot.capability(rejected)).containsEntry("canSubmitWork", true)
                .containsEntry("nextAction", "none");
        assertThat(WorkSubmissionSlot.capability(null)).containsEntry("canSubmitWork", true);
    }

    @Test
    void memoryStoreKeepsOneOccupyingWorkAndReleasesAfterSoftDelete() {
        WorkStore store = new WorkStore(null);
        Work first = work(11, 7, Work.Status.PENDING, 10);
        Work second = work(12, 7, Work.Status.PUBLIC, 20);
        assertThat(store.insert(first)).isTrue();
        assertThat(store.insert(second)).isTrue();
        assertThat(store.occupyingWork(7).id).isEqualTo(11);
        assertThat(store.occupyingWork(8)).isNull();

        store.softDelete(11, 7, "author_delete", 30);
        assertThat(store.occupyingWork(7)).isNull();
    }

    @Test
    void concurrentPendingInsertsKeepOnlyOneSlot() throws Exception {
        WorkStore store = new WorkStore(null);
        Work first = work(21, 9, Work.Status.PENDING, 10);
        Work second = work(22, 9, Work.Status.PENDING, 11);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> store.insert(first));
            var b = pool.submit(() -> store.insert(second));
            boolean firstOk = a.get();
            boolean secondOk = b.get();
            assertThat(firstOk || secondOk).isTrue();
            assertThat(firstOk && secondOk).isFalse();
            assertThat(store.occupyingWork(9).id).isIn(21L, 22L);
            assertThat(store.insert(work(23, 9, Work.Status.PENDING, 12))).isFalse();
            assertThat(store.insert(work(24, 9, Work.Status.PUBLIC, 13))).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void resubmitDoesNotStealAnotherPendingSlot() {
        WorkStore store = new WorkStore(null);
        Work pending = work(31, 4, Work.Status.PENDING, 10);
        Work rejected = work(32, 4, Work.Status.REJECTED, 11);
        rejected.contentVersion = 2;
        assertThat(store.insert(pending)).isTrue();
        assertThat(store.insert(rejected)).isTrue();
        rejected.status = Work.Status.PENDING;
        assertThat(store.casResubmit(rejected, 2)).isFalse();
        assertThat(store.occupyingWork(4).id).isEqualTo(31);
        assertThat(store.byId(32).status).isEqualTo(Work.Status.REJECTED);
    }

    private static Work work(long id, long authorId, String status, long createdAt) {
        Work w = new Work();
        w.id = id;
        w.authorId = authorId;
        w.status = status;
        w.createdAt = createdAt;
        w.updatedAt = createdAt;
        return w;
    }
}
