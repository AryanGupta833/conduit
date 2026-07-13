package com.aryan.conduit.plugin;


import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Builder
public class PluginResult {
        private boolean success;
        private String output;

        @Builder.Default
        private Map<String,Object> variables=new HashMap<>();

        @Builder.Default
        private Map<String,Object> metadata=new HashMap<>();
}
