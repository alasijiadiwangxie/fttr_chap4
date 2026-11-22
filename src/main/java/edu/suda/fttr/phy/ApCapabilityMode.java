package edu.suda.fttr.phy;

/** B1 静态 AP 能力上限。实际 DATA 参数还受报文对应 STA 的能力限制。 */
public enum ApCapabilityMode {
    LCM(20, 1),
    HCM(80, 2);

    public static final int MCS = 7;
    public static final int GI_NS = 800;

    private final int bandwidthMhz;
    private final int nss;

    ApCapabilityMode(int bandwidthMhz, int nss) {
        this.bandwidthMhz = bandwidthMhz;
        this.nss = nss;
    }

    public int bandwidthMhz() {
        return bandwidthMhz;
    }

    public int nss() {
        return nss;
    }
}
