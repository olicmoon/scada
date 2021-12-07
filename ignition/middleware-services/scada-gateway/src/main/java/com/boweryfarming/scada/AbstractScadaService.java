package com.boweryfarming.scada;

import com.inductiveautomation.ignition.gateway.opcua.server.api.ManagedDevice;

import org.slf4j.Logger;

public abstract class AbstractScadaService {
    protected ServiceContext context;
    protected Logger logger;
    protected ManagedDevice managedDevice;

    boolean running;

    public AbstractScadaService(ServiceContext context) {
        this.context = context;
        this.managedDevice = context.getManagedDevice();
        this.logger = context.getLogger();
        this.running = false;
    }

    public String getName() {
        return context.getManagedDevice().getName();
    }

    public void isRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return this.running;
    }

    public abstract void onStart();  // TODO: throw exception when failed to start
    public abstract void onDestroy();
}
