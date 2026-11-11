package edu.sadp.checkcollect.poi;

import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.map.provider", havingValue = "amap")
public class AmapAdapter implements MapAdapter {
  public String provider() {
    return "amap";
  }

  public boolean simulated() {
    return false;
  }

  public String urlFor(double fromLon, double fromLat, double toLon, double toLat, String mode) {
    // 路径规划 URI 不依赖 marker 接口的 coordinate=wgs84 参数。
    double[] from = Gcj02.fromWgs84(fromLon, fromLat);
    double[] to = Gcj02.fromWgs84(toLon, toLat);
    return String.format(
        Locale.ROOT,
        "https://uri.amap.com/navigation?from=%.6f,%.6f,origin&to=%.6f,%.6f,destination&mode=%s&src=checkcollect&callnative=1",
        from[0], from[1], to[0], to[1], mode);
  }
}
