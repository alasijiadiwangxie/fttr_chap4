package edu.suda.fttr.dba;

/**
 * DBA 算法唯一的规划入口。输入只有 MFU 已知信息，输出只是待校验的建议计划。
 * 策略可以保存 RR 游标等内部状态，但不得持有 SFU 队列或创建真实 BWmap 授权。
 */
public interface DbaPolicy {
    DbaPlan plan(DbaContext context);
}
