-- Trace Processor v57.2。標準モジュールのdoFrame抽出を利用する。
-- TARGET_PACKAGE と EXACT_IMAGE_WINDOW_LAYER をtraceで確認した値に置き換える。
-- 未検証テンプレート。出力はdrawとSF提示の対応候補であり、50ms合否ではない。
INCLUDE PERFETTO MODULE android.frames.timeline;

WITH markers AS (
  SELECT id, ts, dur, upid, utid,
    substr(name, length('cc.preview.draw:') + 1) AS generation
  FROM thread_slice
  WHERE process_name = 'TARGET_PACKAGE' AND is_main_thread = 1
    AND name GLOB 'cc.preview.draw:*' AND dur >= 0
), candidates AS (
  SELECT m.id AS marker_id, m.generation, m.ts AS draw_ts,
    m.ts + m.dur AS draw_end_ts, df.id AS do_frame_id,
    df.frame_id AS surface_token, app.id AS surface_frame_id,
    app.display_frame_token AS display_token,
    app.dur AS app_dur, app.present_type AS app_present_type,
    app.jank_type AS app_jank_type,
    sf.id AS display_frame_id, sf.ts + sf.dur AS present_ts,
    sf.dur AS sf_dur, sf.present_type AS sf_present_type,
    sf.jank_type AS sf_jank_type,
    count(*) OVER (PARTITION BY m.id) AS candidate_count
  FROM markers AS m
  JOIN ancestor_slice(m.id) AS ancestor
  JOIN android_frames_choreographer_do_frame AS df
    ON df.id = ancestor.id AND df.upid = m.upid AND df.ui_thread_utid = m.utid
  JOIN actual_frame_timeline_slice AS app
    ON app.upid = df.upid AND app.surface_frame_token = df.frame_id
  JOIN actual_frame_timeline_slice AS sf
    ON sf.display_frame_token = app.display_frame_token AND sf.surface_frame_token IS NULL
  JOIN process AS sf_process ON sf_process.upid = sf.upid
  WHERE app.layer_name = 'EXACT_IMAGE_WINDOW_LAYER'
    AND sf_process.name = '/system/bin/surfaceflinger'
    AND app.display_frame_token > 0
), accepted AS (
  SELECT * FROM candidates
  WHERE candidate_count = 1 AND app_dur >= 0 AND sf_dur >= 0
    AND present_ts >= draw_end_ts
    AND app_present_type IN ('On-time Present', 'Early Present', 'Late Present')
    AND sf_present_type IN ('On-time Present', 'Early Present', 'Late Present')
    AND app_jank_type IS NOT NULL AND sf_jank_type IS NOT NULL
    AND app_jank_type NOT GLOB '*Unknown*' AND sf_jank_type NOT GLOB '*Unknown*'
    AND app_jank_type NOT GLOB '*Dropped*' AND sf_jank_type NOT GLOB '*Dropped*'
    AND app_jank_type != 'Unspecified' AND sf_jank_type != 'Unspecified'
)
SELECT m.generation, m.id AS marker_id, a.do_frame_id, a.surface_token,
  a.display_token, a.surface_frame_id, a.display_frame_id, a.present_ts,
  CASE WHEN a.marker_id IS NULL THEN 'UNRESOLVED' ELSE 'CANDIDATE_ONLY' END AS status
FROM markers AS m LEFT JOIN accepted AS a ON a.marker_id = m.id
ORDER BY m.ts;
