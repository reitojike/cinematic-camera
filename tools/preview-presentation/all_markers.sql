-- Trace Processor v57.2。未実trace検証。correlate.pyが数値upidを置換する。
-- 不完全なsliceとchain欠落のdrawも保持し、混在世代を隠さない。
INCLUDE PERFETTO MODULE android.frames.timeline;

WITH markers AS (
  SELECT id, upid, utid, is_main_thread, ts, dur, name
  FROM thread_slice
  WHERE upid IN (TARGET_APP_UPID, TARGET_INJECTOR_UPID)
    AND name GLOB 'cc.preview.*'
), parents AS (
  SELECT m.id AS marker_id, min(df.id) AS do_frame_id, count(df.id) AS do_frame_count
  FROM markers AS m
  JOIN ancestor_slice(m.id) AS ancestor
  JOIN android_frames_choreographer_do_frame AS df
    ON df.id = ancestor.id AND df.upid = m.upid AND df.ui_thread_utid = m.utid
  WHERE m.name GLOB 'cc.preview.draw:*'
  GROUP BY m.id
)
SELECT m.id AS marker_id, m.upid, m.utid, m.is_main_thread, m.ts, m.dur, m.name,
  p.do_frame_id, coalesce(p.do_frame_count, 0) AS do_frame_count
FROM markers AS m LEFT JOIN parents AS p ON p.marker_id = m.id
ORDER BY m.ts, m.id;
