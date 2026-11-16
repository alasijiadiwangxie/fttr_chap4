package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;

/** 固定时延用于验证事件时序。后续可用报文属性估计不同的服务时长。 */
public final class FixedDelayModel implements LinkDelayModel {
    private final long delayUs;

    public FixedDelayModel(long delayUs) {
        if (delayUs < 0) {
            throw new IllegalArgumentException("Link delay must be non-negative");
        }
        this.delayUs = delayUs;
    }

    @Override
    public long delayUs(Packet packet) {
        return delayUs;
    }
}
