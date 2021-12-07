package com.boweryfarming.scada.simulator;

public class SimulatorTags {
    public static final String PROVIDER = "Simulator";

    /** Scanned QR code (String)*/
    public static final String BIN_DATA_PARSED_LABEL = "Bin Data/Parsed_Bin_Label";
    /** Bin destination after PLC process, (String, "kickout" or "continue" */
    public static final String BIN_DATA_DESTINATION = "Bin Data/Bin_Destination";
    /** Reason for bin destination (String, valid_os_instruction, no_os_instruction,..) */
    public static final String BIN_DATA_KICKOUT_REASON = "Bin Data/Bin_Kickout_Reason";
    /** Event trigger that PLC process completed (Integer) */
    public static final String BIN_DATA_ACC = "Bin Data/ACC";

    /** Bin identifier (Integer) */
    public static final String BIN_ROUTING_DESTINATION_ID = "Bin Routing/Bin_Routing_From_OS_Destination_ID";
    /** OS routing instruction for desired destination (String) */
    public static final String BIN_ROUTING_DESTINATION = "Bin Routing/Bin_Routing_From_OS_Desired_Destination";
    /** Indecate that OS data presents (Integer) */
    public static final String BIN_ROUTING_PRESENT = "Bin Routing/Bin_Routing_From_OS_Data_Present";

    /** Raw bin label in Json format for detailed bin information (String) */
    public static final String CHECK_WEIGH_INFO = "Check Weigh Data/Prototype_Check_Weigh_Info";
    /** Bin weight in grams (Integer) */
    public static final String CHECK_WEIGH_CAPTURED_WEIGHT = "Check Weigh Data/Check_Weigh_Captured_Weight";
    /** Device date time (DateTime) */
    public static final String CHECK_WEIGH_CAPTURED_DATETIME = "Check Weigh Data/Check_Weigh_Captured_Date_Time";


    public static String getTagPath(String path) {
        return String.format("[%s]%s", PROVIDER, path);
    }
}
