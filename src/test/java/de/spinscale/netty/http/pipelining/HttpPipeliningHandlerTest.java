package de.spinscale.netty.http.pipelining;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.After;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

public class HttpPipeliningHandlerTest {

    private static int PORT = 63422;
    private final InetSocketAddress address = new InetSocketAddress("localhost", PORT);;

    private ServerBootstrap serverBootstrap;
    private ExecutorService executorService;

    @After
    public void shutdownHttpServer() throws InterruptedException {
        serverBootstrap.group().shutdownGracefully().awaitUninterruptibly();
        executorService.shutdown();
        executorService.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    public void testThatPipeliningWorks() throws InterruptedException {
        createHttpServer(10000);

        try (HttpClient client = new HttpClient()) {
            Collection<FullHttpResponse> responses = client.sendRequests(address, "/firstfast", "/slow?sleep=250", "/secondfast", "/slow?sleep=500", "/thirdfast");
            assertThat(responses, hasSize(5));
            List<String> responseContents = toString(responses);
            assertThat(responseContents, contains("0", "1", "2", "3", "4"));
        }
    }

    @Test
    public void testThatPipeliningClosesConnectionWithTooManyEvents() throws InterruptedException {
        // TODO this needs better testing... just letting the latch timeout is not too awesome
        createHttpServer(2);

        try (HttpClient client = new HttpClient()) {
            Collection<FullHttpResponse> responses = client.sendRequests(address, "/slow?sleep=500", "/slow?sleep=400", "/slow?sleep=100");
            assertThat(responses, hasSize(0));
        }
    }

    public void createHttpServer(final int size) {
        serverBootstrap = new ServerBootstrap();
        serverBootstrap.channel(NioServerSocketChannel.class);
        serverBootstrap.group(new NioEventLoopGroup());
        serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline()
                        .addLast(new HttpServerCodec())
                        .addLast(new HttpObjectAggregator(8192))
                        .addLast(new HttpPipeliningHandler(size))
                        .addLast(new TestingDelayHandler());
            }
        });

        serverBootstrap.validate();
        serverBootstrap.bind(PORT);

        executorService = Executors.newFixedThreadPool(5);
    }

    private List<String> toString(Collection<FullHttpResponse> responses) {
        List<String> responseContents = new ArrayList<>(responses.size());
        for (FullHttpResponse response : responses) {
            responseContents.add(response.content().toString(StandardCharsets.UTF_8));
        }
        return responseContents;
    }


    private class TestingDelayHandler extends SimpleChannelInboundHandler<HttpPipelinedRequest> {

        public TestingDelayHandler() {
            super();

        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpPipelinedRequest msg) throws Exception {
            executorService.submit(new PossiblySlowRunnable(ctx, msg));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
            e.getCause().printStackTrace();
            ctx.channel().close();
        }
    }

    private class PossiblySlowRunnable implements Runnable {

        private final ChannelHandlerContext ctx;
        private final HttpPipelinedRequest request;

        public PossiblySlowRunnable(ChannelHandlerContext ctx, HttpPipelinedRequest request) {
            this.ctx = ctx;
            this.request = request;
        }

        public void run() {
            ByteBuf buffer = Unpooled.copiedBuffer(request.getRequest().headers().get("X-Counting-Id"), StandardCharsets.UTF_8);
            DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(HTTP_1_1, OK, buffer);
            httpResponse.headers().add(CONTENT_LENGTH, buffer.readableBytes());

            String uri = request.getRequest().getUri();
            QueryStringDecoder decoder = new QueryStringDecoder(uri);

            final int timeout = uri.startsWith("/slow") && decoder.parameters().containsKey("sleep") ? Integer.valueOf(decoder.parameters().get("sleep").get(0)) : 0;
            if (timeout > 0) {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            //System.out.println("Writing request " + request.getRequest().headers().get("X-Counting-Id"));
            ctx.writeAndFlush(request.createHttpResponse(httpResponse, ctx.channel().newPromise()));
        }
    }
}
