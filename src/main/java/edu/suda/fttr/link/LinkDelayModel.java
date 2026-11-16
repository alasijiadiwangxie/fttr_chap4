package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;

/** 给出从本跳开始到接收端完整收到报文的服务时长，单位为微秒。 */
public interface LinkDelayModel {
    long delayUs(Packet packet);
}
