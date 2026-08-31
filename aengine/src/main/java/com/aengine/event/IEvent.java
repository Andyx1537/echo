package com.aengine.event;

/**
 */
public interface IEvent {
    
    public default String getIdenty() 
    {
        return null;
    }
}
