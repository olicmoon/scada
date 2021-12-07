package com.boweryfarming.scada.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.boweryfarming.scada.AbstractScadaService;
import com.boweryfarming.scada.ServiceContext;
import com.google.gson.Gson;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.types.ReferenceTagTypeProps;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;

import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.BuiltinDataType;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimulatorService extends AbstractScadaService {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    Gson gson = new Gson();

    private Map<String, UaVariableNode> variableNodes = new HashMap<String, UaVariableNode>();
    private final Thread taskThread;
    private final TaskRunnable taskRunnable;

    interface Task {
        public String getName();
        public boolean prepare();
        public void run();
    }

    class RawBinLabel {
        public int farm_id;
        public String type;
        public String label;
        public String side;

        public RawBinLabel(int farmId, String side, String label, String type) {
            this.farm_id = farmId;
            this.side = side;
            this.label = label;
            this.type = type;
        }
    }

    class KickoutReason {
        static final String VALID_INSTRUCTION = "valid_os_instruction";
        static final String INVALID_INSTRUCTION = "invalid_os_instruction";
        static final String NO_INSTRUCTION = "no_os_instruction";
    }

    final Set<String> validDestinations = new HashSet<String>(Arrays.asList("basil", "cold_pack"));

    class OsInstruction {
        int destinationId;
        String desiredDestination;
        int dataPresent;

        public OsInstruction(int destinationId,
                String desiredDestination,
                int dataPresent) {
            this.destinationId = destinationId;
            this.desiredDestination = desiredDestination;
            this.dataPresent = dataPresent;
        }
    }

    class BinRoutingTaskContext {
        private final RawBinLabel rawBinLabel;
        Optional<OsInstruction> osInstruction = Optional.empty();
        int weight = 0;

        public BinRoutingTaskContext(RawBinLabel rawBinLabel) {
            this.rawBinLabel = rawBinLabel;
        }

        public RawBinLabel getRawBinLabel() {
            return rawBinLabel;
        }

        public void setOsInstruction(OsInstruction osInstruction) {
            this.osInstruction = Optional.of(osInstruction);
        }

        public Optional<OsInstruction> getOsInstruction() {
            return osInstruction;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public int getWeight() {
            return weight;
        }
    }

    // This task context is only used/accessed by Task runnables that run in a
    // single thread so it won't need addtional synchronization
    Optional<BinRoutingTaskContext> binRoutingTaskContext = Optional.empty();
    int accCounter = 0; // TODO: not sure at this point what's appropriate ACC tag value

    class ScanBinLabelTask implements Task {
        RawBinLabel rawBinLabel;
        int weight;
        ScanBinLabelTask(int farmId, String side, String label, int weight) {
            this.rawBinLabel = new RawBinLabel(farmId, side, label, "bin");
            this.weight = weight;
        }

        @Override
        public String getName() {
            return "ScanBinLabelTask";
        }

        @Override
        public boolean prepare() {
            logger.info(String.format("prepare (scan): 0x%x", binRoutingTaskContext.hashCode()));
            if(binRoutingTaskContext.isPresent()) {
                logger.warn("already ongoing routing task");
                return false;
            }

            return true;
        }

        @Override
        public void run() {
            logger.info(String.format("set context : 0x%x", binRoutingTaskContext.hashCode()));
            binRoutingTaskContext = Optional.of(new BinRoutingTaskContext(rawBinLabel));
            binRoutingTaskContext.get().setWeight(weight);
            setNodeValue(SimulatorTags.BIN_DATA_PARSED_LABEL, rawBinLabel.label);
        }
    }

    class BinRoutingTask implements Task {
        OsInstruction osInstruction;

        BinRoutingTask(OsInstruction osInstruction) {
            this.osInstruction = osInstruction;
        }

        @Override
        public String getName() {
            return "BinRoutingTask";
        }

        @Override
        public boolean prepare() {
            if(!binRoutingTaskContext.isPresent()) {
                logger.warn("no ongoing routing task");
                return false;
            }

            binRoutingTaskContext.get().setOsInstruction(this.osInstruction);

            return true;
        }

        @Override
        public void run() {
            RawBinLabel binLabel = binRoutingTaskContext.get().getRawBinLabel();
            Optional<OsInstruction> instruction = binRoutingTaskContext.get().getOsInstruction();
            logger.info(String.format("raw bin label: %s", gson.toJson(binLabel)));
            setNodeValue(SimulatorTags.CHECK_WEIGH_INFO, gson.toJson(binLabel));

            setNodeValue(SimulatorTags.CHECK_WEIGH_CAPTURED_WEIGHT, binRoutingTaskContext.get().getWeight());

            DateTime currTime = new DateTime();
            setNodeValue(SimulatorTags.CHECK_WEIGH_CAPTURED_DATETIME, currTime);

            if (instruction.isEmpty()) {
                setNodeValue(SimulatorTags.BIN_DATA_DESTINATION, "kickout");
                setNodeValue(SimulatorTags.BIN_DATA_KICKOUT_REASON, KickoutReason.NO_INSTRUCTION);
            } else if (instruction.get().dataPresent == 0) {
                setNodeValue(SimulatorTags.BIN_DATA_DESTINATION, "kickout");
                setNodeValue(SimulatorTags.BIN_DATA_KICKOUT_REASON, KickoutReason.NO_INSTRUCTION);
            } else if (!validDestinations.contains(instruction.get().desiredDestination)) {
                setNodeValue(SimulatorTags.BIN_DATA_DESTINATION, "kickout");
                setNodeValue(SimulatorTags.BIN_DATA_KICKOUT_REASON, KickoutReason.INVALID_INSTRUCTION);
            } else {
                setNodeValue(SimulatorTags.BIN_DATA_DESTINATION, "continue");
                setNodeValue(SimulatorTags.BIN_DATA_KICKOUT_REASON, KickoutReason.VALID_INSTRUCTION);
            }

            accCounter++;
            setNodeValue(SimulatorTags.BIN_DATA_ACC, accCounter);

            logger.info(String.format("clr context : 0x%x acc:%d",
                        binRoutingTaskContext.hashCode(), accCounter));
            binRoutingTaskContext = Optional.empty();
        }
    }

    class TaskRunnable implements Runnable {
        BlockingQueue<Task> queue = new LinkedBlockingQueue<Task>();
        boolean aborted = false;

        public TaskRunnable() {
        }

        public void abort() {
            aborted = true;
            queue.notifyAll();
        }

        /** Queue task to task thread
         * @return false if conditions are not met to run task (task.preapre failed) otherwise 
         *         true when task is successfully scheduled
         */
        public boolean enqueue(Task task) {
            if (aborted) {
                logger.error("task thread aborted..");
                return false;
            }

            if (task.prepare()) {
                try {
                    queue.put(task);
                    return true;
                } catch (InterruptedException ie) {
                    logger.warn(ie.getMessage());
                }
            }

            logger.warn(String.format("failed to schedule task %s", task.getName()));
            return false;
        }

        public void run() {
            while (!aborted) {
                try {
                    Task nextTask = queue.poll(3, TimeUnit.SECONDS);
                    if (nextTask == null) {
                        continue;
                    }

                    logger.info(String.format("Task started %s", nextTask.getName()));
                    nextTask.run();
                    logger.info(String.format("Task finished %s", nextTask.getName()));
                } catch (InterruptedException ie) {
                    logger.warn("task thread interrupted");
                }

            }
            logger.warn("task thread aborted");
        }
    }

    public SimulatorService (ServiceContext context) {
        super(context);
        taskRunnable = new TaskRunnable();
        taskThread = new Thread(taskRunnable);
    }

    @Override
    public void onStart() {
        // create a folder node for our configured device
        UaFolderNode rootNode = new UaFolderNode(
                context.getNodeContext(),
                context.getDeviceContext().nodeId(getName()),
                context.getDeviceContext().qualifiedName(String.format("[%s]", getName())),
                new LocalizedText(String.format("[%s]", getName()))
                );

        // add the folder node to the server
        context.getNodeManager().addNode(rootNode);

        // add a reference to the root "Devices" folder node
        rootNode.addReference(new Reference(
                    rootNode.getNodeId(),
                    Identifiers.Organizes,
                    context.getDeviceContext().getRootNodeId().expanded(),
                    Reference.Direction.INVERSE
                    ));

        addNodes(rootNode);
        startRelays();

        if(importSimulatorTags()) {
            try {
                configurePublicTag("[Public]Conveyance/Bin/1/QRCode",
                        SimulatorTags.getTagPath(SimulatorTags.BIN_DATA_PARSED_LABEL));
                configurePublicTag("[Public]Conveyance/Bin/1/Destination",
                        SimulatorTags.getTagPath(SimulatorTags.BIN_DATA_DESTINATION));
                configurePublicTag("[Public]Conveyance/Bin/1/Result",
                        SimulatorTags.getTagPath(SimulatorTags.BIN_DATA_KICKOUT_REASON));
                configurePublicTag("[Public]Conveyance/Bin/1/ACC",
                        SimulatorTags.getTagPath(SimulatorTags.BIN_DATA_ACC));
                configurePublicTag("[Public]Conveyance/Bin/1/Instruction/Present",
                        SimulatorTags.getTagPath(SimulatorTags.BIN_ROUTING_PRESENT));
                configurePublicTag("[Public]Conveyance/Bin/1/Instruction/DestinationId",
                        SimulatorTags.getTagPath(SimulatorTags.BIN_ROUTING_DESTINATION_ID));
                configurePublicTag("[Public]Conveyance/Bin/1/Instruction/DesiredDestination",
                        SimulatorTags.getTagPath(SimulatorTags.BIN_ROUTING_DESTINATION));
                configurePublicTag("[Public]Conveyance/Bin/1/Weigh/Info",
                        SimulatorTags.getTagPath(SimulatorTags.CHECK_WEIGH_INFO));
                configurePublicTag("[Public]Conveyance/Bin/1/Weigh/Weight",
                        SimulatorTags.getTagPath(SimulatorTags.CHECK_WEIGH_CAPTURED_WEIGHT));
                configurePublicTag("[Public]Conveyance/Bin/1/Weigh/DateTime",
                        SimulatorTags.getTagPath(SimulatorTags.CHECK_WEIGH_CAPTURED_DATETIME));
            } catch (Exception e) {
                logger.error("failed to configure public tag: " + e.getMessage());
            }
        } else {
            // TODO: may retry?
        }
        taskThread.start();
    }

    @Override
    public void onDestroy() {
        stopRelays();

        taskRunnable.abort();
        try {
            taskThread.join();
        } catch (InterruptedException ie) {
            logger.warn(String.format("failed to join task thread %s", ie.getMessage()));
        }
    }

    private boolean importSimulatorTags() {
        TagProvider tagProvider = this.context.getGatewayContext().getTagManager().getTagProvider("Simulator");
        try {
            InputStream is = getClass().getResourceAsStream("simulator_tags.json");
            if (is == null) {
                logger.error("Failed to import simulator tag configs: uri not found");
                return false;
            }

            byte[] bytes = is.readAllBytes();
            String s = new String(bytes, StandardCharsets.UTF_8);
            logger.info(s + " importing:" + bytes.length);

            TagPath root = TagPathParser.parse("Simulator", "");
            List<QualityCode> res = tagProvider.importTagsAsync(
                    root,
                    s,
                    "json",
                    CollisionPolicy.Overwrite).get(3, TimeUnit.SECONDS);
            for (QualityCode code : res) {
                logger.info("import result: " + code.toString());
            }
        } catch (IOException | TimeoutException | ExecutionException | InterruptedException e) {
            logger.error("Faield to import simulator tag configs: " + e.getMessage());
            return false;
        }

        return true;
    }

    private void configurePublicTag(String path, String sourcePath) throws Exception {
        TagProvider tagProvider = this.context.getGatewayContext().getTagManager().getTagProvider("Public");
        TagPath tagPath= TagPathParser.parse(path);
        List<TagConfigurationModel> configs =
            tagProvider.getTagConfigsAsync(Arrays.asList(tagPath), false, true).get(3, TimeUnit.SECONDS);
        TagConfigurationModel config = configs.get(0);
        if(TagObjectType.Unknown == config.getType()) {
            throw new Exception(String.format("Public tag not found '%s'", path));
        }

        config.set(ReferenceTagTypeProps.SourceTagPath, sourcePath);
        List<QualityCode> results =
            tagProvider.saveTagConfigsAsync(Arrays.asList(config), CollisionPolicy.MergeOverwrite).get(3, TimeUnit.SECONDS);

        for (int i = 0; i < results.size(); i++) {
            QualityCode result = results.get(i);
            if (result.isNotGood()) {
                throw new Exception(String.format("Edit tag operation returned bad result '%s'", result.toString()));
            }
        }
    }

    private Optional<Object> getNodeValue(String nodeName) {
        if (variableNodes.containsKey(nodeName)) {
            return Optional.of(variableNodes.get(nodeName).getValue().getValue().getValue());
        } else {
            return Optional.ofNullable(null);
        }
    }

    private void setNodeValue(String nodeName, Object value) {
        if (variableNodes.containsKey(nodeName)) {
            variableNodes.get(nodeName).setValue(new DataValue(new Variant(value)));
        } else {
            logger.error(String.format("Node %s not found. failed to set value", nodeName));
        }
    }

    private void createNode(UaFolderNode dir, String uaPath, NodeId type, Object defaultValue) {
        try {
            ArrayList<String> nodes = new ArrayList<String>();
            Collections.addAll(nodes, uaPath.split("/"));
            UaVariableNode node = createNode(dir, nodes, type, defaultValue);
            variableNodes.put(uaPath, node);
            logger.info("Created UaNode: [" + uaPath + "]");
            return;
        } catch (Exception e) {
            logger.error("Failed to create UaNode [" + uaPath + "]: " + e.getMessage());
        }
    }

    private UaVariableNode createNode(UaFolderNode dir, ArrayList<String> nodes, NodeId type, Object defaultValue) throws Exception {
        if (nodes.size() == 0) {
            throw new Exception(String.format("Failed to create node: empty"));
        }
        String name = nodes.get(0);
        nodes.remove(0);

        Optional<UaNode> res = dir.findNode(context.getDeviceContext().qualifiedName(name));
        if (nodes.size() > 0) {
            UaFolderNode subdir = null;
            if (res.isEmpty()) {
                subdir = new UaFolderNode(
                        context.getNodeContext(),
                        context.getDeviceContext().nodeId(name),
                        context.getDeviceContext().qualifiedName(name),
                        new LocalizedText(name));

                context.getNodeManager().addNode(subdir);
                dir.addOrganizes(subdir);
            } else {
                subdir = (UaFolderNode) res.get();
            }

            // iterate rest of the path
            return createNode(subdir, nodes, type, defaultValue);
        } else {
            // data node
            if (res.isEmpty()) {
                UaVariableNode newNode = UaVariableNode.builder(context.getNodeContext())
                    .setNodeId(context.getDeviceContext().nodeId(String.format("%s/node", name)))
                    .setBrowseName(context.getDeviceContext().qualifiedName(name))
                    .setDisplayName(new LocalizedText(name))
                    .setDataType(type)
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .setAccessLevel(AccessLevel.READ_WRITE)
                    .setUserAccessLevel(AccessLevel.READ_WRITE)
                    .setValue(new DataValue(new Variant(defaultValue)))
                    .build();
                context.getNodeManager().addNode(newNode);
                dir.addOrganizes(newNode);

                return newNode;
            } else {
                return (UaVariableNode) res.get();
            }
        }
    }

    private void addNodes(UaFolderNode root) {
        createNode(root, SimulatorTags.BIN_DATA_PARSED_LABEL, BuiltinDataType.String.getNodeId(), "");
        createNode(root, SimulatorTags.BIN_DATA_DESTINATION, BuiltinDataType.String.getNodeId(), "kickout");
        createNode(root, SimulatorTags.BIN_DATA_KICKOUT_REASON, BuiltinDataType.String.getNodeId(),
                "no_os_instruction");
        createNode(root, SimulatorTags.BIN_DATA_ACC, BuiltinDataType.Int32.getNodeId(), 0);

        createNode(root, SimulatorTags.CHECK_WEIGH_INFO, BuiltinDataType.String.getNodeId(), "");
        createNode(root, SimulatorTags.CHECK_WEIGH_CAPTURED_WEIGHT, BuiltinDataType.UInt32.getNodeId(), 0);
        createNode(root, SimulatorTags.CHECK_WEIGH_CAPTURED_DATETIME, BuiltinDataType.DateTime.getNodeId(),
                new DateTime(0));

        createNode(root, SimulatorTags.BIN_ROUTING_DESTINATION_ID, BuiltinDataType.Int32.getNodeId(), 0);
        createNode(root, SimulatorTags.BIN_ROUTING_DESTINATION, BuiltinDataType.String.getNodeId(), "");
        createNode(root, SimulatorTags.BIN_ROUTING_PRESENT, BuiltinDataType.Int32.getNodeId(), 999);
    }

    Set<String> runningRelays = new HashSet<String>();
    private void startRelay(String name, Runnable runnable, int interval) {
        context.getDeviceContext().getGatewayContext()
            .getExecutionManager()
            .registerAtFixedRate(
                    name,
                    context.getDeviceContext().getName(),
                    runnable, interval, TimeUnit.SECONDS);
        runningRelays.add(name);
    }

    private void startRelays() {
        startRelay("MonitorRoutingPresent",
                new Runnable() {
                    public void run () {
                        int id = 0;
                        String destination = "";
                        int dataPresent = 0;

                        Optional<Object> res = Optional.empty();
                        res = getNodeValue(SimulatorTags.BIN_ROUTING_PRESENT);
                        if (res.isPresent()) {
                            dataPresent = (int) res.get();
                        } else {
                            logger.error("Failed to read bin data present");
                            return;
                        }

                        if (dataPresent != 0 && dataPresent != 1) {
                            // TODO: assuming data present value is only 0 or 1
                            return;
                        }

                        res = getNodeValue(SimulatorTags.BIN_ROUTING_DESTINATION_ID);
                        if (res.isPresent()) {
                            id = (int) res.get();
                        } else {
                            logger.error("Failed to read bin routing destination id");
                            return;
                        }

                        res = getNodeValue(SimulatorTags.BIN_ROUTING_DESTINATION);
                        if (res.isPresent()) {
                            destination = (String) res.get();
                        }

                        boolean rc = taskRunnable.enqueue(new BinRoutingTask(
                                    new OsInstruction(id, destination, dataPresent)));
                        if (!rc) {
                            logger.error("failed to process routing present event");
                            setNodeValue(SimulatorTags.BIN_ROUTING_PRESENT, 999);
                            return;
                        }

                        setNodeValue(SimulatorTags.BIN_ROUTING_PRESENT, 999);
                    }
                }, 3);
    }

    private void stopRelays() {
        for (String owner: runningRelays) {
            context.getDeviceContext().getGatewayContext()
                .getExecutionManager()
                .unRegister(owner, context.getDeviceContext().getName());
        }
    }

    public void scanBinLabel(int farmId, String side, String label, int weight) {
        logger.info(String.format("scan_bin_label: %d %s %s %d",
                    farmId, side, label, weight));
        taskRunnable.enqueue(new ScanBinLabelTask(farmId, side, label, weight));
    }

    public void clearBinRouting() {
        taskRunnable.enqueue(new Task() {
            public String getName() {
                return "ClearBinRoutingTask";
            }

            public boolean prepare() {
                return binRoutingTaskContext.isPresent();
            }

            public void run() {
                binRoutingTaskContext = Optional.empty();
            }
        });
    }
}
