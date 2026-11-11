package edu.sadp.checkcollect.poi;

/**
 * WGS84 原始位置只在进入高德 URI 边界时转换；数据库和接口目标坐标仍为 WGS84。
 * 公式改编自 googollee/eviltransform (BSD-2-Clause)，许可证随 JAR 分发于
 * META-INF/eviltransform-LICENSE.txt。结果用真实 AMap.convertFrom('gps') 样本交叉校验。
 */
final class Gcj02 {
    private Gcj02() {}

    static double[] fromWgs84(double lon, double lat) {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return new double[] {lon, lat};
        }
        double x = lon - 105, y = lat - 35;
        double dLat = -100 + 2*x + 3*y + .2*y*y + .1*x*y + .2*Math.sqrt(Math.abs(x));
        double dLon = 300 + x + 2*y + .1*x*x + .1*x*y + .1*Math.sqrt(Math.abs(x));
        double common = (20*Math.sin(6*x*Math.PI) + 20*Math.sin(2*x*Math.PI))*2/3;
        dLat += common + (20*Math.sin(y*Math.PI) + 40*Math.sin(y*Math.PI/3))*2/3
                + (160*Math.sin(y*Math.PI/12) + 320*Math.sin(y*Math.PI/30))*2/3;
        dLon += common + (20*Math.sin(x*Math.PI) + 40*Math.sin(x*Math.PI/3))*2/3
                + (150*Math.sin(x*Math.PI/12) + 300*Math.sin(x*Math.PI/30))*2/3;
        double rad = Math.toRadians(lat);
        double eccentricity = .00669342162296594323;
        double magic = 1 - eccentricity*Math.sin(rad)*Math.sin(rad);
        double root = Math.sqrt(magic);
        dLat = dLat*180 / ((6378137*(1-eccentricity))/(magic*root)*Math.PI);
        dLon = dLon*180 / (6378137/root*Math.cos(rad)*Math.PI);
        return new double[] {lon + dLon, lat + dLat};
    }
}
