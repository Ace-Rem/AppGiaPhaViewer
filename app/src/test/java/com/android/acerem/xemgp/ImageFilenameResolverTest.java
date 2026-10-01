package com.android.acerem.xemgp;

import com.android.acerem.xemgp.data.ImageFilenameResolver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ImageFilenameResolverTest {
    @Test public void matchesWebFilenameForIsoDate() {
        assertEquals("nguyenminhkhoi1984.webp", ImageFilenameResolver.forMember("Nguyễn Minh Khôi", "1984-11-28"));
    }

    @Test public void acceptsDayMonthYearInputToo() {
        assertEquals("lethuha1987.webp", ImageFilenameResolver.forMember("Lê Thu Hà", "12-05-1987"));
    }

    @Test public void missingYearHasNoImageFilename() {
        assertNull(ImageFilenameResolver.forMember("Nguyễn Minh Khôi", ""));
    }
}
