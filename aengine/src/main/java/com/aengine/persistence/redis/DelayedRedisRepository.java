package com.aengine.persistence.redis;


import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.DelaySaveRepository;
import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.db.DBManager;

import java.util.List;

public class DelayedRedisRepository<T extends AbstractEntity> extends CachedRedisRepository<T>{

    private final DelaySaveRepository<T> delaySaveRepository;

    protected DelayedRedisRepository() {
        CRepository repository = getAnnotation();
        delaySaveRepository = new DelaySaveRepository<>(this,
                redis.getPool(),
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
        for (T entity : entities) {
            delaySaveRepository.removeFromQueue(entity);
            super.remove(entity);
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
            updateCacheIndex(entity);
            delaySaveRepository.addToQueue(entity);
        }
    }
}
