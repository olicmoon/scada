package com.boweryfarming.scada.tags;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.boweryfarming.scada.AbstractScadaService;
import com.boweryfarming.scada.ServiceContext;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.config.TagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;

public class PublicTagManagerService extends AbstractScadaService {
    private final GatewayContext gatewayContext;
    private final GatewayTagManager tagManager;
    private final TagProvider tagProvider;
    private final ExecutorService tagNotificationExecutor = Executors.newFixedThreadPool(3);
    private Map<String, PublicTag> publicTags = new HashMap<String, PublicTag>();
    private Map<String, Collection<PublicTagEventListener>> publicTagEventListeners =
        new HashMap<String, Collection<PublicTagEventListener>>();

    public PublicTagManagerService(ServiceContext context) {
        super(context);
        this.gatewayContext = context.getGatewayContext();
        this.tagManager = this.gatewayContext.getTagManager();
        this.tagProvider = this.tagManager.getTagProvider("Public");
    }

    @Override
    public void onStart() {
        preparePublicTags();
        subscribeTagValues();
    }

    @Override
    public void onDestroy() {
        unsubscribeTagValues();
    }

    private void preparePublicTags() {
        List<TagConfiguration> configs = new ArrayList<>();

        try {
            prepareFolder(configs, "[Public]Conveyance/");
            prepareFolder(configs, "[Public]Conveyance/Bin/");
            prepareFolder(configs, "[Public]Conveyance/Bin/1/");
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/QRCode", DataType.String);
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Destination", DataType.String);
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Result", DataType.String);
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/ACC", DataType.Int8);
            prepareFolder(configs, "[Public]Conveyance/Bin/1/Instruction");
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Instruction/Present", DataType.String);
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Instruction/DestinationId", DataType.Int8);
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Instruction/DesiredDestination", DataType.String);
            prepareFolder(configs, "[Public]Conveyance/Bin/1/Weigh");
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Weigh/Info", DataType.String);
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Weigh/Weight", DataType.Int8);
            preparePublicTag(configs, "[Public]Conveyance/Bin/1/Weigh/DateTime", DataType.DateTime);
        } catch (Exception e) {
            logger.warn("Failed Public tag initialization: " + e.getMessage());
            return;
        }

        /**
         * currently we're not merge/overwriting existing tags
         */
        try {
            List<QualityCode> res = tagProvider.saveTagConfigsAsync(configs, CollisionPolicy.Abort).get(10, TimeUnit.SECONDS);
            for (QualityCode code : res) {
                logger.info("Save tag result: " + code.toString());
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.warn("Failed to save tag config" + e.toString()); // TODO: retry
            return;
        }
    }

    void subscribeTagValues() {
        for (Map.Entry<String, PublicTag> entry : publicTags.entrySet()) {
            PublicTag tag = entry.getValue();
            tag.start();
        }
    }

    void unsubscribeTagValues() {
        for (Map.Entry<String, PublicTag> entry : publicTags.entrySet()) {
            PublicTag tag = entry.getValue();
            try {
                tag.stop();
            } catch (Exception e) {
                logger.warn("Failed to unsubscribe tag" + e.toString());
                return;
            }
        }
    }

    private boolean prepareFolder(List<TagConfiguration> configs, String path) {
        TagPath tagPath;
        try {
            tagPath = TagPathParser.parse(path);
        } catch (IOException e) {
            logger.warn("Parse error " + path + ":" + e.toString());
            return false;
        }

        try {
            List<TagConfigurationModel> res = this.tagProvider.getTagConfigsAsync(Arrays.asList(tagPath), false, true).get(3, TimeUnit.SECONDS);
            logger.info("Prepare Tag:" + path + " res: " + res.size());
            if (res.size() == 1) {
                TagConfiguration config = res.get(0);

                if (config.getType() == TagObjectType.Unknown) {
                    logger.info("Creating new folder:" + path);
                    TagConfiguration newConfig = BasicTagConfiguration.createNew(tagPath);
                    newConfig.setType(TagObjectType.Folder);
                    configs.add(newConfig);
                    return true;
                }

                if (config.getType() != TagObjectType.Folder) {
                    logger.error("Tag:" + path + " exists but is not a folder >> "
                            + config.getType().toString());

                    return false;
                }

                return true;
            } else {
                logger.error("Multiple configs found");
                return false;
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.warn("Browse error " + path + ":" + e.toString());
            return false;
        }
    }

    private void preparePublicTag(List<TagConfiguration> configs, String path, DataType type) throws Exception {
        if (preparePublicTagInternal(configs, path, type)) {
            publicTags.put(path, new PublicTag(this, path));
        } else {
            throw new Exception("Failed to prepare public tag:" + path);
        }
    }

    private boolean preparePublicTagInternal(List<TagConfiguration> configs, String path, DataType type) {
        TagPath tagPath;
        try {
            tagPath = TagPathParser.parse(path);
        } catch (IOException e) {
            logger.warn("Parse error " + path + ":" + e.toString());
            return false;
        }

        try {
            List<TagConfigurationModel> res = this.tagProvider.getTagConfigsAsync(Arrays.asList(tagPath), false, true).get(3, TimeUnit.SECONDS);
            logger.info("Prepare Tag:" + path + " res: " + res.size());
            if (res.size() == 1) {
                TagConfiguration config = res.get(0);

                if (config.getType() == TagObjectType.Unknown) {
                    logger.info("Creating new reference: " + path);
                    TagConfiguration newConfig = BasicTagConfiguration.createNew(tagPath);
                    newConfig.setType(TagObjectType.AtomicTag);
                    newConfig.set(WellKnownTagProps.ValueSource, "reference");
                    newConfig.set(WellKnownTagProps.DataType, type);
                    configs.add(newConfig);
                    return true;
                }

                if (config.getType() != TagObjectType.AtomicTag) {
                    logger.error("Tag:" + path + " exists but is not an atomic >> "
                            + config.getType().toString());

                    return false;
                }

                String valueSource = config.get(WellKnownTagProps.ValueSource);
                if(!valueSource.equals("reference")) {
                    logger.info("Tag: " + path + " exists but not a reference >> "
                            + valueSource);
                }
                return true;
            } else {
                logger.error("Multiple configs found");
                return false;
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.warn("Browse error " + path + ":" + e.toString());
            return false;
        }
    }

    public PublicTag getPublicTag(String tagPath) {
        /**
         * TODO: lock (in case.. there's new public tags added during runtime?)
         * Mostly this isn't required (asumming SCADA gateway can restart after an software upgrade)
         */
        if (!publicTags.containsKey(tagPath)) {
            return null;
        }

        return publicTags.get(tagPath);
    }

    public void notifyPublicTagValueUpdated(PublicTag tag) {
        String path = tag.getPath();
        Object value = tag.getValue();
        logger.info("Notify tag value updated path: " + path + " value: " + value.toString());
        Collection<PublicTagEventListener> list = publicTagEventListeners.get(path);
        if (list == null) {
            return;
        }

        for (PublicTagEventListener listener: list) {
            tagNotificationExecutor.execute(() -> listener.onValueChanged(path, value));
        }
    }

    public void addEventListener(String path, PublicTagEventListener listener) {
        Collection<PublicTagEventListener> list = publicTagEventListeners.get(path);
        if (list == null) {
            list = new HashSet<PublicTagEventListener>();
            publicTagEventListeners.put(path, list);
        }

        list.add(listener);
    }

    public void removeEventListener(String path, PublicTagEventListener listener) {
        Collection<PublicTagEventListener> list = publicTagEventListeners.get(path);
        if (list == null) {
            return;
        }

        list.remove(listener);
    }

    public GatewayTagManager getTagManager() {
        return this.tagManager;
    }

}
