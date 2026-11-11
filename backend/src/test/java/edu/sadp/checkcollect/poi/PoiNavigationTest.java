package edu.sadp.checkcollect.poi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.sadp.checkcollect.identity.IdentityService;
import java.time.Instant;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PoiNavigationTest {
    // 2026-11-11 实际 AMap JS API convertFrom(point, "gps") 返回值，独立于本地公式。
    @Test void convertedCoordinatesMatchVendorSamplesWithinOneMetre() {
        double[] wuhan = Gcj02.fromWgs84(114.355338, 30.528738);
        assertThat(wuhan[0]).isCloseTo(114.360853, within(.00001));
        assertThat(wuhan[1]).isCloseTo(30.526395, within(.00001));
    }

    @Test void beijingMatchesVendorAndTokyoIsUnchanged() {
        double[] beijing = Gcj02.fromWgs84(116.397128, 39.916527);
        assertThat(beijing[0]).isCloseTo(116.403372, within(.00001));
        assertThat(beijing[1]).isCloseTo(39.917930, within(.00001));
        assertThat(Gcj02.fromWgs84(139.748060, 35.631050)).containsExactly(139.748060, 35.631050);
    }

    @Test void routeConvertsBothEndpointsOnceAndPreservesChosenMode() {
        String url = new AmapAdapter().urlFor(114.355338, 30.528738, 116.397128, 39.916527, "car");
        assertEndpoint(url, "from", 114.360853, 30.526395);
        assertEndpoint(url, "to", 116.403372, 39.917930);
        assertThat(url).contains("mode=car").doesNotContain("coordinate=wgs84");
    }

    @Test void invalidOrMissingOriginNeverFallsBackToDestination() {
        PoiRepository repository = mock(PoiRepository.class);
        PoiService service = service(repository);
        for (Double[] coordinates : new Double[][] {
                {null, null}, {114.0, null}, {null, 30.0}, {Double.NaN, 30.0},
                {114.0, Double.POSITIVE_INFINITY}, {181.0, 30.0}, {114.0, -91.0}}) {
            assertThatThrownBy(() -> service.navigation(UUID.randomUUID(), coordinates[0], coordinates[1], "walk"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(repository);
    }

    @Test void rawTargetRemainsWgs84AndUnknownModesAreRejected() {
        PoiRepository repository = mock(PoiRepository.class);
        PoiService service = service(repository);
        UUID id = UUID.randomUUID();
        when(repository.findSummary(id)).thenReturn(Optional.of(new PoiSummary(id, UUID.randomUUID(),
                PoiCategory.SHOP, "商铺", "目标", "", 114.355338, 30.528738, "WGS84", 1, 1,
                Instant.now(), Instant.now(), null)));
        NavigationTarget target = service.navigation(id, 114.35, 30.52, "walk");
        assertThat(target.crs()).isEqualTo("WGS84");
        assertThat(target.targetLon()).isEqualTo(114.355338);
        assertThat(target.targetLat()).isEqualTo(30.528738);
        assertEndpoint(target.url(), "to", 114.360853, 30.526395);
        assertThatThrownBy(() -> service.navigation(id, 114.35, 30.52, "other"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    private PoiService service(PoiRepository repository) {
        return new PoiService(repository, mock(PhotoStorage.class), mock(IdentityService.class),
                mock(OcrAdapter.class), new AmapAdapter(), mock(PoiNotifier.class));
    }

    private void assertEndpoint(String url, String key, double lon, double lat) {
        String value = Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .filter(part -> part.startsWith(key + "=")).findFirst().orElseThrow().substring(key.length() + 1);
        String[] coordinates = value.split(",");
        assertThat(Double.parseDouble(coordinates[0])).isCloseTo(lon, within(.00001));
        assertThat(Double.parseDouble(coordinates[1])).isCloseTo(lat, within(.00001));
        assertThat(coordinates).hasSize(3);
    }
}
