-- M2 POI 采集与地图：POI、采集版本、照片元数据、核验摘要。
-- 归属约定见 docs/architecture.md 第 4、7 节：本迁移只创建 M2 拥有的表。
-- revision 一旦写入不再修改；pois.current_revision_id 指向当前有效版本。
-- poi_review_summaries 由 M3 通过 M2 的 setReviewSummary 写入，M2 只读。

CREATE TABLE pois (
  id UUID PRIMARY KEY,
  current_revision_id UUID,
  created_by UUID NOT NULL REFERENCES app_users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMPTZ
);

CREATE TABLE poi_revisions (
  id UUID PRIMARY KEY,
  poi_id UUID NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  revision_no INT NOT NULL CHECK (revision_no > 0),
  purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('NEW', 'CORRECTION', 'FIELD_CHECK')),
  category VARCHAR(32) NOT NULL CHECK (category IN ('HOUSE', 'SHOP', 'BUS_STOP', 'PARK', 'TREE', 'SCENIC', 'OTHER')),
  name VARCHAR(120) NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  lon DOUBLE PRECISION NOT NULL CHECK (lon BETWEEN -180 AND 180),
  lat DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
  crs VARCHAR(16) NOT NULL,
  located_at TIMESTAMPTZ NOT NULL,
  accuracy_m DOUBLE PRECISION,
  source_task_id UUID,
  submitted_by UUID NOT NULL REFERENCES app_users(id),
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (poi_id, revision_no)
);

ALTER TABLE pois
  ADD CONSTRAINT pois_current_revision_fk FOREIGN KEY (current_revision_id) REFERENCES poi_revisions(id);

CREATE TABLE poi_photos (
  id UUID PRIMARY KEY,
  revision_id UUID NOT NULL REFERENCES poi_revisions(id) ON DELETE CASCADE,
  stored_path VARCHAR(400) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  byte_size BIGINT NOT NULL,
  ocr_text TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- state / progress 的含义由 M3 决定，M2 只原样保存并用于地图展示与筛选，故不设 CHECK。
CREATE TABLE poi_review_summaries (
  poi_id UUID PRIMARY KEY REFERENCES pois(id) ON DELETE CASCADE,
  revision_id UUID REFERENCES poi_revisions(id),
  state VARCHAR(32),
  progress VARCHAR(32),
  error_flag BOOLEAN NOT NULL DEFAULT FALSE,
  summary TEXT,
  updated_by UUID REFERENCES app_users(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX poi_revisions_poi_idx ON poi_revisions (poi_id, revision_no DESC);
CREATE INDEX poi_revisions_location_idx ON poi_revisions (lon, lat);
CREATE INDEX poi_photos_revision_idx ON poi_photos (revision_id);
CREATE INDEX pois_updated_idx ON pois (updated_at DESC);
