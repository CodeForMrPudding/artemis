package io.artemis.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/** Writer defaults from the collection API design. */
@ConfigurationProperties("vectordb.writer")
public interface WriterConfig {
    @Bindable(defaultValue = "10000") int getQueueCapacity();
    @Bindable(defaultValue = "true") boolean isSyncWrites();
    @Bindable(defaultValue = "30s") Duration getRequestTimeout();
}
