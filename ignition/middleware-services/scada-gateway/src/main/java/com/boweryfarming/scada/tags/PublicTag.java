package com.boweryfarming.scada.tags;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.event.InvalidListenerException;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeEvent;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;

public class PublicTag {
    // TODO: implement Ignition SDK here
    // TODO: validate tag
    private final PublicTagManagerService service;
    private final String path;
    private final TagPath tagPath;
    private final ValueListener listener;
    private final AtomicReference<Object> tagValue = new AtomicReference<Object>(null);

    public PublicTag(PublicTagManagerService service, String path) throws IOException {
        this.service = service;
        // throws IOException when invalid path string
        this.path = path;
        this.tagPath = TagPathParser.parse(path);
        this.listener = new ValueListener();
    }

    /** Start updating tag value */
    public void start() {
        this.service.getTagManager().subscribeAsync(this.tagPath, this.listener);
    }

    /** Stop updating tag value */
    public void stop() throws InterruptedException, ExecutionException {
        this.service.getTagManager().unsubscribeAsync(this.tagPath, this.listener).get();
    }

    class ValueListener implements TagChangeListener {
        public void tagChanged(TagChangeEvent event) throws InvalidListenerException {
            List<TagPath> list = new ArrayList<TagPath>();
            list.add(event.getTagPath());

            try {
                // TODO: Optimize: SCADA services can handle ComputableFuture in a separated thread
                List<QualifiedValue> tags = service.getTagManager().readAsync(list).get();
                QualifiedValue qv = tags.get(0);

                Object newValue = qv.getValue();
                if (newValue == null) {
                    return;
                }
                if (newValue.equals(PublicTag.this.tagValue)) {
                    // don't do anything
                } else {
                    PublicTag.this.tagValue.set(qv.getValue());
                    PublicTag.this.service.notifyPublicTagValueUpdated(PublicTag.this);
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    public String getPath() {
        return this.path;
    }

    public TagPath getTagPath() {
        return this.tagPath;
    }

    public Object getValue() throws RuntimeException {
        // tag only supports primitive types so return value of this is a pass-by-value
        Object value = this.tagValue.get();
        if (value == null) {
            throw new RuntimeException("No value in tag: " + this.path);
        }
        return value;
    }

    public void setValue(Object newValue) throws RuntimeException {
        try {
            List<QualityCode> results = this.service.getTagManager().writeAsync(
                    Arrays.asList(this.tagPath), Arrays.asList(newValue)).get(3, TimeUnit.SECONDS);
            QualityCode qc = results.get(0);
            if(qc.isNotGood()) {
                throw new RuntimeException("Failed to update tag value path:" + this.tagPath.toString()
                        + " value" + newValue.toString() + " reason:" + qc.toString());
            }
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
            throw new RuntimeException("Failed to update tag value path:" + this.tagPath.toString()
                    + " value" + newValue.toString() + " reason:" + e.getMessage());
        }
    }
}
