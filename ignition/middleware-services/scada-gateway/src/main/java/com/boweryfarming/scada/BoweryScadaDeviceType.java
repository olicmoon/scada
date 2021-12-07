package com.boweryfarming.scada;

import javax.annotation.Nonnull;

import com.boweryfarming.scada.settings.BoweryScadaDeviceSettings;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.localdb.persistence.ReferenceField;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceSettingsRecord;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceType;


public class BoweryScadaDeviceType extends DeviceType {
    public static final BoweryScadaDeviceType INSTANCE = new BoweryScadaDeviceType();

    public static final String TYPE_ID = "SimulatorDevice";

    public BoweryScadaDeviceType() {
        /* DisplayName and Description are retrieved from ExampleDevice.properties */
        super(TYPE_ID, "BoweryScadaDevice.Meta.DisplayName", "BoweryScadaDevice.Meta.Description");
    }

    @Override
    public RecordMeta<? extends PersistentRecord> getSettingsRecordType() {
        return BoweryScadaDeviceSettings.META;
    }

    @Override
    public ReferenceField<?> getSettingsRecordForeignKey() {
        return BoweryScadaDeviceSettings.DEVICE_SETTINGS;
    }

    @Nonnull
    @Override
    public Device createDevice(
        @Nonnull DeviceContext deviceContext,
        @Nonnull DeviceSettingsRecord deviceSettingsRecord
    ) {

        BoweryScadaDeviceSettings settings = findProfileSettingsRecord(
            deviceContext.getGatewayContext(),
            deviceSettingsRecord
        );

        return new BoweryScadaDevice(this, deviceContext, settings);
    }

}
