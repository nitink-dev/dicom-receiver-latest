package com.eh.digitalpathology.dicomreceiver.service.smb;


import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class RemoteWatcherRefreshListener implements ApplicationListener< RefreshScopeRefreshedEvent > {


    private final RemoteDirectoryWatcherLifeCycle lifecycle;

    public RemoteWatcherRefreshListener(RemoteDirectoryWatcherLifeCycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void onApplicationEvent(RefreshScopeRefreshedEvent event) {
        lifecycle.stop();
        lifecycle.start();
    }

}

