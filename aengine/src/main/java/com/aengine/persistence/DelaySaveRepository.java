package com.aengine.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 延迟保存的仓储
 *
 *
 */
public class DelaySaveRepository<T extends AbstractEntity> {

    private final int batchSize;

    private final int delay;

    private final ConcurrentMap<T, Long> changed = new ConcurrentHashMap<>();

    private final IRepository<T> repository;

    private static final Logger log = LoggerFactory.getLogger(DelaySaveRepository.class);

    private class DelayTask implements Runnable {

        public void run() {
            delaySave(false);
        }

        @Override
        public String toString() {
            return this.getClass().toString() + "$" + this.getClass().getSimpleName();
        }
    }

    public DelaySaveRepository(IRepository<T> repository, ScheduledExecutorService scheduledExecutorService, int batchSize, int interval, int delay) {
        this.repository = repository;
        this.batchSize = batchSize;
        this.delay = delay;
        scheduledExecutorService.scheduleWithFixedDelay(new DelayTask(), interval, interval, TimeUnit.SECONDS);
    }

    public void removeFromQueue(T entity) {
        changed.remove(entity);
    }

    public void addToQueue(T entity) {
        changed.putIfAbsent(entity, System.currentTimeMillis());
    }

    // 仅判定是否需要落库，绝不在此处删除脏标记（删除必须在落库成功之后）
    private boolean isNeedSave(Long ts, long delay, boolean force) {
        if (ts == null) {
            return false;
        }
        if (!force) {
            return ts + delay < System.currentTimeMillis();
        } else {
            return ts != 0;
        }
    }

    public synchronized void delaySave(boolean force) {
        List<T> save = new ArrayList<>();
        // 记录入队时的时间戳快照，落库成功后据此条件删除：
        // 若落库期间实体被重新置脏（时间戳变化），则保留脏标记，下个周期再存，避免丢失更新。
        Map<T, Long> snapshot = new HashMap<>();
        for (Map.Entry<T, Long> entry : changed.entrySet()) {
            T entity = entry.getKey();
            if (isNeedSave(entry.getValue(), delay * 1000L, force)) {
                save.add(entity);
                snapshot.put(entity, entry.getValue());
            }
            if (save.size() >= batchSize) {
                flush(save, snapshot);
                save = new ArrayList<>();
                snapshot = new HashMap<>();
            }
        }
        if (save.size() > 0) {
            flush(save, snapshot);
        }
    }

    private void flush(List<T> save, Map<T, Long> snapshot) {
        try {
            repository.forceSave(save);
        } catch (Exception e) {
            // 落库失败：保留脏标记，下个周期重试，绝不删除
            log.error("延迟保存失败，保留脏标记下次重试", e);
            return;
        }
        // 仅落库成功后清除脏标记，且只删除时间戳未变化（期间未被重新置脏）的条目
        for (T entity : save) {
            changed.remove(entity, snapshot.get(entity));
        }
    }
}
