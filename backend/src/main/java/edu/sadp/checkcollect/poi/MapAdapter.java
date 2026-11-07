package edu.sadp.checkcollect.poi;

/**
 * 地图平台适配接口。M2 拥有该适配器，真实 SDK 或 HTTP 调用只允许出现在实现类中，
 * 不向其他模块暴露具体平台细节。坐标系由适配器负责，M2 数据库中保留原始坐标及标识。
 */
public interface MapAdapter {
    String provider();

    boolean simulated();

    /** 返回可以打开目标位置的地图链接；接入真实平台后可返回路线或导航页。 */
    String urlFor(double fromLon, double fromLat, double toLon, double toLat);
}
