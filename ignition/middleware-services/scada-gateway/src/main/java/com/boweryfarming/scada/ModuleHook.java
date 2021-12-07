package com.boweryfarming.scada;

import static org.python.google.common.collect.Lists.newArrayList;

import java.util.List;

import javax.annotation.Nonnull;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.AbstractDeviceModuleHook;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceType;

import org.jetbrains.annotations.NotNull;

public class ModuleHook extends AbstractDeviceModuleHook {

    @Override
    public void setup(@NotNull GatewayContext context) {
        super.setup(context);
        
        BundleUtil.get().addBundle(BoweryScadaDevice.class);
    }

    @Override
    public void startup(@NotNull LicenseState activationState) {
        super.startup(activationState);
    }

    @Override
    public void shutdown() {
        super.shutdown();

        BundleUtil.get().removeBundle(BoweryScadaDevice.class);
    }

    @Nonnull
    @Override
    protected List<DeviceType> getDeviceTypes() {
        return newArrayList(BoweryScadaDeviceType.INSTANCE);
    }

}
