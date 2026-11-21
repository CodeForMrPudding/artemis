package io.artemis.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

/** Configurable request limits, enforced by the request validator. */
@ConfigurationProperties("vectordb.limits")
public interface LimitsConfig {
    @Bindable(defaultValue = "4096") int getMaxDimension();
    @Bindable(defaultValue = "1000") int getMaxBatchSize();
    @Bindable(defaultValue = "1000") int getMaxK();
    @Bindable(defaultValue = "64") int getMaxCollectionNameLength();
    @Bindable(defaultValue = "256") int getMaxIdBytes();
    @Bindable(defaultValue = "32") int getMaxMetadataEntries();
    @Bindable(defaultValue = "64") int getMaxMetadataKeyBytes();
    @Bindable(defaultValue = "1024") int getMaxMetadataValueBytes();
}
