package edu.sadp.checkcollect.review.stub;

import edu.sadp.checkcollect.identity.IdentityService;
import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.identity.UserSummary;
import edu.sadp.checkcollect.review.port.PoiRevision;
import edu.sadp.checkcollect.review.port.ReviewProgress;
import edu.sadp.checkcollect.review.port.ReviewSummary;
import edu.sadp.checkcollect.review.port.RevisionDraft;
import edu.sadp.checkcollect.review.port.RevisionPurpose;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * M2 未接入时给 POI 桩写入演示数据，使核验页面在只有 M1 身份服务的情况下可以完整演示。
 *
 * <p>仅当 {@code app.review.demo-data=true} 时生效，且只在 POI 版本桩存在时写数据；
 * 数据只保存在内存桩中，不写数据库。提交人取数据库中已有的采集者账号，因此需要先由
 * {@code BootstrapUsers} 建好账号，这里用 {@link ApplicationReadyEvent} 保证在启动器之后执行。
 */
@Component
@ConditionalOnProperty(name = "app.review.demo-data", havingValue = "true")
public class ReviewDemoData {
    private static final Logger log = LoggerFactory.getLogger(ReviewDemoData.class);

    private final ObjectProvider<InMemoryPoiRevisionStub> pois;
    private final IdentityService identity;

    public ReviewDemoData(ObjectProvider<InMemoryPoiRevisionStub> pois, IdentityService identity) {
        this.pois = pois;
        this.identity = identity;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        InMemoryPoiRevisionStub stub = pois.getIfAvailable();
        if (stub == null) return;
        if (!stub.latestRevisions().isEmpty()) return;
        List<UserSummary> collectors = identity.listUsers(Role.COLLECTOR);
        if (collectors.isEmpty()) {
            log.warn("未找到采集者账号，跳过 M3 演示数据。请设置 APP_SEED_DEMO=true 或先创建采集者");
            return;
        }
        Instant now = Instant.now();
        UserSummary first = collectors.get(0);
        UserSummary second = collectors.size() > 1 ? collectors.get(1) : first;
        demo(stub, "poi-demo-noodle", "餐饮", "邻家面馆", "街角小面馆，招牌为红底白字，早午营业。",
                first.id(), 30.274112, 120.155072, 9.5, "photo-demo-noodle", now.minusSeconds(7200));
        demo(stub, "poi-demo-store", "零售", "晨光便利店", "24 小时便利店，位于地铁口东侧。",
                second.id(), 30.275903, 120.157661, 12.0, "photo-demo-store", now.minusSeconds(5400));
        demo(stub, "poi-demo-bus", "公共交通", "7 路公交站牌", "站牌标有 7 路、28 路，夜间照明良好。",
                first.id(), 30.272388, 120.160914, 6.0, "photo-demo-bus", now.minusSeconds(3600));
        log.info("已在 M2 桩中写入 {} 条 M3 演示 POI 最新版本", stub.latestRevisions().size());
    }

    private void demo(InMemoryPoiRevisionStub stub, String poiId, String category, String name, String description,
                      UUID collector, double latitude, double longitude, Double accuracy, String photoId, Instant locatedAt) {
        PoiRevision revision = stub.createRevision(new RevisionDraft(poiId, RevisionPurpose.NEW, collector, null,
                category, name, description, latitude, longitude, accuracy, "WGS84", locatedAt, photoId));
        stub.setReviewSummary(poiId, revision.id(),
                new ReviewSummary(ReviewProgress.PENDING_REVIEW, null, "待核验（M2 桩演示数据）"));
    }
}
