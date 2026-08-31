package com.aengine.persistence.db;


import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.DelaySaveRepository;
import com.aengine.persistence.annotation.CRepository;

import java.util.List;

/**
 */
public class DelayedJDBCRepository<T extends AbstractEntity> extends CachedJDBCRepository<T> {

    private final DelaySaveRepository<T> delaySaveRepository;

    protected DelayedJDBCRepository() {
        CRepository repository = getAnnotation();

        delaySaveRepository = new DelaySaveRepository<>(this,
                this.pool,
                repository.batch(),
                repository.interval(),
                repository.delay());

        DBManager.getInstance().addHook(() -> delaySaveRepository.delaySave(true));
    }

    @Override
    public void remove(T entity) {
        delaySaveRepository.removeFromQueue(entity);
        super.remove(entity);
    }

    @Override
    public void remove(List<T> entities) {
        super.remove(entities);
        for (T entity : entities) {
            delaySaveRepository.removeFromQueue(entity);
        }
    }

    @Override
    public void save(T entity) {
        updateCacheIndex(entity);
        delaySaveRepository.addToQueue(entity);
    }

    @Override
    public void save(List<T> entities) {
        for (T entity : entities) {
            save(entity);
        }
    }
}
