package com.myvu.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.myvu.client.protocol.InitBurst;
import com.myvu.client.protocol.Relay;
import com.myvu.client.protocol.RelayMessage;
import com.myvu.client.protocol.RelaySequencer;

import org.junit.Test;

import java.io.InputStream;
import java.util.List;

/**
 * Guards the init burst, which is the piece that originally broke the Python
 * client: replaying the capture's stale high msgIds made the glasses discard
 * everything as out-of-order.
 */
public class InitBurstTest {

    private List<InitBurst.Entry> load() throws Exception {
        InputStream in = getClass().getClassLoader()
                .getResourceAsStream(InitBurst.ASSET_NAME);
        assertNotNull("captured_init.txt missing from test resources", in);
        return InitBurst.load(in);
    }

    @Test
    public void parsesTheCaptureIntoReplayableDataMessages() throws Exception {
        List<InitBurst.Entry> entries = load();
        // The capture holds 32 data messages; the two SyncOffSetTime frames and
        // the sync_clone_data frame are filtered out as stale state.
        assertEquals(29, entries.size());
    }

    @Test
    public void staleStateMessagesAreFiltered() throws Exception {
        for (InitBurst.Entry e : load()) {
            String body = e.bodyText();
            assertFalse("SyncOffSetTime would set the glasses' clock backwards",
                    body.contains("SyncOffSetTime"));
            assertFalse("sync_clone_data would replay an old settings snapshot",
                    body.contains("sync_clone_data"));
        }
    }

    @Test
    public void replayProducesAGaplessSequenceStartingAtOne() throws Exception {
        List<InitBurst.Entry> entries = load();
        RelaySequencer seq = new RelaySequencer();

        int expected = 0;
        for (InitBurst.Entry e : entries) {
            byte[] frame = seq.dataFrame(e.msgBody, e.category, e.needCallback, e.appUniteCode);
            RelayMessage m = Relay.parseFrame(frame);
            assertNotNull(m);
            assertEquals("msgIds must be a fresh 1..N run", ++expected, m.msgId);
        }
        assertEquals(entries.size(), expected);
    }

    @Test
    public void knownInitMessagesSurvive() throws Exception {
        // Spot-check that the filter did not over-match and drop real content.
        boolean sawDeviceInfo = false;
        boolean sawFeatureList = false;
        for (InitBurst.Entry e : load()) {
            String body = e.bodyText();
            if (body.contains("get_device_info")) sawDeviceInfo = true;
            if (body.contains("feature_list")) sawFeatureList = true;
        }
        assertTrue("get_device_info should be in the burst", sawDeviceInfo);
        assertTrue("feature_list should be in the burst", sawFeatureList);
    }

    @Test
    public void everyEntryCarriesANonEmptyBody() throws Exception {
        for (InitBurst.Entry e : load()) {
            assertTrue("frame " + e.frame + " has an empty body", e.msgBody.length > 0);
        }
    }
}
