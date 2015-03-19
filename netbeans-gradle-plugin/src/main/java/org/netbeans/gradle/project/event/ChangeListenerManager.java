package org.netbeans.gradle.project.event;

import org.jtrim.event.ListenerRegistry;

public interface ChangeListenerManager extends ListenerRegistry<Runnable> {
    public void fireEventually();
}
