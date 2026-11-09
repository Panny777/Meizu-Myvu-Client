package com.myvu.client.transport.ble;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GlassesAdvertisementTest {
    private static final int ID = 0x0bd2;

    /** Synthetic name; the opaque trailing bytes preserve the observed layout. */
    private static byte[] fixture() {
        byte[] data = new byte[16];
        data[0] = 0x13;
        data[1] = 10;
        System.arraycopy("IMIKI A1B2".getBytes(StandardCharsets.US_ASCII), 0, data, 2, 10);
        data[12] = 0x0f;
        data[13] = 0;
        data[14] = 0x16;
        data[15] = 2;
        return data;
    }

    @Test public void identifiesManufacturerOnlyImikiAdvertisement() {
        assertEquals("IMIKI A1B2", GlassesAdvertisement.imikiName(ID, fixture()));
    }

    @Test public void acceptsNameWithoutOpaqueTailAndLowercaseHex() {
        byte[] data = Arrays.copyOf(fixture(), 12);
        data[8] = 'a';
        data[10] = 'b';
        assertEquals("IMIKI a1b2", GlassesAdvertisement.imikiName(ID, data));
    }

    @Test public void rejectsOtherManufacturerIdsAndFieldMarkers() {
        assertNull(GlassesAdvertisement.imikiName(0x0bd1, fixture()));
        assertNull(GlassesAdvertisement.imikiName(0xffff, fixture()));
        byte[] data = fixture();
        data[0] = 0x12;
        assertNull(GlassesAdvertisement.imikiName(ID, data));
    }

    @Test public void rejectsEveryTruncatedNameWithoutThrowing() {
        assertNull(GlassesAdvertisement.imikiName(ID, null));
        for (int length = 0; length < 12; length++) {
            assertNull(GlassesAdvertisement.imikiName(ID, Arrays.copyOf(fixture(), length)));
        }
    }

    @Test public void rejectsInvalidDeclaredLengthsIncludingUnsignedOverflow() {
        for (int length : new int[] {0, 1, 9, 11, 14, 127, 128, 255}) {
            byte[] data = fixture();
            data[1] = (byte) length;
            assertNull(GlassesAdvertisement.imikiName(ID, data));
        }
    }

    @Test public void rejectsUnrelatedNamesAndMalformedSuffixes() {
        for (int index = 2; index < 12; index++) {
            byte[] data = fixture();
            data[index] = 0;
            assertNull(GlassesAdvertisement.imikiName(ID, data));
        }
        for (int value : new int[] {'G', ' ', '-', 0x80, 0xff}) {
            byte[] data = fixture();
            data[11] = (byte) value;
            assertNull(GlassesAdvertisement.imikiName(ID, data));
        }
    }

    @Test public void doesNotSearchArbitraryDataForEmbeddedNames() {
        byte[] data = new byte[fixture().length + 2];
        System.arraycopy(fixture(), 0, data, 2, fixture().length);
        assertNull(GlassesAdvertisement.imikiName(ID, data));
    }
}
