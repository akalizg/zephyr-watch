package com.zephyr.watch.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelRegistryRequest {

    private String modelVersion;
    private String modelType;
    private String modelUri;
    private String thresholdUri;
    private String featureColumnsUri;
    private String metadataUri;
    private String status;
}
