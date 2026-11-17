package edu.suda.fttr.simulator;

/** 到达事件标记数据接收。启用立即 ACK 时，无线服务在 ACK 完成后结束。 */
public enum EventType {
    PACKET_GENERATED,
    FIBER_TX_START,
    PACKET_ARRIVAL_AT_SFU,
    WIFI_ARRIVAL_AT_SFU,
    WIFI_TX_START,
    PACKET_ARRIVAL_AT_STA,
    WIFI_ACK_START,
    WIFI_ACK_END,
    PACKET_ARRIVAL_AT_MFU,
    WIFI_SELECT_NEXT,
    WIFI_ACCESS_TIMER,
    WIFI_ACCESS_RESOLVE,
    WIFI_COLLISION_DATA_END
}
