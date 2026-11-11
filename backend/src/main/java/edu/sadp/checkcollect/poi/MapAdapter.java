package edu.sadp.checkcollect.poi;

/**
 * 地图平台适配接口。M2 拥有该适配器，真实 SDK 或 HTTP 调用只允许出现在实现类中，
 * 不向其他模块暴露具体平台细节。坐标系由适配器负责，M2 数据库中保留原始坐标及标识。
 */
public interface MapAdapter {
    String provider();

    boolean simulated();

    /** 输入均为 WGS84，由适配器转换为平台要求的坐标；mode 为 walk/car。 */
    String urlFor(double fromLon, double fromLat, double toLon, double toLat, String mode);
}
