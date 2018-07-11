// Copyright 2015-2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Test;

import io.nats.client.Connection;
import io.nats.client.Nats;

public class PublishTests {
    private static final String clusterName = "test-cluster";
    private static final String clientName = "me";


    @Test
    public void testBasicPublish() throws Exception {
        try (NatsStreamingTestServer srv = new NatsStreamingTestServer(clusterName, false)) {
            Options options = new Options.Builder().natsUrl(srv.getURI()).build();
            try (StreamingConnection sc = NatsStreaming.connect(clusterName, clientName, options)) {
                sc.publish("foo", "Hello World!".getBytes());
            }
        }
    }

    @Test
    public void testBasicPublishAsync() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] cbguid = new String[1];
        try (NatsStreamingTestServer srv = new NatsStreamingTestServer(clusterName, false)) {
            Options options = new Options.Builder().natsUrl(srv.getURI()).build();
            try (StreamingConnection sc = NatsStreaming.connect(clusterName, clientName, options)) {
                AckHandler acb = (lguid, ex) -> {
                    cbguid[0] = lguid;
                    latch.countDown();
                };
                String pubguid = sc.publish("foo", "Hello World!".getBytes(), acb);
                assertFalse("Expected non-empty guid to be returned", pubguid.isEmpty());

                assertTrue("Did not receive our ack callback", latch.await(5, TimeUnit.SECONDS));
                assertEquals("Expected a matching guid in ack callback", pubguid, cbguid[0]);
            }
        }
    }

    @Test(expected=IOException.class)
    public void testTimeoutPublishAsync() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] guid = new String[1];
        // Run a STAN server
        try (NatsStreamingTestServer srv = new NatsStreamingTestServer(clusterName, false)) {
            Options opts = new Options.Builder().pubAckWait(Duration.ofMillis(50)).natsUrl(srv.getURI()).build();
            try (StreamingConnection sc = NatsStreaming.connect(clusterName, clientName, opts)) {
                assertNotNull(sc);
                AckHandler acb = (lguid, ex) -> {
                    assertTrue(ex instanceof TimeoutException);
                    latch.countDown();
                };

                // Kill the NATS Streaming server so we timeout
                srv.shutdown();

                guid[0] = sc.publish("foo", "Hello World!".getBytes(), acb);
                assertNotNull(guid[0]);
                assertFalse("Expected non-empty guid to be returned.", guid[0].isEmpty());
                assertTrue("Did not receive our ack callback with a timeout err",
                        latch.await(2, TimeUnit.SECONDS));
            }
        }

    }

    @Test
    public void testMaxPubAcksInFlight() throws Exception {
        int timeoutInSeconds = 5;
        try (NatsStreamingTestServer srv = new NatsStreamingTestServer(clusterName, false)) {
            try (Connection nc = Nats.connect(srv.getURI())) {
                Options opts = new Options.Builder()
                        .maxPubAcksInFlight(1)
                        .pubAckWait(Duration.ofSeconds(timeoutInSeconds))
                        .natsConn(nc)
                        .build();

                StreamingConnection sc = NatsStreaming.connect(clusterName, clientName, opts);
                // Don't defer the close of connection since the server is stopped,
                // the close would delay the test.

                // Cause the ACK to not come by shutdown the server now
                srv.shutdown();

                byte[] msg = "hello".getBytes();

                // Send more than one message, if MaxPubAcksInflight() works, one
                // of the publish call should block for up to PubAckWait.
                Instant start = Instant.now().minusMillis(100);
                try {
                    for (int i = 0; i < 2; i++) {
                            sc.publish("foo", msg, null);
                    }
                } catch (TimeoutException ex) {
                    ex.printStackTrace();
                    // Should get one of these for timeout
                }
                Instant end = Instant.now().plusMillis(100);
                // So if the loop ended before the PubAckWait timeout, then it's a failure.
                if (Duration.between(start, end).compareTo(Duration.ofSeconds(timeoutInSeconds)) < 0) {
                    fail("Should have blocked after 1 message sent");
                }
            }
        }
    }
}