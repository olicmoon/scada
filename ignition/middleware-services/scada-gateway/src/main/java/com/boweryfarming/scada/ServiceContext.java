package com.boweryfarming.scada;

import java.util.HashMap;
import java.util.Map;

import com.boweryfarming.scada.command.CommandService;
import com.boweryfarming.scada.conveyor.BinConveyorService;
import com.boweryfarming.scada.simulator.SimulatorService;
import com.boweryfarming.scada.tags.PublicTag;
import com.boweryfarming.scada.tags.PublicTagManagerService;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.ManagedDevice;

import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceContext {
    private final Logger logger = LoggerFactory.getLogger(BoweryScadaDevice.class);

    private final DeviceContext deviceContext;
    private final UaNodeContext nodeContext;
    private final UaNodeManager nodeManager;
    private final ManagedDevice managedDevice;
    private Map<String, AbstractScadaService> services = new HashMap<String, AbstractScadaService>();

    public static final String SIMULATOR_SERVICE = "com.boweryfarming.service.simulator";
    public static final String COMMAND_SERVICE = "com.boweryfarming.service.command";
    public static final String BINCONVEYANCE_SERVICE = "com.boweryfarming.service.binconveyance";
    public static final String PUBTAG_MANAGER_SERVICE = "com.boweryfarming.service.pubtagmanager";

    public void boot() {
        // Manually run services that need to start early
        startService(COMMAND_SERVICE);
        startService(PUBTAG_MANAGER_SERVICE);

        startServices();
    }

    public void shutdown() {
        stopServices();
    }

    public ServiceContext(ManagedDevice managedDevice,
            DeviceContext deviceContext,
            UaNodeContext nodeContext,
            UaNodeManager nodeManager) {
        this.deviceContext = deviceContext;
        this.managedDevice = managedDevice;
        this.nodeContext = nodeContext;
        this.nodeManager = nodeManager;

        // TODO: start command service and simulator services only for test environment
        services.put(COMMAND_SERVICE, new CommandService(this));
        services.put(PUBTAG_MANAGER_SERVICE, new PublicTagManagerService(this));
        services.put(BINCONVEYANCE_SERVICE, new BinConveyorService(this));

        services.put(SIMULATOR_SERVICE, new SimulatorService(this));
    }

    private void startServices() {
        for (Map.Entry<String, AbstractScadaService> entry : services.entrySet()) {
            String name = entry.getKey();
            AbstractScadaService service = entry.getValue();
            if (service.isRunning()) {
                continue;
            }

            startService(name);
        }
    }

    private void startService(String name) {
        logger.info("Service: " + name + " Starting");

        if (!services.containsKey(name)) {
            logger.error(String.format("Failed to find service %s", name));
            return;
        }

        AbstractScadaService service = services.get(name);
        if (service.isRunning()) {
            logger.warn(String.format("Service %s already running", name));
            return;
        }

        service.onStart();
        service.isRunning(true);
        logger.info("Service: " + name + " Started");
    }

    private void stopServices() {
        for (Map.Entry<String, AbstractScadaService> entry : services.entrySet()) {
            AbstractScadaService service = entry.getValue();
            if (service.isRunning()) {
                service.onDestroy();
                service.isRunning(false);
            }
        }
    }

    public AbstractScadaService getService(String name) {
        if (!services.containsKey(name)) {
            logger.warn(String.format("Failed to find service %s", name));
            return null;
        }

        return services.get(name);
    }

    public Logger getLogger() {
        return logger;
    }

    public DeviceContext getDeviceContext() {
        return deviceContext;
    }

    public ManagedDevice getManagedDevice() {
        return managedDevice;
    }

    public UaNodeManager getNodeManager() {
        return nodeManager;
    }

    public UaNodeContext getNodeContext() {
        return nodeContext;
    }

    public GatewayContext getGatewayContext() {
        return deviceContext.getGatewayContext();
    }

    public PublicTag getPublicTag(String tagPath) throws RuntimeException {
        PublicTagManagerService service = (PublicTagManagerService) getService(PUBTAG_MANAGER_SERVICE);
        PublicTag tag = service.getPublicTag(tagPath);
        if (tag == null) {
            throw new RuntimeException("Undefined tag:" + tagPath);
        }

        return tag;
    }
}
