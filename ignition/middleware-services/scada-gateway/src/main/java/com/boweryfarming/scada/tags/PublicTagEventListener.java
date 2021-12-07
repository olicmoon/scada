package com.boweryfarming.scada.tags;

import java.util.EventListener;

public interface PublicTagEventListener extends EventListener {
    public void onValueChanged(String path, Object value);
}
