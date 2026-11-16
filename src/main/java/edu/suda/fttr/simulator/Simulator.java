package edu.suda.fttr.simulator;

import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.FiberLink;
import edu.suda.fttr.link.FixedDelayModel;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.trace.TraceLogger;

/** 建立单 SFU 下行拓扑，按事件类型调用对应的实体。 */
public final class Simulator {
    private Simulator() {
    }

    public static void run(SimulationConfig config, TraceLogger trace) {
        SimulationContext context = new SimulationContext(trace, config.packets().size() > 1);
        Mfu mfu = new Mfu(new FiberLink(new FixedDelayModel(config.fiberDelayUs())));
        Sfu sfu = new Sfu(new WifiLink(new FixedDelayModel(config.wifiDelayUs())));
        Sta sta = new Sta();

        // 外部业务只安排 MFU 到达。后续的事件由节点和链路逐跳生成。
        for (Packet packet : config.packets()) {
            context.scheduleAfter(packet.mfuArrivalUs(), EventType.PACKET_ARRIVAL_AT_MFU, packet);
        }

        Event event;
        while ((event = context.nextEvent()) != null) {
            switch (event.type()) {
                case PACKET_ARRIVAL_AT_MFU:
                    mfu.receive(event.packet(), context);
                    break;
                case FIBER_TX_START:
                    mfu.transmit(event.packet(), context);
                    break;
                case PACKET_ARRIVAL_AT_SFU:
                    sfu.receive(event.packet(), context);
                    break;
                case WIFI_TX_START:
                    sfu.transmit(event.packet(), context);
                    break;
                case PACKET_ARRIVAL_AT_STA:
                    sta.receive(event.packet(), context);
                    // 当前链路模型中，STA 完整接收就是此次无线服务的结束边界。
                    sfu.completeTransmission(event.packet(), context);
                    break;
                case WIFI_SELECT_NEXT:
                    sfu.selectNext(context);
                    break;
                default:
                    throw new IllegalStateException("Unhandled event: " + event.type());
            }
        }
        context.recordSummary();
    }
}
