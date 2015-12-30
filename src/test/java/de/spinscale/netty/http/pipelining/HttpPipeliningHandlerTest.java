package de.spinscale.netty.http.pipelining;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.junit.After;
import org.junit.Test;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNotNull;

public class HttpPipeliningHandlerTest {

    private ExecutorService executorService = Executors.newFixedThreadPool(5);
    private Map<String, CountDownLatch> waitingRequests = new ConcurrentHashMap<>();

    @After
    public void closeResources() throws InterruptedException {
        // finish all waitingReqeusts
        for (CountDownLatch latch : waitingRequests.values()) {
            while (latch.getCount() > 0) latch.countDown();
        }

        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    public void testThatPipeliningWorksWithFastSerializedRequests() throws InterruptedException {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new HttpPipeliningHandler(10000), new WorkEmulatorHandler());

        for (int i = 0; i < 5; i++) {
            embeddedChannel.writeInbound(createHttpRequest("/" + String.valueOf(i)));
        }

        for (CountDownLatch latch : waitingRequests.values()) {
            latch.countDown();
        }

        Thread.sleep(10);

        for (int i = 0; i < 5; i++) {
            assertReadHttpMessageHasContent(embeddedChannel, String.valueOf(i));
        }

        assertThat(embeddedChannel.isOpen(), is(true));
    }

    @Test
    public void testThatPipeliningWorksWhenSlowRequestsInDifferentOrder() throws InterruptedException {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new HttpPipeliningHandler(10000), new WorkEmulatorHandler());

        for (int i = 0; i < 5; i++) {
            embeddedChannel.writeInbound(createHttpRequest("/" + String.valueOf(i)));
        }

        // random order execution..
        List<CountDownLatch> latches = new ArrayList<>(waitingRequests.values());
        Collections.shuffle(latches);
        for (CountDownLatch latch : latches) {
            latch.countDown();
        }

        Thread.sleep(10);

        for (int i = 0; i < 5; i++) {
            assertReadHttpMessageHasContent(embeddedChannel, String.valueOf(i));
        }

        assertThat(embeddedChannel.isOpen(), is(true));
    }

    @Test
    public void testThatPipeliningWorksWithChunkedRequests() throws InterruptedException {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new AggregateUrisAndHeadersHandler(), new HttpPipeliningHandler(10000), new WorkEmulatorHandler());

        DefaultHttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/0");
        embeddedChannel.writeInbound(httpRequest);
        embeddedChannel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);

        httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/1");
        embeddedChannel.writeInbound(httpRequest);
        embeddedChannel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);

        waitingRequests.get("1").countDown();
        waitingRequests.get("0").countDown();

        Thread.sleep(10);

        for (int i = 0; i < 2; i++) {
            assertReadHttpMessageHasContent(embeddedChannel, String.valueOf(i));
        }

        assertThat(embeddedChannel.isOpen(), is(true));
    }

    @Test(expected = ClosedChannelException.class)
    public void testThatPipeliningClosesConnectionWithTooManyEvents() throws InterruptedException {
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new HttpPipeliningHandler(2), new WorkEmulatorHandler());

        embeddedChannel.writeInbound(createHttpRequest("/0"));
        // this two are put in the queue
        embeddedChannel.writeInbound(createHttpRequest("/1"));
        embeddedChannel.writeInbound(createHttpRequest("/2"));
        embeddedChannel.writeInbound(createHttpRequest("/3"));

        // finish two requests to fill up the queue
        waitingRequests.get("1").countDown();
        waitingRequests.get("2").countDown();

        // this will close the channel
        waitingRequests.get("3").countDown();

        // this will throw an exception
        Thread.sleep(10);
        embeddedChannel.writeInbound(createHttpRequest("/"));
    }


    private void assertReadHttpMessageHasContent(EmbeddedChannel embeddedChannel, String expectedContent) {

        FullHttpResponse response = (FullHttpResponse) embeddedChannel.outboundMessages().poll();
        assertNotNull("Expected response to exist, maybe you did not wait long enough?", response);
        assertNotNull("Expected response to have content " + expectedContent, response.content());
        String data = new String(ByteBufUtil.getBytes(response.content()), StandardCharsets.UTF_8);
        assertThat(data, is(expectedContent));
    }

    private FullHttpRequest createHttpRequest(String uri) {
        final FullHttpRequest httpRequest = new DefaultFullHttpRequest(HTTP_1_1, HttpMethod.GET, uri);
        return httpRequest;
    }

    /**
     *
     */
    private static class AggregateUrisAndHeadersHandler extends SimpleChannelInboundHandler<HttpRequest> {

        public static final Queue<String> QUEUE_URI = new LinkedTransferQueue<>();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) throws Exception {
            QUEUE_URI.add(request.uri());
        }
    }

    private class WorkEmulatorHandler extends SimpleChannelInboundHandler<HttpPipelinedRequest> {

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final HttpPipelinedRequest pipelinedRequest) throws Exception {
            final QueryStringDecoder decoder;
            if (pipelinedRequest.getRequest() instanceof FullHttpRequest) {
                final FullHttpRequest fullHttpRequest = (FullHttpRequest) pipelinedRequest.getRequest();
                decoder = new QueryStringDecoder(fullHttpRequest.uri());
            } else {
                decoder = new QueryStringDecoder(AggregateUrisAndHeadersHandler.QUEUE_URI.poll());
            }

            final String uri = decoder.path().replace("/", "");
            ByteBuf content = Unpooled.copiedBuffer(uri, StandardCharsets.UTF_8);
            final DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
            httpResponse.headers().add(CONTENT_LENGTH, content.readableBytes());

            final CountDownLatch latch = new CountDownLatch(1);
            waitingRequests.put(uri, latch);

            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        latch.await(2, TimeUnit.SECONDS);
                        ctx.writeAndFlush(pipelinedRequest.createHttpResponse(httpResponse, ctx.channel().newPromise()));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }
}
