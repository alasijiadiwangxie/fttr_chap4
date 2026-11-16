package edu.suda.fttr.simulator;

/** 到达事件同时标记上一跳服务结束，发送开始保留为单独事件。 */
public enum EventType {
    PACKET_ARRIVAL_AT_MFU,
    FIBER_TX_START,
    PACKET_ARRIVAL_AT_SFU,
    WIFI_TX_START,
    PACKET_ARRIVAL_AT_STA
}
