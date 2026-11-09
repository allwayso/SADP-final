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

  public String urlFor(double fromLon, double fromLat, double toLon, double toLat) {
    return String.format(
        Locale.ROOT,
        "https://uri.amap.com/navigation?from=%.6f,%.6f&to=%.6f,%.6f&mode=walk&coordinate=wgs84&src=checkcollect&callnative=1",
        fromLon,
        fromLat,
        toLon,
        toLat);
  }
}
