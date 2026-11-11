package edu.sadp.checkcollect.poi;

import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 未接入商用地图平台时的桩实现：只生成 OpenStreetMap 的位置链接，便于演示“导航入口”存在，
 * 但不是路线规划或语音导航。演示时必须说明此部分为模拟。
 */
@Component
@ConditionalOnProperty(name = "app.map.provider", havingValue = "stub", matchIfMissing = true)
public class StubMapAdapter implements MapAdapter {

    @Override
    public String provider() { return "stub-osm"; }

    @Override
    public boolean simulated() { return true; }

    @Override
    public String urlFor(double fromLon, double fromLat, double toLon, double toLat, String mode) {
        return String.format(Locale.ROOT,
                "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=18/%.6f/%.6f",
                toLat, toLon, toLat, toLon);
    }
}
