package com.boweryfarming.scada;

import java.util.List;

import javax.annotation.Nonnull;

import com.boweryfarming.scada.settings.BoweryScadaDeviceSettings;
import com.google.gson.Gson;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceType;
import com.inductiveautomation.ignition.gateway.opcua.server.api.ManagedDevice;

import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;

public class BoweryScadaDevice extends ManagedDevice {
    private final ServiceContext serviceContext;

    private final DeviceContext deviceContext;
    private final SubscriptionModel subscriptionModel;
    private final BoweryScadaDeviceSettings settings;

    Gson gson = new Gson();

    public BoweryScadaDevice (DeviceType deviceType,
            DeviceContext deviceContext,
            BoweryScadaDeviceSettings settings) {
        super(deviceType, deviceContext);

        this.deviceContext = deviceContext;
        this.settings = settings;

        subscriptionModel = new SubscriptionModel(deviceContext.getServer(), this);

        getLifecycleManager().addStartupTask(this::onStartup);
        getLifecycleManager().addShutdownTask(this::onShutdown);

        serviceContext = new ServiceContext(this, deviceContext, getNodeContext(), getNodeManager());
    }

    @Nonnull
    @Override
    public String getStatus() {
        return "Running";
    }

    private void onStartup() {
        subscriptionModel.startup();

        serviceContext.boot();

        // TagProvider provider = deviceContext.getGatewayContext().getTagManager().getTagProvider("default");
        // fire initial subscription creation
        List<DataItem> dataItems = deviceContext.getSubscriptionModel().getDataItems(getName());
        onDataItemsCreated(dataItems);
    }

    private void onShutdown() {
        subscriptionModel.shutdown();

        serviceContext.shutdown();
    }

    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsCreated(dataItems);
    }

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsModified(dataItems);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsDeleted(dataItems);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
        subscriptionModel.onMonitoringModeChanged(monitoredItems);
    }

}
