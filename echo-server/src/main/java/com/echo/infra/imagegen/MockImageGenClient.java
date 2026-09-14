package com.echo.infra.imagegen;

import java.util.List;

/** 无钥匙时的占位：不发起外网调用。 */
public final class MockImageGenClient implements IImageGenClient {
    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public List<GeneratedImage> stylize(String baseImageRef) {
        return List.of();
    }
}
