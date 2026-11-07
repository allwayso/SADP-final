package edu.sadp.checkcollect.poi;

/**
 * 地图导航入口的返回值。simulated 为真表示当前由桩适配器生成，页面必须标注为模拟，
 * 不得声称已接入商用地图平台的路线导航。
 */
public record NavigationTarget(
        String provider,
        boolean simulated,
        String url,
        double targetLon,
        double targetLat,
        String crs,
        String note) {}
