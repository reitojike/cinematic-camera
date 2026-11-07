package dev.cinematic.p0.rawdiagnostic;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test-APK process has no target Kotlin runtime. Use only Android/Java here. */
public final class SyntheticDiagnosticProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/x-adobe-dng"; }
    private File fixture() { return new File(getContext().getCacheDir(), "diagnostic-synthetic.dng"); }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"prepareSynthetic".equals(method)) throw new IllegalArgumentException();
        byte[] bytes = extras == null ? null : extras.getByteArray("bytes");
        if (bytes == null || bytes.length > 16384) throw new IllegalArgumentException();
        try (FileOutputStream output = new FileOutputStream(fixture())) { output.write(bytes); }
        catch (IOException error) { throw new IllegalStateException(error); }
        return Bundle.EMPTY;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"r".equals(mode) || !("fixture.dng".equals(name) || "slow.dng".equals(name)))
            throw new FileNotFoundException();
        // Bounded delay makes cancellation reproducible; never used as a timing measurement.
        if ("slow.dng".equals(name)) {
            try { Thread.sleep(1500); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new FileNotFoundException(); }
        }
        return ParcelFileDescriptor.open(fixture(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        throw new UnsupportedOperationException("Metadata must not be queried");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
