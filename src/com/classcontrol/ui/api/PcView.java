package com.classcontrol.ui.api;

import java.awt.image.BufferedImage;

public record PcView(
        String id,
        String host,
        String ip,
        Status status,
        long lastSeenMs,
        BufferedImage thumbnail,
        boolean locked
) {}
