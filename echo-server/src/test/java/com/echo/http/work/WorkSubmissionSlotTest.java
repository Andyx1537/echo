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
