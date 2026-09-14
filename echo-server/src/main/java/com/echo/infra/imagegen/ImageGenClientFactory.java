package com.echo.infra.imagegen;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ImageGenClientFactory {
    private ImageGenClientFactory() {
    }

    public static IImageGenClient fromEnv() {
        return create(ImageGenConfig.fromEnv());
    }

    public static IImageGenClient create(ImageGenConfig config) {
        if (config.isMock()) {
            log.info("出图装配：provider={} 回落 MockImageGenClient", config.provider());
            return new MockImageGenClient();
        }
        log.info("出图装配：provider={} model={} function={} workspace={}",
                config.provider(), config.model(), config.function(), config.workspaceBase());
        return new ApiImageGenClient(config);
    }
}
