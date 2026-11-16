package org.cinematic.evaluation;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import org.json.JSONObject;

/** Reads only explicitly staged target-app private files. No picker, storage permission or network IO. */
final class PrivateDatasetRun {
    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    private static byte[] read(File file, int maximum) throws Exception {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() > maximum)
            throw new IllegalArgumentException("Invalid private input");
        // Bound the read itself, not only the size observed before opening.
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IllegalArgumentException("Input too large");
            return bytes;
        }
    }
    private static int dimension(JSONObject sample, String name) throws Exception {
        Object value = sample.get(name);
        if (!(value instanceof Integer) || (Integer) value <= 0)
            throw new IllegalArgumentException("Invalid dimension");
        return (Integer) value;
    }
    private static byte[] pixels(File root, JSONObject sample) throws Exception {
        String id = sample.getString("id");
        if (!id.matches("evaluation-[0-9a-f]{16}")) throw new IllegalArgumentException("Opaque ID required");
        int w = dimension(sample, "width"), h = dimension(sample, "height");
        long count = (long) w * h;
        if (count > 4194304) throw new IllegalArgumentException("Too many pixels");
        File file = new File(root, id + ".rgba");
        if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
            throw new IllegalArgumentException("Input escaped private directory");
        byte[] bytes = read(file, (int) count * 4);
        if (bytes.length != count * 4 || !hash(bytes).equals(sample.getString("sha256")))
            throw new IllegalArgumentException("Pixel identity mismatch");
        for (int i = 3; i < bytes.length; i += 4)
            if ((bytes[i] & 255) != 255) throw new IllegalArgumentException("Opaque pixels required");
        return bytes;
    }
    static String run(Context context, Bundle args) throws Exception {
        if (!"true".equals(args.getString("datasetRunApproved")))
            throw new IllegalArgumentException("Dataset run not approved");
        String expected = args.getString("manifestSha256", "");
        String runId = args.getString("runId", "");
        if (!expected.matches("[0-9a-f]{64}") || !runId.matches("[0-9a-f]{16}"))
            throw new IllegalArgumentException("Explicit manifest and run ID required");
        int repeats = Integer.parseInt(args.getString("repeats", "3"));
        if (repeats < 1 || repeats > 20) throw new IllegalArgumentException("Invalid repeats");
        File root = new File(context.getFilesDir(), "evaluation-input");
        if (Files.isSymbolicLink(root.toPath())) throw new IllegalArgumentException("Invalid input directory");
        byte[] raw = read(new File(root, "manifest.json"), 65536);
        if (!hash(raw).equals(expected)) throw new IllegalArgumentException("Manifest identity mismatch");
        JSONObject manifest = new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        if (manifest.getInt("schema") != 1 || !"upright-srgb-argb8888-v1".equals(manifest.getString("geometry")))
            throw new IllegalArgumentException("Invalid manifest");
        String revision = manifest.getString("revision");
        if (!revision.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Revision required");
        var samples = manifest.getJSONArray("samples");
        if (samples.length() < 1 || samples.length() > 64) throw new IllegalArgumentException("Invalid sample count");
        var seen = new HashSet<String>();
        var seenPixels = new HashSet<String>();
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.getJSONObject(i);
            if (!seen.add(sample.getString("id"))) throw new IllegalArgumentException("Duplicate sample");
            String verifiedHash = hash(pixels(root, sample)); // Preflight before detector/output construction.
            if (!seenPixels.add(verifiedHash)) throw new IllegalArgumentException("Duplicate pixel content");
        }
        File output = new File(context.getFilesDir(), "evaluation-results-" + runId + ".jsonl");
        if (!output.createNewFile()) throw new IllegalArgumentException("Result already exists");
        long start = SystemClock.elapsedRealtimeNanos();
        try (Detector detector = DetectorFactory.create(context, args.getString("mode", "FAST"));
             FileWriter writer = new FileWriter(output)) {
            long initialization = SystemClock.elapsedRealtimeNanos() - start;
            int invocation = 0;
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                byte[] bytes = pixels(root, sample); // Hash the exact bytes converted, even after preflight.
                int[] colors = new int[bytes.length / 4];
                for (int p = 0; p < colors.length; p++) {
                    int j = p * 4;
                    colors[p] = 0xff000000 | (bytes[j] & 255) << 16 | (bytes[j + 1] & 255) << 8 | (bytes[j + 2] & 255);
                }
                Bitmap bitmap = Bitmap.createBitmap(colors, sample.getInt("width"), sample.getInt("height"), Bitmap.Config.ARGB_8888);
                bitmap.setHasAlpha(false);
                try {
                    for (int n = 0; n < repeats; n++) {
                        var row = Measurement.run(detector, bitmap, sample.getString("id"),
                            invocation++ == 0 ? "first-call" : "warm", n, initialization);
                        row.put("input_sha256", sample.getString("sha256")).put("revision", revision)
                            .put("manifest_sha256", expected);
                        writer.write(row + "\n");
                        writer.flush();
                        if (!"SUCCESS".equals(row.getString("status"))) throw new IllegalStateException("Evaluation failed");
                    }
                } finally { bitmap.recycle(); }
            }
        }
        return "private evaluation complete; adoption UNDECIDED";
    }
}
