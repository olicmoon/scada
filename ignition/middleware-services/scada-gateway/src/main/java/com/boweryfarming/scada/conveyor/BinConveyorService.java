package com.boweryfarming.scada.conveyor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.boweryfarming.scada.AbstractScadaService;
import com.boweryfarming.scada.ServiceContext;
import com.boweryfarming.scada.tags.PublicTag;
import com.boweryfarming.scada.tags.PublicTagEventListener;
import com.boweryfarming.scada.tags.PublicTagManagerService;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

public class BinConveyorService extends AbstractScadaService {
    final PublicTagManagerService publicTagManagerService;

    // TODO: read this database configs from Ignition gateway
    static final String DB_URL = "jdbc:postgresql://ignition-db:5432/ignition_dev";
    static final String USER = "ignition_dev";
    static final String PASSWD = "ignition_dev";
    static final String TBL_ROUTING_ENTRIES = "bin_conveyance_routing_entries";
    static final String TBL_ROUTING_LOGS = "bin_conveyance_farm_2_prototype_cold_pack_weigh_routing_logs";

    Map<String, PublicTagEventListener> eventListeners = new HashMap<String, PublicTagEventListener>();

    public BinConveyorService (ServiceContext context) {
        super(context);
        this.publicTagManagerService =
            (PublicTagManagerService) context.getService(ServiceContext.PUBTAG_MANAGER_SERVICE);
    }

    @Override
    public void onStart() {
        addEventListener("[Public]Conveyance/Bin/1/QRCode",
                new PublicTagEventListener() {
                    public void onValueChanged(String path, Object value) {
                        String QRCode = (String) value;
                        logger.info("New QR code: " + QRCode);

                        RetryPolicy<Object> policy = new RetryPolicy<>()
                            .abortOn(IllegalArgumentException.class)
                            .abortOn(ClassNotFoundException.class)
                            .handle(RuntimeException.class)
                            .withDelay(Duration.ofMillis(200))
                            .withMaxDuration(Duration.ofSeconds(3))
                            .onSuccess(e -> logger.info("QRCodeUpdated(" + QRCode + ") "
                                        + "succeeded " + e.getElapsedTime().toSeconds() + "s "
                                        + "attempts:" + e.getAttemptCount()))
                            .onAbort(e -> logger.error("Aborted.. QRCodeUpdated(" + QRCode + ") "
                                        + e.getFailure().getMessage()))
                            .onFailure(e -> logger.error("Failed.. QRCodeUpdated(" + QRCode + ") "
                                        + e.getFailure().getMessage()))
                            .onRetry(e -> logger.error("Retrying.. QRCodeUpdated(" + QRCode + ") "
                                        + e.getLastFailure().getMessage()));

                        Failsafe.with(policy).run(() -> QRCodeUpdated(QRCode));
                    }
                });

        addEventListener("[Public]Conveyance/Bin/1/ACC",
                new PublicTagEventListener() {
                    public void onValueChanged(String path, Object value) {
                        Long acc = (Long) value;
                        logger.info("ACC triggered: " + acc);

                        RetryPolicy<Object> policy = new RetryPolicy<>()
                            .abortOn(IllegalArgumentException.class)
                            .abortOn(ClassNotFoundException.class)
                            .handle(RuntimeException.class)
                            .withDelay(Duration.ofMillis(200))
                            .withMaxDuration(Duration.ofSeconds(3))
                            .onSuccess(e -> logger.info("accTriggered(" + acc + ") "
                                        + "succeeded " + e.getElapsedTime().toSeconds() + "s "
                                        + "attempts:" + e.getAttemptCount()))
                            .onAbort(e -> logger.error("Aborted.. accTriggered(" + acc+ ") "
                                        + e.getFailure().getMessage()))
                            .onFailure(e -> logger.error("Failed.. QRCodeUpdated(" + acc + ") "
                                        + e.getFailure().getMessage()))
                            .onRetry(e -> logger.error("Retrying.. accTriggered(" + acc + ") "
                                        + e.getLastFailure().getMessage()));

                        Failsafe.with(policy).run(() -> accTriggered(acc));
                    }
                });
    }

    private void addEventListener(String tagPath, PublicTagEventListener listener) {
        this.publicTagManagerService.addEventListener(tagPath, listener);
        this.eventListeners.put(tagPath, listener);
    }

    @Override
    public void onDestroy() {
        for (Map.Entry<String, PublicTagEventListener> entry : eventListeners.entrySet()) {
            this.publicTagManagerService.removeEventListener(entry.getKey(), entry.getValue());
        }
    }

    private void QRCodeUpdated(String QRCode) throws ClassNotFoundException, IllegalArgumentException, RuntimeException {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(DB_URL, USER, PASSWD);

            if (QRCode.length() == 0) {
                throw new IllegalArgumentException("Reject empty QRCode");
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("select * from %s ", TBL_ROUTING_ENTRIES));
            sb.append(String.format("where %s.bin_label = %s ", TBL_ROUTING_ENTRIES, QRCode));
            sb.append(String.format("order by %s.id desc limit 1;", TBL_ROUTING_ENTRIES));
            String query = sb.toString();

            stmt = conn.createStatement();
            rs = stmt.executeQuery(query);
            logger.info("query result:" + rs.toString());
            if (rs.next() == false) {
                throw new RuntimeException("No routing instruction:" + QRCode);
            }

            int id = rs.getInt("id");
            String desiredDestination = rs.getString("desired_destination");
            String validTill = rs.getString("valid_till");
            logger.info("Routing instruction: "
                    + " id:" + id
                    + " desired_destination:" + desiredDestination
                    + " valid_till:" + validTill);

            PublicTag presentTag = context.getPublicTag("[Public]Conveyance/Bin/1/Instruction/Present");
            PublicTag destinationTag = context.getPublicTag("[Public]Conveyance/Bin/1/Instruction/DesiredDestination");
            PublicTag idTag = context.getPublicTag("[Public]Conveyance/Bin/1/Instruction/DestinationId");
            presentTag.setValue(1);
            destinationTag.setValue(desiredDestination);
            idTag.setValue(id);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to select from database:" + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) { /* ignore */ }
            }

            if (stmt != null) {
                try { stmt.close(); } catch (SQLException e) { /* ignore */ }
            }

            if (rs != null) {
                try { rs.close(); } catch (SQLException e) { /* ignore */ }
            }
        }
    }

    private void accTriggered(Long acc) throws ClassNotFoundException, RuntimeException {
        if (acc == 0) {
            throw new IllegalArgumentException("Reject acc == 0");
        }
        logger.info("ACC: " + acc);

        StringBuilder sb = new StringBuilder();
        String rawBinLabel = (String) context.getPublicTag("[Public]Conveyance/Bin/1/Weigh/Info").getValue();
        String parsedBinLabel = (String) context.getPublicTag("[Public]Conveyance/Bin/1/QRCode").getValue();
        String destination = (String) context.getPublicTag("[Public]Conveyance/Bin/1/Destination").getValue();
        Long routingEntryId = (Long) context.getPublicTag("[Public]Conveyance/Bin/1/Instruction/DestinationId").getValue();
        String reason =  (String) context.getPublicTag("[Public]Conveyance/Bin/1/Result").getValue();
        Long weightGrams = (Long) context.getPublicTag("[Public]Conveyance/Bin/1/Weigh/Weight").getValue();
        String deviceDataTime = (String) context.getPublicTag("[Public]Conveyance/Bin/1/Datetime").getValue();
        String insertedAt = ""; // TODO: get insert time
        String updatedAt = ""; // TODO: get update time
        String writePrototypeDestination = destination;
        String writePrototypeReason = reason;

        sb.append(String.format("insert into %s (", TBL_ROUTING_LOGS));
        sb.append("raw_bin_label, parsed_bin_label, destination, ");
        sb.append("bin_conveyance_routing_entry_id, reason, weight_grams, ");
        sb.append("device_datetime, inserted_at, updated_at, "); 
        sb.append("write_prototype_destination, write_prototype_reason) ");
        sb.append("values ( ");
        sb.append(String.format("%s, %s, %s, ", rawBinLabel, parsedBinLabel, destination));
        sb.append(String.format("%s, %s, %s, ", routingEntryId, reason, weightGrams));
        sb.append(String.format("%s, %s, %s, ", deviceDataTime, insertedAt, updatedAt));
        sb.append(String.format("%s, %s ", writePrototypeDestination, writePrototypeReason));
        sb.append(");");

        String query = sb.toString();

        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(DB_URL, USER, PASSWD);
            stmt = conn.createStatement();
            rs = stmt.executeQuery(query);
            logger.info("query result:" + rs.toString());
            if (rs.next() == false) {
                return;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to select from database:" + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) { /* ignore */ }
            }

            if (stmt != null) {
                try { stmt.close(); } catch (SQLException e) { /* ignore */ }
            }

            if (rs != null) {
                try { rs.close(); } catch (SQLException e) { /* ignore */ }
            }
        }
    }

}
