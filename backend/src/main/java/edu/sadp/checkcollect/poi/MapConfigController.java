package edu.sadp.checkcollect.poi;

import edu.sadp.checkcollect.identity.IdentityService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class MapConfigController {
  private final IdentityService identity;
  private final String provider, key;

  public MapConfigController(
      IdentityService identity,
      @Value("${app.map.provider:stub}") String provider,
      @Value("${AMAP_JS_KEY:}") String key) {
    this.identity = identity;
    this.provider = provider;
    this.key = key;
  }

  @GetMapping("/api/pois/platform")
  public Map<String, String> config() {
    identity.currentUser();
    return Map.of("provider", provider, "key", key);
  }
}
